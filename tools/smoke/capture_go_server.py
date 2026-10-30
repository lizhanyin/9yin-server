# -*- coding: utf-8 -*-
"""Go 参考 exe（9yin-game-native-menu.exe, -listen 19061）模拟登录抓包。

复用 smoke_client.py 的帧编解码（XOR 0xFA6D3BCC + EE 转义 + EE EE 终止）。
链路：login → worldinfo → [建角] → choose(≥84B 与建角同构) → ready(84B)
→ 空闲推流窗口 → ping。全部帧落盘（.bin 与 decode_ref_capture.py 兼容）。

用法：
  python capture_go_server.py                 # 127.0.0.1:19061，固定账号（复跑走已有角色）
  python capture_go_server.py --fresh         # 随机账号（走建角链）
  python capture_go_server.py --host 127.0.0.1 --port 19061 --seconds 10
  python capture_go_server.py --probes        # ready 后追加 240/291/924 探针抓 S2C 响应
"""
import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from smoke_client import (  # noqa: E402
    activity_custom, ci, create_role_packet, decode_frames, direct_custom,
    encode_frame, op,
)

DEFAULT_HOST, DEFAULT_PORT = "127.0.0.1", 19061


def u32(v):
    return struct.pack('<I', v & 0xFFFFFFFF)


def i64(v):
    return struct.pack('<q', v)


def login_packet(blob32):
    """0x02 ClientLogin：账号 blob 在 0x19、u32 长度在 0x15（16..256 且 16 对齐），
    其后为 v2 多槽位结构（对齐 Go auth.ParseLoginAccountIdentity 的读取范围）。"""
    def slot(b):
        return u32(len(b)) + b
    w = bytearray(b"\x02") + bytearray(0x14)
    w += slot(blob32)          # 账号身份（32B，16 对齐）
    w += slot(bytes(16))       # 密码/凭据槽（私服不校验）
    w += u32(0)
    w += slot(bytes(16))
    w += u32(0)
    return bytes(w)


def recv_frames(sock, expect_min=1, timeout=25.0, quiet=1.5):
    """持续收帧：整体上限 timeout；满足 expect_min 且静默 quiet 秒后提前返回。"""
    sock.settimeout(quiet)
    buf = b""
    frames = []
    start = time.time()
    last = time.time()
    while time.time() - start < timeout:
        try:
            data = sock.recv(65536)
            if not data:
                break
            last = time.time()
            try:
                got, rest = decode_frames(buf + data)
            except ValueError as e:
                print("  !! decode error: %s; drop %d buffered bytes" % (e, len(buf)))
                buf, got, rest = b"", [], b""
            frames.extend(got)
            buf = rest
        except socket.timeout:
            if len(frames) >= expect_min and time.time() - last >= quiet:
                break
        except OSError:
            break
    return frames


class Capture:
    def __init__(self, out_base):
        self.out_base = out_base
        self.frames = []      # (step, t_rel, frame)
        self.t0 = time.time()

    def add(self, step, frames):
        now = time.time() - self.t0
        for f in frames:
            self.frames.append((step, now, f))

    def dump(self):
        with open(self.out_base + '.bin', 'wb') as fh:
            for i, (_, _, fr) in enumerate(self.frames):
                fh.write(struct.pack('<IH', len(fr), i) + fr)
        with open(self.out_base + '.txt', 'w', encoding='utf-8') as fh:
            for i, (step, t, fr) in enumerate(self.frames):
                fh.write("#%d t=%7.2f [%s] op=0x%02X len=%d\n" % (i, t, step, op(fr), len(fr)))
        return len(self.frames)


def histogram(frames):
    ops = [op(f) for f in frames]
    return {hex(o): ops.count(o) for o in sorted(set(ops))}


def main():
    import argparse
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--host', default=DEFAULT_HOST)
    ap.add_argument('--port', type=int, default=DEFAULT_PORT)
    ap.add_argument('--fresh', action='store_true', help='随机账号，强制走建角链')
    ap.add_argument('--seconds', type=float, default=6.0, help='ready 后空闲推流窗口秒数')
    ap.add_argument('--probes', action='store_true', help='追加 240/291/924 自定义探针')
    ap.add_argument('--out', default=None, help='输出前缀（默认 go_capture_<时间戳>）')
    args = ap.parse_args()

    out_base = args.out or os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                        'go_capture_' + time.strftime('%Y%m%d_%H%M%S'))

    blob = os.urandom(16) + bytes(16) if args.fresh else b'sim-login-client\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0'
    assert len(blob) == 32

    try:
        sock = socket.create_connection((args.host, args.port), timeout=5)
    except OSError as e:
        print("无法连接 %s:%d —— %s\n请先启动 Go 服：9yin-game-native-menu.exe -listen %d ..."
              % (args.host, args.port, e, args.port))
        sys.exit(2)

    cap = Capture(out_base)
    print("connected %s:%d account_blob=%s%s"
          % (args.host, args.port, blob[:8].hex(), ' (fresh)' if args.fresh else ''))

    def step(name, pkt, expect=1, timeout=30, quiet=1.5):
        t_send = time.time() - cap.t0
        if pkt:
            sock.sendall(encode_frame(pkt))
        frames = recv_frames(sock, expect, timeout=timeout, quiet=quiet)
        cap.add(name, frames)
        print("[%-8s] t=%6.2f send=%dB -> %3d frames  %s"
              % (name, t_send, len(pkt) if pkt else 0, len(frames), histogram(frames)))
        return frames

    # 1. 登录 → 0x04 角色列表
    f = step("login", login_packet(blob))
    has_role = bool(f) and len(f[0]) > 0x25 and f[0][0x25] == 1
    print("           has_role:", has_role)
    if not f:
        print("!! 登录无响应，终止（服务器可能要求不同登录布局）")
        sock.close()
        sys.exit(1)

    # 2. 世界信息 → 0x05
    step("world", b"\x03")

    # 3. 建角（仅无角色时）→ 0x04 单角色
    create_pkt = create_role_packet()
    if not has_role:
        step("create", create_pkt, timeout=30)
    else:
        print("[create  ] skipped (role exists)")

    # 4. 选角（≥84B 与建角同构）→ 0x09/0x0A/0x15×8/0x0B/0x0D/0x1F/0x10…
    step("choose", b"\x04" + create_pkt[1:], expect=20, timeout=30, quiet=2.0)

    # 5. Ready（恰好 1 字节——现版 exe 严格校验：length 84 会触发
    #    "invalid session message: client opcode 0x09 length 84 (want exactly 1)" 断连）
    step("ready", b"\x09", expect=50, timeout=60, quiet=2.5)

    # 6. 空闲窗口：收服务器主动推流（心跳/定时器/超时踢出等），不发送
    step("idle", None, expect=0, timeout=args.seconds, quiet=args.seconds)

    # 7. ping 0x18 → 心跳回包
    step("ping", b"\x18" + u32(int(time.time() * 1000) & 0xFFFFFFFF), timeout=4, quiet=1.0)

    # 8. 可选探针：抓已知 Go 响应形态（0x10 属性推送 / 924 tick 回显）
    if args.probes:
        step("p240on", activity_custom([ci(240), ci(1)]), timeout=4, quiet=1.0)
        step("p240off", activity_custom([ci(240), ci(0)]), timeout=4, quiet=1.0)
        step("p291", activity_custom([ci(291), ci(0)]), timeout=4, quiet=1.0)
        step("p924", direct_custom(0x1E, [ci(924), i64(int(time.time() * 1000))]),
             timeout=4, quiet=1.0)
        step("p2gm", activity_custom([ci(2), ci(1)]), timeout=4, quiet=1.0)

    sock.close()
    n = cap.dump()
    total_ops = {}
    for _, _, fr in cap.frames:
        total_ops[hex(op(fr))] = total_ops.get(hex(op(fr)), 0) + 1
    print("\ntotal %d frames -> %s.bin / .txt" % (n, out_base))
    print("overall: %s" % dict(sorted(total_ops.items())))
    print("离线分析：python decode_ref_capture.py %s.bin" % out_base)


if __name__ == "__main__":
    main()
