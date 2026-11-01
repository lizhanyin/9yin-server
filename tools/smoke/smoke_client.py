# -*- coding: utf-8 -*-
"""九阴 Java 服务端冒烟客户端：帧协议(XOR+EE转义)全链路验证。"""
import socket
import struct
import sys

KEY = bytes([0xCC, 0x3B, 0x6D, 0xFA])
HOST, PORT = "127.0.0.1", 19063

results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("PASS " if cond else "FAIL ") + name + (" | " + detail if detail else ""))


def xor(data):
    return bytes(b ^ KEY[i % 4] for i, b in enumerate(data))


def encode_frame(plain):
    out = bytearray()
    for b in xor(plain):
        out.append(b)
        if b == 0xEE:
            out.append(0x00)
    out += b"\xEE\xEE"
    return bytes(out)


def decode_frames(buf):
    """输入线上转义字节流（可跨 recv 分块重喂），返回 (明文帧列表, 未完成帧的原始转义字节)。

    残片必须保持转义形态原样返回：若返回去转义后的体字节，其中的字面 0xEE
    重喂时会被再次当作转义标记，EE xx(x∉{00,EE}) 误报 bad escape——帧体含
    0xEE 且跨分块时必触发（View40 洪泛实测）。
    """
    frames, i, start = [], 0, 0
    while i < len(buf):
        if buf[i] == 0xEE:
            if i + 1 >= len(buf):
                break                      # 终止符/转义对被分块截断
            if buf[i + 1] == 0x00:
                i += 2
                continue
            if buf[i + 1] == 0xEE:
                frames.append(_decode_body(buf[start:i]))
                i += 2
                start = i
                continue
            raise ValueError("bad escape")
        i += 1
    return frames, bytes(buf[start:])


def _decode_body(escaped):
    out = bytearray()
    j = 0
    while j < len(escaped):
        if escaped[j] == 0xEE:             # 帧体内只可能是 EE 00 转义
            out.append(0xEE)
            j += 2
        else:
            out.append(escaped[j])
            j += 1
    return xor(bytes(out))


def recv_frames(sock, expect_min=1, timeout=6.0, quiet=0.5):
    """持续收帧：整体上限 timeout；满足 expect_min 且静默 quiet 秒后提前返回。"""
    import time as _time
    sock.settimeout(quiet)
    buf = b""
    frames = []
    start = _time.time()
    last_data = _time.time()
    while _time.time() - start < timeout:
        try:
            data = sock.recv(262144)
        except socket.timeout:
            if len(frames) >= expect_min and _time.time() - last_data >= quiet:
                break
            continue
        if not data:
            break
        last_data = _time.time()
        buf += data
        new_frames, buf = decode_frames(buf)
        frames.extend(new_frames)
    return frames


def u32(v):
    return struct.pack("<I", v & 0xFFFFFFFF)


def i32(v):
    return struct.pack("<i", v)


def narrow(s):
    b = s.encode("gbk")
    return b"\x06" + u32(len(b) + 1) + b + b"\x00"


def wide(s):
    b = s.encode("utf-16-le")
    return b"\x07" + u32(len(b) + 2) + b + b"\x00\x00"


def login_packet():
    w = bytearray(b"\x02") + bytearray(0x14)
    w += u32(32) + bytes(32)
    return bytes(w)


def create_role_packet(name="冒烟侠", book="book1"):
    w = bytearray(b"\x05") + u32(0)
    w += name.encode("utf-16-le") + b"\x00\x00"
    while len(w) < 0x49:
        w.append(0)
    w += narrow(book)
    return bytes(w)


def activity_custom(msg):
    # 0x0A 信封：21 字节头 + u16 个数 + 值
    w = bytearray(b"\x0A") + bytearray(20) + struct.pack("<H", len(msg))
    for v in msg:
        w += v
    return bytes(w)


def direct_custom(opcode, msg):
    w = bytearray([opcode]) + struct.pack("<H", len(msg))
    for v in msg:
        w += v
    return bytes(w)


def ci(v):
    return b"\x02" + i32(v)


def move_packet(x, y, z, o):
    w = bytearray(b"\x0A") + bytearray(0x25)
    for f in (x, y, z, o):
        w += b"\x04" + struct.pack("<f", f)
    while len(w) < 67:
        w.append(0)
    return bytes(w)


def select_packet(seq, oid, owner, func):
    w = bytearray(b"\x07") + bytearray(16)
    w += u32(seq) + u32(oid) + u32(owner) + u32(func)
    return bytes(w)


def op(f):
    return f[0] & 0xFF


def find_prop(frame, prop_idx, header=0x3F):
    """在 0x0D/0x18 属性区里线性扫 u16 索引（用已知布局断言即可，这里只做存在性提示）。"""
    return None


def main():
    sock = socket.create_connection((HOST, PORT), timeout=5)

    # 1. 登录 → 0x04（无角色）
    sock.sendall(encode_frame(login_packet()))
    frames = recv_frames(sock, 1)
    check("login 0x04 reply", frames and op(frames[0]) == 0x04,
          "op=" + hex(op(frames[0])) if frames else "no reply")
    f0 = frames[0]
    # 重复运行时账号已有角色：0（首跑）或 1（复跑）都可接受
    check("login role count in {0,1}", len(f0) >= 0x29 and f0[0x25] in (0, 1), "count=%d" % f0[0x25])

    # 2. 世界信息 → 0x05
    sock.sendall(encode_frame(b"\x03"))
    frames = recv_frames(sock, 1)
    check("world info 0x05", frames and op(frames[0]) == 0x05)

    # 3. 建角 → 0x04 单角色
    sock.sendall(encode_frame(create_role_packet()))
    frames = recv_frames(sock, 1)
    check("create role 0x04", frames and op(frames[0]) == 0x04 and frames[0][0x25] == 1,
          "count=%d" % (frames[0][0x25] if frames else -1))

    # 4. 选角 → 0x09/0x0A/8×0x15/0x0B/0x0D/0x1F/0x10
    sock.sendall(encode_frame(b"\x04"))
    frames = recv_frames(sock, 14, timeout=9)
    ops = [op(f) for f in frames]
    check("choose chain length", len(ops) >= 14, "n=%d ops=%s" % (len(ops), ops[:16]))
    check("choose has property table 0x09(2424)", ops and ops[0] == 0x09
          and struct.unpack("<H", frames[0][1:3])[0] == 2424)
    check("choose bag views 0x15 x8", ops.count(0x15) == 8, "got %d" % ops.count(0x15))
    check("choose entry 0x0B + add 0x0D + loc 0x1F + vital 0x10",
          0x0B in ops and 0x0D in ops and 0x1F in ops and 0x10 in ops)

    # 5. Ready → NPC + bootstrap
    sock.sendall(encode_frame(b"\x09"))
    frames = recv_frames(sock, 30, timeout=12)
    ops = [op(f) for f in frames]
    npc_adds = ops.count(0x0D)
    check("ready NPC adds >0", npc_adds > 0, "adds=%d" % npc_adds)
    check("ready qinggong View46 created (0x15 x3 total)",
          ops.count(0x15) == 3, "got %d" % ops.count(0x15))
    check("ready task rows flooded (0x11 >= 1600)", ops.count(0x11) >= 1600,
          "rows=%d" % ops.count(0x11))
    check("ready view adds (0x18 == 84)", ops.count(0x18) == 84, "got %d" % ops.count(0x18))
    check("ready skill switch 0x1E", ops.count(0x1E) >= 1, "got %d" % ops.count(0x1E))
    # 找 NPC oid（0x0D 第一个非玩家对象：oid != 1）
    npc_oid = 0
    for f in frames:
        if op(f) == 0x0D:
            oid = struct.unpack("<I", f[1:5])[0]
            if oid != 1:
                npc_oid = oid
                break

    # 6. 打坐（0x0A 信封 240）
    sock.sendall(encode_frame(activity_custom([ci(240), ci(1)])))
    frames = recv_frames(sock, 1, timeout=3)
    ops = [op(f) for f in frames]
    logic = [f for f in frames if op(f) == 0x10 and len(f) > 14
             and struct.unpack("<H", f[12:14])[0] == 462]
    check("sitcross 240 (envelope) LogicState 102", logic and logic[0][14] == 102)

    # 7. 打坐（0x1E 直连 —— 新路由）
    sock.sendall(encode_frame(direct_custom(0x1E, [ci(240), ci(2)])))
    frames = recv_frames(sock, 1, timeout=3)
    logic = [f for f in frames if op(f) == 0x10 and len(f) > 14
             and struct.unpack("<H", f[12:14])[0] == 462]
    check("sitcross 240 (direct 0x1E) LogicState 0", logic and logic[0][14] == 0)

    # 8. 轻功 216（qinggong_10 Consume=10，buf_qg_climb 无 buff 映射）
    sock.sendall(encode_frame(activity_custom([ci(216), narrow("qinggong_10"), ci(1)])))
    frames = recv_frames(sock, 1, timeout=3)
    vital = [f for f in frames if op(f) == 0x10 and len(f) > 12]
    check("qinggong 216 accepted -> vital 0x10", bool(vital))

    # 9. 217 激活高阶轻功 → ActiveQGSkillRec 0x11
    sock.sendall(encode_frame(direct_custom(0x27, [ci(217), narrow("qinggong_gaojie")])))
    frames = recv_frames(sock, 1, timeout=3)
    rec = [f for f in frames if op(f) == 0x11
           and struct.unpack("<H", f[10:12])[0] == 1]
    check("qinggong 217 active record row", bool(rec))

    # 10. 选中 NPC（10001 可能不存在，用下发到的第一个）
    if npc_oid:
        sock.sendall(encode_frame(select_packet(1, npc_oid, 1, 0)))
        frames = recv_frames(sock, 1, timeout=3)
        ops = [op(f) for f in frames]
        check("select NPC -> 0x1D + LastObject", 0x1D in ops and 0x10 in ops,
              "ops=%s" % ops)

    # 11. GM HTTP
    import urllib.request
    with urllib.request.urlopen("http://127.0.0.1:19080/api/status", timeout=3) as r:
        body = r.read().decode("utf-8")
    check("gm /api/status", '"running":true' in body, body[:80])

    # 12. 区服端口（冒烟环境 4002；SvrListHandler 仅对生产端口 4000 生效，逻辑由单测覆盖）
    s2 = socket.create_connection(("127.0.0.1", 4002), timeout=3)
    s2.close()
    check("area port accepts connections", True)

    sock.close()
    fails = [r for r in results if not r[1]]
    print("\n== SMOKE %s: %d/%d passed ==" % ("FAILED" if fails else "OK",
                                              len(results) - len(fails), len(results)))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
