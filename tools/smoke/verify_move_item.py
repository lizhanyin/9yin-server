# -*- coding: utf-8 -*-
"""装备穿/脱端到端验证（msg30 移动 / msg31 右键使用 / msg886 一键穿）：
登录（无角则建角）→ DB 种子武器入包 → 30 拖穿上槽22 → 30 拖卸下 →
31 右键穿 → 886 一键穿，每步断言回帧（0x18/0x19 成对）+ DB 行迁移。
帧方言证据链 = menu2.exe `MOVEITEM (%d,%d)->(%d,%d)` / `USEITEM fwz card` /
`reject malformed native item use values=` / `onestep equip uid=%s`。

用法: python -I verify_move_item.py [host] [port]   (默认 127.0.0.1:19061)
"""
import base64
import hashlib
import importlib.util
import os
import socket
import struct
import subprocess
import sys
import time

_SPEC = importlib.util.spec_from_file_location(
    'smoke_client', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'smoke_client.py'))
_SC = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_SC)
encode_frame = _SC.encode_frame
recv_frames = _SC.recv_frames
create_role_packet = _SC.create_role_packet
activity_custom = _SC.activity_custom
ci = _SC.ci
narrow = _SC.narrow
op = _SC.op

LOGIN_PLAIN = base64.b64decode(
    "Ak0BAAAAAAAAAAAAAAAAAAAAAAAAEAAAAEV4Yn56JszEa59b9I1tykcwAAAAAWOSFZpNrD9AWZk2oe0QCIj6ctPsf66QYCRX37RDtXQw//pUygS+/hmlIk6YcM6IAgAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAA==")

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 19061
MYSQL = 'G:/pojie/ai/mysql-26/bin/mysql.exe'
JSON_ROLES = r'F:/Game/Online/9yin/9yin_server_new/data/roles.json'  # 部署服角色列表来源
ROLE_NAME = '穿脱冒烟侠'
WEAPON = 'blade_battlefield_60601'  # 兜底：DB 查不到 game_items 时用

results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("PASS " if cond else "FAIL ") + name + (" | " + str(detail) if detail else ""))


def _mysql(args, sql):
    """SQL 走 stdin（字节流）：绕开 Windows 命令行对 UTF-8 中文参数的 GBK 转码。"""
    r = subprocess.run(
        [MYSQL, '-unineyin', '-pnineyin123', '-h127.0.0.1', '-P3306', '-D', 'nineyin',
         '--default-character-set=utf8mb4'] + args,
        input=sql.encode('utf-8'), capture_output=True)
    return r.stdout.decode('utf-8', 'replace')


def db(sql):
    return _mysql(['-N'], sql).strip()


def db_batch(sql):
    """TSV 全行读取（\\N=NULL；tab/newline/backslash 转义反转录）。"""
    out = _mysql(['-N', '-B'], sql)
    rows = []
    for line in out.splitlines():
        if not line:
            continue
        rows.append([f.replace('\\t', '\t').replace('\\n', '\n').replace('\\\\', '\\')
                     for f in line.split('\t')])
    return rows


def sql_quote(v):
    if v == 'NULL':
        return 'NULL'
    try:
        float(v)  # 纯数值不引（datetime 含 -: 空格会落到引号分支）
        return v
    except ValueError:
        return "'" + v.replace('\\', '\\\\').replace("'", "\\'") + "'"


def snapshot(role_id):
    """冒烟前物品域快照（结束时原样恢复——冒烟只借用角色，不留下痕迹）。"""
    return {
        'role_equip_items': db_batch('SELECT * FROM role_equip_items WHERE role_id=%d ORDER BY seq' % role_id),
        'role_bag_items': db_batch('SELECT * FROM role_bag_items WHERE role_id=%d ORDER BY seq' % role_id),
    }


def restore_snapshot(role_id, snap):
    for table, rows in snap.items():
        db('DELETE FROM %s WHERE role_id=%d' % (table, role_id))
        for row in rows:
            db('INSERT INTO %s VALUES (%s)' % (table, ','.join(sql_quote(v) for v in row)))


def resolve_role(role_name):
    """选角名 → (role_id, account_id, 是否补建了 roles 行)。

    部署服角色列表走 data/roles.json（identities 里钉 role_id/account_id），
    MySQL roles 表只在外键完整性上被需要（bag/equip 行 FK roles.role_id）——
    JSON 角色需补一行占位（slot=900 避开 uq_roles_account_slot），测后删。
    """
    import json
    import os
    try:
        with open(JSON_ROLES, encoding='utf-8') as fh:
            data = json.load(fh)
        for key, account in data.get('accounts', {}).items():
            if account.get('name') == role_name:
                ident = data.get('identities', {}).get(key, {})
                role_id = int(ident.get('role_id', 0))
                account_id = int(ident.get('account_id', 0))
                if role_id:
                    created = db("SELECT COUNT(*) FROM roles WHERE role_id=%d" % role_id) == '0'
                    if created:
                        db("INSERT INTO roles (role_id, account_id, slot, name) "
                           "VALUES (%d, %d, 900, '%s')" % (role_id, account_id, role_name))
                    return role_id, account_id, created
    except (OSError, ValueError):
        pass
    row = db("SELECT role_id, account_id FROM roles WHERE name='%s'" % role_name)
    if not row:
        return 0, 0, False
    rid, _, acc = row.partition('\t')
    return int(rid), int(acc), False


def view_frame(frames, opcode, view, index):
    for f in frames:
        if op(f) == opcode and struct.unpack_from('<H', f, 1)[0] == view \
                and struct.unpack_from('<H', f, 3)[0] == index:
            return f
    return None


def uid_of(role_id, slot):
    digest = hashlib.md5(('%d:bag:%d' % (role_id, slot)).encode()).digest()
    return ''.join('%02x' % b for b in digest[:8])


def role_name_from_list(frame):
    """0x04 选角帧首角色名（rolelist_appearance_probe.parse_role 同构）。"""
    off = 0x29 + 2 + 1 + 4 + 1 + 4 + 1
    name_len = struct.unpack_from('<I', frame, off)[0]
    off += 4
    return frame[off:off + name_len - 2].decode('utf-16-le')


def main():
    # ---- 登录（无角则建角）→ 选角 → ready ----
    sock = socket.create_connection((HOST, PORT), timeout=5)
    sock.sendall(encode_frame(LOGIN_PLAIN))
    frames = recv_frames(sock, 1, timeout=10)
    if not (frames and op(frames[0]) == 0x04):
        print("FAIL login (no 0x04)")
        return 1
    if frames[0][0x25] == 0:
        sock.sendall(encode_frame(create_role_packet(ROLE_NAME)))
        recv_frames(sock, 1, timeout=10)
        role_name = ROLE_NAME
    else:
        role_name = role_name_from_list(frames[0])
    print("chosen role: %s" % role_name)
    choose = bytearray(0x54)
    choose[0] = 0x04
    sock.sendall(encode_frame(bytes(choose)))
    recv_frames(sock, 14, timeout=15)
    sock.sendall(encode_frame(b"\x09"))
    ready = recv_frames(sock, 5, timeout=8)
    check("login/choose/ready chain", len(ready) >= 1, len(ready))

    role_id, account_id, created_role_row = resolve_role(role_name)
    if not role_id:
        print("FAIL role %r not resolvable (JSON 存档与 MySQL roles 表均未命中)" % role_name)
        return 1
    print("role_id=%d account=%d (roles row %s)" % (
        role_id, account_id, 'created' if created_role_row else 'pre-existing'))
    snap = snapshot(role_id)  # 冒烟只借角色：结束时按快照原样恢复

    # ---- DB 种子：武器入背包装备页空闲格（不整表清——快照兜底恢复） ----
    weapon = db("SELECT item_id FROM game_items WHERE equip_type='Weapon' "
                "AND item_id LIKE 'blade%' LIMIT 1") or WEAPON
    used = {int(r) for r in db("SELECT slot FROM role_bag_items "
                               "WHERE role_id=%d AND view_id=2" % role_id).split()
            if r.strip().isdigit()}
    seed_slot = next((s for s in range(1, 91) if s not in used), 0)
    if not seed_slot:
        print("FAIL bag full, no free slot to seed")
        return 1
    seq = int(db("SELECT COALESCE(MAX(seq),0)+1 FROM role_bag_items WHERE role_id=%d" % role_id) or 1)
    db("INSERT INTO role_bag_items (role_id, seq, slot, config_id, item_type, amount, "
       "view_id, name, script) VALUES (%d, %d, %d, '%s', 0, 1, 2, '', '')"
       % (role_id, seq, seed_slot, weapon))
    check("seed weapon %s in bag slot%d" % (weapon, seed_slot),
          db("SELECT COUNT(*) FROM role_bag_items WHERE role_id=%d AND slot=%d AND view_id=2"
             % (role_id, seed_slot)) == '1')

    # ---- msg30 穿上：[30, 121, seed_slot, 1, 22] ----
    sock.sendall(encode_frame(activity_custom(
        [ci(30), ci(121), ci(seed_slot), ci(1), ci(22)])))
    frames = recv_frames(sock, 3, timeout=8)
    check("wear30: 0x18 doll row (view1 idx22)", view_frame(frames, 0x18, 1, 22) is not None)
    check("wear30: 0x19 source clear (view121 idx%d)" % seed_slot,
          view_frame(frames, 0x19, 121, seed_slot) is not None)
    time.sleep(0.5)
    check("wear30: db equip slot22",
          db("SELECT COUNT(*) FROM role_equip_items WHERE role_id=%d AND slot=22" % role_id) == '1')
    check("wear30: db bag seed slot cleared",
          db("SELECT COUNT(*) FROM role_bag_items WHERE role_id=%d AND slot=%d AND view_id=2"
             % (role_id, seed_slot)) == '0')

    # ---- msg30 卸下：[30, 1, 22, 121, 0]（自动选格） ----
    sock.sendall(encode_frame(activity_custom([ci(30), ci(1), ci(22), ci(121), ci(0)])))
    frames = recv_frames(sock, 3, timeout=8)
    check("off30: 0x19 doll clear (view1 idx22)", view_frame(frames, 0x19, 1, 22) is not None)
    check("off30: 0x18 bag row (view121)",
          any(op(f) == 0x18 and struct.unpack_from('<H', f, 1)[0] == 121 for f in frames))
    time.sleep(0.5)
    check("off30: db equip slot22 empty",
          db("SELECT COUNT(*) FROM role_equip_items WHERE role_id=%d AND slot=22" % role_id) == '0')
    bag_slot = db("SELECT slot FROM role_bag_items WHERE role_id=%d AND view_id=2 LIMIT 1" % role_id)
    check("off30: db bag row exists slot=%s" % bag_slot, bag_slot.isdigit())

    # ---- msg31 右键穿：[31, 121, slot, 1] ----
    sock.sendall(encode_frame(activity_custom(
        [ci(31), ci(121), ci(int(bag_slot)), ci(1)])))
    frames = recv_frames(sock, 3, timeout=8)
    check("wear31: 0x18 doll row", view_frame(frames, 0x18, 1, 22) is not None)
    time.sleep(0.5)
    check("wear31: db equip slot22",
          db("SELECT COUNT(*) FROM role_equip_items WHERE role_id=%d AND slot=22" % role_id) == '1')

    # ---- msg30 再卸下 → msg886 一键穿（uid 认领） ----
    sock.sendall(encode_frame(activity_custom([ci(30), ci(1), ci(22), ci(121), ci(0)])))
    recv_frames(sock, 3, timeout=8)
    time.sleep(0.5)
    bag_slot = db("SELECT slot FROM role_bag_items WHERE role_id=%d AND view_id=2 LIMIT 1" % role_id)
    uid = uid_of(role_id, int(bag_slot))
    sock.sendall(encode_frame(activity_custom([ci(886), narrow(uid)])))
    frames = recv_frames(sock, 3, timeout=8)
    check("onestep886: 0x18 doll row", view_frame(frames, 0x18, 1, 22) is not None)
    time.sleep(0.5)
    check("onestep886: db equip slot22",
          db("SELECT COUNT(*) FROM role_equip_items WHERE role_id=%d AND slot=22" % role_id) == '1')

    # ---- 拒绝路径：30 参数不足 / 31 值数不符 ----
    sock.sendall(encode_frame(activity_custom([ci(30), ci(121), ci(3)])))
    frames = recv_frames(sock, 1, timeout=3)
    check("reject30: no view frames", not any(op(f) in (0x18, 0x19) for f in frames), len(frames))
    sock.sendall(encode_frame(activity_custom([ci(31), ci(121), ci(3)])))
    frames = recv_frames(sock, 1, timeout=3)
    check("reject31(3 vals): no view frames", not any(op(f) in (0x18, 0x19) for f in frames), len(frames))

    # ---- 恢复快照（角色物品域回到冒烟前原样；补建的 roles 占位行一并撤掉） ----
    restore_snapshot(role_id, snap)
    left = db("SELECT (SELECT COUNT(*) FROM role_equip_items WHERE role_id=%d) "
              "+ (SELECT COUNT(*) FROM role_bag_items WHERE role_id=%d)" % (role_id, role_id))
    check("snapshot restored (%s rows)" % left,
          int(left) == len(snap['role_equip_items']) + len(snap['role_bag_items']))
    if created_role_row:
        db('DELETE FROM roles WHERE role_id=%d' % role_id)
    sock.close()

    fails = [r for r in results if not r[1]]
    print("\n== MOVE30/31/886 VERIFY %s: %d/%d ==" % ("FAILED" if fails else "OK",
                                                      len(results) - len(fails), len(results)))
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
