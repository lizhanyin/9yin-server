# -*- coding: utf-8 -*-
"""C2S 99 操作模式全链探针（生产口 19061，冒烟账号隔离，无破坏性操作）。

链路：登录→建角→选角→ready → [99,1]/[99,0] 直发期待 0x10 idx223 回推、
非法值零帧 → 重登期待 post-spawn OperateMode 回推 → DB operate_mode 核对。

用法: python -I probe_operate_mode.py [port]
"""
import os
import socket
import struct
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from smoke_client import (encode_frame, recv_frames, direct_custom,  # noqa: E402
                          activity_custom, ci, create_role_packet, move_packet)

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 19061
MYSQL = r"G:\pojie\ai\mysql-26\bin\mysql.exe"

results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("PASS " if cond else "FAIL ") + name + (" | " + detail if detail else ""))


def load_types():
    table = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                         "..", "..", "nineyin", "src", "main", "resources",
                         "nineyin", "protocol", "official_property_table.txt")
    types = {}
    for line in open(table, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 3 and parts[0].isdigit():
            types[int(parts[0])] = int(parts[2])
    return types


def prop_value(frame, want_idx, types):
    """0x10 帧：u8 isView | u32 obj | u32 owner | u16 count | (u16 idx + typed)*。"""
    if (frame[0] & 0xFF) != 0x10 or len(frame) < 12:
        return None
    count = struct.unpack_from("<H", frame, 10)[0]
    off = 12
    for _ in range(count):
        if off + 2 > len(frame):
            return None
        idx = struct.unpack_from("<H", frame, off)[0]
        off += 2
        code = types.get(idx)
        size = {1: 1, 2: 2, 3: 4, 5: 4, 4: 8, 9: 8}.get(code)
        if code in (7, 8):
            if off + 4 > len(frame):
                return None
            size = 4 + struct.unpack_from("<I", frame, off)[0]
        if size is None or off + size > len(frame):
            return None
        if idx == want_idx:
            if code == 3:
                return struct.unpack_from("<i", frame, off)[0]
            return ("str", struct.unpack_from("<I", frame, off)[0])
        off += size
    return None


def archive_value(frame, want_idx, types):
    """0x0D 玩家属性档：0x3f 固定首部后 (u16 idx + typed)*（count@0x3d）。

    注意 idx=0 是合法属性（Type=BYTE）——按 count 驱动，勿用 0 哨兵。
    """
    if (frame[0] & 0xFF) != 0x0D or len(frame) <= 0x41:
        return None
    count = struct.unpack_from("<H", frame, 0x3d)[0]
    off = 0x3f
    for _ in range(count):
        if off + 2 > len(frame):
            return None
        idx = struct.unpack_from("<H", frame, off)[0]
        off += 2
        code = types.get(idx)
        size = {1: 1, 2: 2, 3: 4, 5: 4, 4: 8, 9: 8}.get(code)
        if code in (7, 8):
            if off + 4 > len(frame):
                return None
            n = struct.unpack_from("<I", frame, off)[0]
            if n <= 0 or off + 4 + n > len(frame):
                return None
            if idx == want_idx:
                return ("str", n, frame[off + 4:off + 4 + n - 1].decode(errors="replace"))
            off += 4 + n
            continue
        if size is None or off + size > len(frame):
            return None
        if idx == want_idx:
            return struct.unpack_from("<i", frame, off)[0] if code == 3 else code
        off += size
    return None


def login_and_ready(sock):
    # 完整登录槽：header 25B + u32 len/bytes ×(account/password) + u32 client
    # + u32 len/bytes(login-string) + u32 login-type（LoginIdentity.parse 全检）。
    w = bytearray(b"\x02") + bytearray(0x14)
    w += struct.pack("<I", 16) + bytes(16)
    w += struct.pack("<I", 16) + bytes(16)
    w += struct.pack("<I", 0)
    w += struct.pack("<I", 16) + bytes(16)
    w += struct.pack("<I", 0)
    sock.sendall(encode_frame(bytes(w)))
    frames = recv_frames(sock, 1)
    if not frames or (frames[0][0] & 0xFF) != 0x04:
        return None, "login 0x04 fail"
    count = frames[0][0x25] if len(frames[0]) > 0x25 else -1
    sock.sendall(encode_frame(b"\x03"))
    frames = recv_frames(sock, 1)
    if not frames or (frames[0][0] & 0xFF) != 0x05:
        return None, "world 0x05 fail"
    if count == 0:
        sock.sendall(encode_frame(create_role_packet(name="模式探针", book="book1")))
        frames = recv_frames(sock, 1)
        if not frames or (frames[0][0] & 0xFF) != 0x04 or frames[0][0x25] != 1:
            return None, "create role fail"
    sock.sendall(encode_frame(b"\x04" + bytearray(0x53)))  # 长度闸 ≥0x54，体不读
    choose = recv_frames(sock, 14, timeout=9)
    if len(choose) < 14:
        return None, "choose chain short"
    sock.sendall(encode_frame(b"\x09"))
    frames = choose + recv_frames(sock, 30, timeout=15)
    # 活动包推进 AWAITING_ACTIVITY → IN_WORLD（GM 切图前置相位；正常客户端就绪后必发）
    sock.sendall(encode_frame(move_packet(377.4, 20.3, 1021.6, 2.9)))
    frames += recv_frames(sock, 1, timeout=3)
    return frames, None


def sql_query(sql):
    out = subprocess.run([MYSQL, "-unineyin", "-pnineyin123", "-h127.0.0.1",
                          "-P3306", "-N", "-B", "nineyin", "-e", sql],
                         capture_output=True, text=True)
    return out.stdout.strip()


def main():
    types = load_types()
    sock = socket.create_connection(("127.0.0.1", PORT), timeout=5)
    frames, err = login_and_ready(sock)
    check("login+ready chain", err is None, err or f"{len(frames)} frames")
    if err:
        sys.exit(1)

    # [99, 1] 直达 → 0x10 idx223 val 1
    sock.sendall(encode_frame(direct_custom(0x1E, [ci(99), ci(1)])))
    frames = recv_frames(sock, 1, timeout=3)
    got = [prop_value(f, 223, types) for f in frames]
    check("msg99 mode=1 echo 0x10 idx223", 1 in got, f"values={got}")

    # [99, 0] 信封 → 0x10 idx223 val 0
    sock.sendall(encode_frame(activity_custom([ci(99), ci(0)])))
    frames = recv_frames(sock, 1, timeout=3)
    got = [prop_value(f, 223, types) for f in frames]
    check("msg99 mode=0 echo (envelope)", 0 in got, f"values={got}")

    # [99, 5] 非法 → 无 idx223 回推帧（环境流量 294/0x21 属正常背景）
    sock.sendall(encode_frame(direct_custom(0x1E, [ci(99), ci(5)])))
    frames = recv_frames(sock, 1, timeout=1.5, quiet=0.8)
    got = [prop_value(f, 223, types) for f in frames]
    check("msg99 invalid rejected silent",
          not any(v in (0, 1) for v in got), f"frames={len(frames)} idx223={got}")

    # [195, scheme, str] 键位方案上传 → 回推 198/199/200
    bind = "1,87;2,83;3,81;4,69;5,65;6,68;7,32;8,90;"

    def scheme_hits(frames):
        """仅解析结构合法的单属性串帧：idx + u32 len + utf8。"""
        out = []
        for f in frames:
            if (f[0] & 0xFF) != 0x10 or len(f) < 22:
                continue
            count = struct.unpack_from("<H", f, 10)[0]
            idx = struct.unpack_from("<H", f, 12)[0]
            if count != 1 or idx not in (198, 199, 200):
                continue
            n = struct.unpack_from("<I", f, 14)[0]
            if not (0 < n <= len(f) - 18):
                continue
            try:
                out.append((idx, f[18:18 + n - 1].decode()))
            except UnicodeDecodeError:
                continue
        return out

    for scheme, want_idx in ((1, 199), (2, 200), (0, 198)):
        sock.sendall(encode_frame(direct_custom(
            0x1E, [ci(195), ci(scheme),
                   b"\x06" + struct.pack("<I", len(bind) + 1) + bind.encode() + b"\x00"])))
        frames = recv_frames(sock, 1, timeout=3)
        hits = scheme_hits(frames)
        hit = [h for h in hits if h[0] == want_idx and h[1] == bind + " "]
        check(f"msg195 scheme={scheme} echo idx={want_idx}", bool(hit), f"hits={hits[:3]}")

    sock.close()

    # DB 落库核对（冒烟账号角色）
    row = sql_query("SELECT operate_mode FROM role_keybinds rk JOIN roles r "
                    "ON r.role_id = rk.role_id WHERE r.name = '模式探针'")
    check("db operate_mode persisted = 0", row == "0", f"row={row!r}")
    row = sql_query("SELECT LENGTH(shortcut_key1), LENGTH(shortcut_key2), LENGTH(bind_value) "
                    "FROM role_keybinds rk JOIN roles r ON r.role_id = rk.role_id "
                    "WHERE r.name = '模式探针'")
    check("db keybind schemes persisted", row == f"{len(bind)}\t{len(bind)}\t{len(bind)}",
          f"row={row!r}")

    # 重登 → post-spawn 回推 idx223 = 0 + 198/199/200 + 0x0D 档内嵌
    sock = socket.create_connection(("127.0.0.1", PORT), timeout=5)
    frames, err = login_and_ready(sock)
    check("relogin chain", err is None, err or f"{len(frames)} frames")
    # 0x0D 属性档：引擎建对象时读——模式必须在这里（2026-11-04"模式没推"根因）
    arch = [archive_value(f, 223, types) for f in frames if (f[0] & 0xFF) == 0x0D]
    check("relogin 0x0D archive carries OperateMode=0", 0 in arch, f"arch={arch}")
    arch_key = [archive_value(f, 198, types) for f in frames if (f[0] & 0xFF) == 0x0D]
    check("relogin 0x0D archive carries ShortcutKey",
          any(isinstance(a, tuple) and a[2] == bind for a in arch_key), f"arch={arch_key}")
    got = [prop_value(f, 223, types) for f in frames if (f[0] & 0xFF) == 0x10]
    check("relogin post-spawn OperateMode=0 pushed", 0 in got, f"values={got}")
    idx_hits = [(idx, text[:20]) for idx, text in scheme_hits(frames)]
    for want in (198, 199, 200):
        check(f"relogin pushed idx={want}", any(h[0] == want for h in idx_hits),
              f"hits={idx_hits}")

    # ---- 跨场景（GM switch_scene_preset）后四属性重推 ----
    import json
    import urllib.request
    import urllib.error

    def http(method, path, body=None):
        url = "http://127.0.0.1:19080" + path + ("?__s=127.0.0.1:19081" if "?" not in path else "&__s=127.0.0.1:19081")
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.loads(r.read().decode())

    sessions = http("GET", "/api/sessions")
    sid = None
    for s in sessions:
        if s.get("name") == "模式探针":
            sid = s.get("sessionId")
            break
    if sid is None:
        check("gm session located", False, f"sessions={[s.get('name') for s in sessions][:5]}")
    else:
        check("gm session located", True, f"sessionId={sid}")
        before = len(scheme_hits([]))  # 0；占位保持结构简单
        # 排空旧帧
        recv_frames(sock, 1, timeout=0.5, quiet=0.3)
        try:
            r = http("POST", f"/api/sessions/{sid}/command",
                     {"action": "switch_scene_preset", "preset": "yanjing"})
            check("gm switch_scene_preset ok", r.get("ok") is True, str(r)[:80])
        except urllib.error.HTTPError as e:
            r = {}
            check("gm switch_scene_preset ok", False, f"HTTP {e.code}: {e.read()[:80]!r}")
        if r.get("ok") is True:
            # 0x0C → (750ms) 0x0B+0x0D → 发 0x09 → 收尾重推。环境 0x10 流量会插队，
            # 按操作码等待而非收帧计数。
            frames = []
            import time as _time
            deadline = _time.time() + 10
            while _time.time() < deadline:
                frames += recv_frames(sock, 1, timeout=2, quiet=0.5)
                ops = [f[0] & 0xFF for f in frames]
                if 0x0C in ops and 0x0B in ops and 0x0D in ops:
                    break
            ops = [f[0] & 0xFF for f in frames]
            check("switch chain 0x0C+0x0B+0x0D", 0x0C in ops and 0x0B in ops and 0x0D in ops,
                  f"ops={[hex(o) for o in ops[:10]]}")
            arch = [archive_value(f, 223, types) for f in frames if (f[0] & 0xFF) == 0x0D]
            check("scene-switch 0x0D archive carries OperateMode", 0 in arch, f"arch={arch}")
            sock.sendall(encode_frame(b"\x09"))
            frames = recv_frames(sock, 30, timeout=12)
            hits = [(i, t[:16]) for i, t in scheme_hits(frames)]
            got223 = [prop_value(f, 223, types) for f in frames if (f[0] & 0xFF) == 0x10]
            for want in (198, 199, 200):
                check(f"scene-switch re-pushed idx={want}", any(h[0] == want for h in hits),
                      f"hits={hits}")
            check("scene-switch re-pushed idx=223", 0 in got223, f"223={got223}")
    sock.close()

    fails = [r for r in results if not r[1]]
    print("\n== PROBE %s: %d/%d passed ==" % ("FAILED" if fails else "OK",
                                              len(results) - len(fails), len(results)))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
