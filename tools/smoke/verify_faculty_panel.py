# -*- coding: utf-8 -*-
"""修炼面板数据链验证：role_faculty 快照 → View 43 帧带真实层数 + msg 1006 应答。

前置：冒烟侠 role_faculty 已种 ng_jh_201 level=29 的快照（同 618）。
"""
import socket
import struct
import sys

sys.path.insert(0, 'G:/pojie/ai/9yin-server/tools/smoke')
from smoke_client import (encode_frame, decode_frames, login_packet, create_role_packet,
                          op, activity_custom, ci)  # noqa: E402

HOST, PORT = "127.0.0.1", 19061
results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("PASS " if cond else "FAIL ") + name + (" | " + detail if detail else ""))


def recv_frames(sock, expect_min=1, timeout=25.0, quiet=1.5):
    sock.settimeout(quiet)
    buf = b""
    frames = []
    start = last = __import__('time').time()
    while __import__('time').time() - start < timeout:
        try:
            data = sock.recv(65536)
            if not data:
                break
            last = __import__('time').time()
            got, rest = decode_frames(buf + data)
            frames.extend(got)
            buf = rest
        except socket.timeout:
            if len(frames) >= expect_min and __import__('time').time() - last >= quiet:
                break
        except OSError:
            break
    return frames


def le16(f, o):
    return struct.unpack('<H', f[o:o + 2])[0]


def view43_books(frames):
    """从 0x18 view=43 帧解出 [(slot, configId, level, totalFill, power)]。"""
    books = []
    for f in frames:
        if op(f) != 0x18 or le16(f, 1) != 43:
            continue
        slot = le16(f, 3)
        p = 7
        cfg, level, fill, power = None, None, None, None
        props = le16(f, 5)
        for _ in range(props):
            key = le16(f, p)
            p += 2
            if key in (1439, 1891, 184, 1936, 1952):
                n = struct.unpack('<I', f[p:p + 4])[0]
                val = f[p + 4:p + 4 + n - 1]
                if key == 1439:
                    cfg = val.decode('utf-8', 'replace')
                p += 4 + n
            elif key in (1464, 1969):
                v = f[p]
                if key == 1464:
                    level = v
                p += 1
            elif key in (2011, 2012):
                p += 2
            else:
                v = struct.unpack('<i', f[p:p + 4])[0]
                if key == 1933 + 2:  # 1935? no-op
                    pass
                if key == 764:
                    fill = v
                elif key == 1932 + 88:  # 不精确占位
                    pass
                p += 4
        books.append((slot, cfg, level, fill))
    return books


def main():
    sock = socket.create_connection((HOST, PORT), timeout=5)
    sock.sendall(encode_frame(login_packet()))
    frames = recv_frames(sock, 1)
    check("login reply 0x04", frames and op(frames[0]) == 0x04)
    if frames[0][0x25] == 0:
        sock.sendall(encode_frame(create_role_packet("冒烟侠")))
        recv_frames(sock, 1)
    sock.sendall(encode_frame(b"\x04"))
    frames = recv_frames(sock, 20, timeout=25)
    check("choose chain", any(op(f) == 0x0B for f in frames))
    sock.sendall(encode_frame(b"\x09"))
    frames = recv_frames(sock, 200, timeout=40, quiet=2.5)
    check("ready ok (frames>100)", len(frames) > 100, str(len(frames)))

    books = view43_books(frames)
    check("View 43 has books", len(books) >= 6, "books=%d" % len(books))
    jh201 = [b for b in books if b[1] == 'ng_jh_201']
    check("ng_jh_201 in View 43", len(jh201) == 1, str(jh201))
    if jh201:
        slot, cfg, level, fill = jh201[0]
        check("ng_jh_201 level == 29 (二十九层)", level == 29,
              "level=%s fill=%s slot=%s" % (level, fill, slot))
        others = [(b[1], b[2]) for b in books if b[1] != 'ng_jh_201'][:5]
        print("  其它书样本:", others)

    # msg 1006 面板查询（sub 21 = 修炼面板查询，F 实测子命令）
    before = len(frames)
    sock.sendall(encode_frame(activity_custom([ci(1006), ci(21)])))
    reply = recv_frames(sock, 1, timeout=6)
    check("msg 1006 sub21 got reply", len(reply) >= 1,
          "frames=%d ops=%s" % (len(reply), [hex(op(f)) for f in reply[:4]]))
    sock.close()

    fails = [r for r in results if not r[1]]
    print("\n== FACULTY PANEL VERIFY %s: %d/%d ==" % ("FAILED" if fails else "OK",
                                                      len(results) - len(fails), len(results)))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
