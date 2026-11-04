# -*- coding: utf-8 -*-
"""NPC 对话交互冒烟：0x07 两段式 → 0x1C 菜单 → mark 分发 → 0x1D 告辞（+商店 View61）。

场景无关：就绪帧里找任一 optiontalk 命中 NPC（0x0D 属性区含其 creator id 明文）；
初视口没有就读 0x0B 场景资源 → 对应 creator 目录 XML 取对话 NPC 坐标，0x0A motion
走位触发视口跟随物化后再点。菜单项文本不钉死——服务端下发的就是数据侧文本 key，
冒烟侧用同一份 optiontalk 数据比对；若该 NPC 的对话图 BFS 可达 shop 选项，则顺路
按文本 key 走位到开店项，验 0x16/0x15(View61)/0x18 帧组。购买链（msg70）由
NpcDialogFlowTest（真数据 + MySQL）覆盖。

用法：先启动本地服，再 python -I npc_talk_probe.py [host] [port]（默认 19061）
"""
import base64
import collections
import importlib.util
import os
import re
import struct
import sys

_SPEC = importlib.util.spec_from_file_location(
    'smoke_client', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'smoke_client.py'))
_SC = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_SC)

HOST = _SC.HOST
PORT = _SC.PORT
check = _SC.check
encode_frame = _SC.encode_frame
recv_frames = _SC.recv_frames
results = _SC.results
select_packet = _SC.select_packet
create_role_packet = _SC.create_role_packet
move_packet = _SC.move_packet
op = _SC.op

# 官方 0x02 登录帧（账号摘要+密码结构校验过不去 smoke_client 的极简包），
# 同 verify_move_item.LOGIN_PLAIN 抓包 golden 帧。
LOGIN_PLAIN = base64.b64decode(
    "Ak0BAAAAAAAAAAAAAAAAAAAAAAAAEAAAAEV4Yn56JszEa59b9I1tykcwAAAAAWOSFZpNrD9AWZk2oe0QCIj6ctPsf66QYCRX37RDtXQw//pUygS+/hmlIk6YcM6IAgAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAA==")

SERVER_ROOT = os.environ.get("NINEYIN_SMOKE_ROOT",
                             r"E:\Workspace\project\game\9yin_server1")


def load_optiontalk():
    """(对话 NPC 名集, NPC→DialogID, 节点表)。节点={menu_id, next, opt:[(类型,文本,目标)]}。"""
    rule = os.path.join(SERVER_ROOT, r"resources\modern\share\rule")
    names, npc2dialog, nodes = set(), {}, {}
    cur = sec = None
    try:
        with open(os.path.join(rule, "optiontalk_npc.ini"), encoding="gbk", errors="replace") as fh:
            for line in fh:
                s = line.strip()
                m = re.match(r"^\[([A-Za-z_0-9]+)\]$", s)
                if m:
                    cur = m.group(1)
                    names.add(cur)
                elif s.startswith("DialogID=") and cur:
                    npc2dialog[cur] = int(s.split("=")[1])
    except OSError:
        pass
    try:
        with open(os.path.join(rule, "optiontalk_dialog_pack.ini"),
                  encoding="gbk", errors="replace") as fh:
            for line in fh:
                s = line.strip()
                m = re.match(r"^\[(\d+)\]$", s)
                if m:
                    sec = int(m.group(1))
                    nodes[sec] = {"menu_id": "", "next": [], "opt": []}
                    continue
                if sec is None:
                    continue
                if s.startswith("MenuID="):
                    nodes[sec]["menu_id"] = s.split("=", 1)[1]
                elif s.startswith("NextPage="):
                    nodes[sec]["next"] = [int(x) for x in re.findall(r"\d+", s)]
                elif s.startswith("Option="):
                    for d in s[7:].split(","):
                        p = d.split("|")
                        if len(p) >= 3 and p[0].strip():
                            nodes[sec]["opt"].append(
                                (p[0].strip(), p[1].strip(), p[2].strip()))
    except OSError:
        pass
    return names, npc2dialog, nodes


def shop_path(dialog_id, nodes, depth=8):
    """BFS 首个 shop 选项：返回 (菜单文本 key 路径, shop 目标)。"""
    queue = collections.deque([(dialog_id, [])])
    seen = {dialog_id}
    while queue:
        node_id, path = queue.popleft()
        if len(path) > depth:
            continue
        node = nodes.get(node_id)
        if not node:
            continue
        for kind, text_key, target in node["opt"]:
            if kind == "shop":
                return path + [text_key], target
        for nxt in node["next"]:
            if nxt in seen:
                continue
            seen.add(nxt)
            text = nodes[nxt]["menu_id"] if nxt in nodes else ""
            if text:
                queue.append((nxt, path + [text]))
    return None, None


def find_dialog_npc(frames, dialog_names):
    """0x0D 帧属性区扫 creator id 明文 → (oid, name)。

    名字须含字母且 ≥4 字符——纯数字节名（坐标/数值字节撞车）会全帧误命中。
    """
    candidates = [n for n in dialog_names if len(n) >= 4 and re.search(r"[A-Za-z]", n)]
    for f in frames:
        if op(f) != 0x0D:
            continue
        oid = struct.unpack("<I", f[1:5])[0]
        for name in candidates:
            if name.encode("utf-8") in f:
                return oid, name
    return 0, ""


def scene_resource(frames):
    """0x0B 入场帧属性区抓场景 resource（形如 school09 / born04 / city05）。"""
    for f in frames:
        if op(f) == 0x0B:
            m = re.search(rb"([a-z0-9_]{3,20})\x00", f[12:])
            if m:
                return m.group(1).decode()
    return ""


def dialog_npc_positions(resource, dialog_names):
    """creator/npc_creator/<resource>_* 目录 XML → [(name, x, y, z)]。"""
    base = os.path.join(SERVER_ROOT, r"resources\modern\share\creator\npc_creator")
    out = []
    try:
        entries = [d for d in os.listdir(base) if d.startswith(resource)]
    except OSError:
        return out
    for d in entries:
        folder = os.path.join(base, d)
        for fn in os.listdir(folder):
            if not fn.endswith(".xml"):
                continue
            try:
                with open(os.path.join(folder, fn), encoding="gbk", errors="replace") as fh:
                    for line in fh:
                        if "id=" not in line:
                            continue
                        m_id = re.search(r'id="([A-Za-z_0-9]+)"', line)
                        if not m_id or m_id.group(1) not in dialog_names:
                            continue
                        m_xyz = re.search(r'x="([-\d.]+)"\s+y="([-\d.]+)"\s+z="([-\d.]+)"', line)
                        if m_xyz:
                            out.append((m_id.group(1),
                                        float(m_xyz.group(1)), float(m_xyz.group(2)),
                                        float(m_xyz.group(3))))
            except OSError:
                continue
    return out


def menu_items(frame):
    """解析 0x1C：op|u32 obj|u32 owner|u16 count|项(u8 type,u16 mark,u32 len,UTF16LE+NUL)。"""
    count = struct.unpack("<H", frame[9:11])[0]
    items = []
    offset = 11
    for _ in range(count):
        mark = struct.unpack("<H", frame[offset + 1:offset + 3])[0]
        byte_len = struct.unpack("<I", frame[offset + 3:offset + 7])[0]
        text = frame[offset + 7:offset + 7 + byte_len - 2].decode("utf-16-le")
        items.append((mark, text))
        offset += 7 + byte_len
    return items


def click_menu(sock, npc_oid, seq, items, want_text, timeout=3.0, quiet=0.5):
    """按文本 key 找菜单项并回发 mark；返回 (mark 是否找到, 回帧)。"""
    for mark, text in items:
        if text == want_text:
            sock.sendall(encode_frame(select_packet(seq, npc_oid, 1, mark)))
            return True, recv_frames(sock, 1, timeout=timeout, quiet=quiet)
    return False, []


def main():
    import socket
    host = sys.argv[1] if len(sys.argv) > 1 else HOST
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 19061
    dialog_names, npc2dialog, nodes = load_optiontalk()
    check("optiontalk tables loaded", len(dialog_names) > 1000 and len(nodes) > 500,
          "npcs=%d nodes=%d" % (len(dialog_names), len(nodes)))

    sock = socket.create_connection((host, port), timeout=5)
    sock.sendall(encode_frame(LOGIN_PLAIN))
    frames = recv_frames(sock, 1)
    if not (frames and op(frames[0]) == 0x04):
        check("login 0x04 reply", False, "ops=%s" % [op(f) for f in frames])
        finish(sock)
    sock.sendall(encode_frame(b"\x03"))
    recv_frames(sock, 1)
    if frames[0][0x25] == 0:
        sock.sendall(encode_frame(create_role_packet(name="对话冒烟")))
        recv_frames(sock, 1)
    choose = bytearray(0x54)
    choose[0] = 0x04
    sock.sendall(encode_frame(bytes(choose)))
    choose_frames = recv_frames(sock, 14, timeout=9)
    sock.sendall(encode_frame(b"\x09"))
    frames = choose_frames + recv_frames(sock, 30, timeout=12)

    # 场景对话 NPC 布点；优先选 BFS 可达 shop 的（活体顺路验开店帧组）
    resource = scene_resource(frames)
    placements = dialog_npc_positions(resource, dialog_names) if resource else []
    shop_npc = None
    for name, x, y, z in placements:
        path, target = shop_path(npc2dialog.get(name, 0), nodes)
        if path:
            shop_npc = (name, x, y, z, path, target)
            break
    check("scene %s has dialog npcs" % resource, len(placements) > 0, "count=%d" % len(placements))

    npc_oid, npc_name = find_dialog_npc(frames, dialog_names)
    # 优先走位到 shop 链 NPC（初始站位旁的对话 NPC 不一定是它）
    if shop_npc and npc_name != shop_npc[0]:
        sock.sendall(encode_frame(move_packet(shop_npc[1], shop_npc[2], shop_npc[3], 0.0)))
        frames = recv_frames(sock, 30, timeout=8)
        oid, name = find_dialog_npc(frames, {shop_npc[0]})
        if oid:
            npc_oid, npc_name = oid, name
    if not npc_oid:
        pos = shop_npc or (placements[0] if placements else None)
        check("movable dialog npc placement", pos is not None, "pos=%r" % (pos,))
        if not pos:
            finish(sock)
        sock.sendall(encode_frame(move_packet(pos[1], pos[2], pos[3], 0.0)))
        frames = recv_frames(sock, 30, timeout=8)
        npc_oid, npc_name = find_dialog_npc(frames, dialog_names)
    check("dialog npc materialized", npc_oid > 0, "oid=%d name=%s" % (npc_oid, npc_name))
    if not npc_oid:
        finish(sock)

    # 1. 初次点击 FuncID=0 → 0x10 LastObject + 0x1C 菜单（活体服并发推送心跳帧，
    #    且移动后 0x1F 重放批次洪泛，窗口放大 + 成员断言）
    sock.sendall(encode_frame(select_packet(1, npc_oid, 1, 0)))
    frames = recv_frames(sock, 2, timeout=6, quiet=1.0)
    menu = [f for f in frames if op(f) == 0x1C]
    check("click -> LastObject 0x10 + 0x1C menu",
          any(op(f) == 0x10 for f in frames) and bool(menu),
          "ops=%s" % [op(f) for f in frames][:8])
    if not menu:
        finish(sock)
    items = menu_items(menu[0])
    check("menu binds npc + leave tail",
          struct.unpack("<I", menu[0][1:5])[0] == npc_oid
          and items and items[-1][1] == "ui_form_talk_leave",
          "items=%s" % items)

    # 2. 数据驱动走位：按 shop_path 文本 key 逐项点菜单，末项期望开店帧组。
    #    ⚠ 只点数据侧已知的文本 key（shop 链/告辞）——golden 账号（role 618）上
    #    盲点首个 mark 会命中 giveitem 选项真发物品（2026-11-04 曾给 618 的背包
    #    塞进 2 个 Box_qycdu_009，手工 DELETE 复位）。
    seq = 2
    if shop_npc and npc_name == shop_npc[0]:
        path, target = shop_npc[4], shop_npc[5]
        walked = 0
        for want in path[:-1]:
            ok, frames = click_menu(sock, npc_oid, seq, items, want)
            seq += 1
            if not ok:
                break
            walked += 1
            menu = [f for f in frames if op(f) == 0x1C]
            if not menu:
                break
            items = menu_items(menu[0])
        ok, frames = click_menu(sock, npc_oid, seq, items, path[-1], timeout=6, quiet=1.0)
        ops = [op(f) for f in frames]
        has_view = any(op(f) == 0x15 and struct.unpack("<H", f[1:3])[0] == 61 for f in frames)
        check("shop walk -> View61 frames (0x16+0x15+0x18)",
              ok and has_view and 0x16 in ops and 0x18 in ops,
              "path=%s walked=%d ops=%s" % (path, walked, ops[:10]))
        if has_view:
            shop_id = None
            for f in frames:
                if op(f) == 0x15:
                    idx = f.find(target.encode("utf-8"))
                    if idx > 0:
                        shop_id = target
                        break
            check("createview carries shop id", shop_id == target, "want=%s" % target)
        # 已消费的 shop mark 重放 → 0x1D（防重放；mark 已 take 不会再发物品/开店）
        _, frames = click_menu(sock, npc_oid, 98, items, path[-1])
        check("replayed shop mark -> 0x1D", any(op(f) == 0x1D for f in frames))
    else:
        # 无 shop 链 NPC 就位：用「告辞」mark 验路由（已知安全项，不会触发 giveitem）
        check("shop-capable npc reachable", False,
              "scene=%s 无 shop 链 NPC 就位（移动后未命中 %s）" % (resource, shop_npc[0] if shop_npc else "-"))
        leave_mark = items[-1][0]
        sock.sendall(encode_frame(select_packet(seq, npc_oid, 1, leave_mark)))
        frames = recv_frames(sock, 1, timeout=3)
        check("leave mark routes -> 0x1D", any(op(f) == 0x1D for f in frames))

    # 3. 重开菜单走「告辞」/ 未知 mark
    sock.sendall(encode_frame(select_packet(90, npc_oid, 1, 0)))
    frames = recv_frames(sock, 2, timeout=4)
    menu = [f for f in frames if op(f) == 0x1C]
    if menu:
        items = menu_items(menu[0])
        _, frames = click_menu(sock, npc_oid, 91, items, "ui_form_talk_leave")
        check("leave mark -> 0x1D", any(op(f) == 0x1D for f in frames))
    else:
        check("leave mark -> 0x1D", False, "no menu on reopen")
    sock.sendall(encode_frame(select_packet(92, npc_oid, 1, 999)))
    frames = recv_frames(sock, 1, timeout=3)
    check("stale mark -> 0x1D", any(op(f) == 0x1D for f in frames))

    finish(sock)


def finish(sock):
    sock.close()
    fails = [r for r in results if not r[1]]
    print("\n== NPC-TALK SMOKE %s: %d/%d passed ==" % ("FAILED" if fails else "OK",
                                                       len(results) - len(fails), len(results)))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
