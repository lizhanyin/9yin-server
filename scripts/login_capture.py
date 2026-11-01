#!/usr/bin/env python
"""九阴登录链端到端采集：对目标服重放固定客户端序列并落盘全部响应帧。

用法: python -I login_capture.py <host> <port> <out.json>
帧协议: XOR(0xFA6D3BCC 小端) + 0xEE 转义 + EE EE 终止（与 Go transport 一致）。
"""
import hashlib
import json
import socket
import sys
import time

KEY = bytes([0xCC, 0x3B, 0x6D, 0xFA])
GOLDEN = r"G:\pojie\ai\9yin-server\nineyin\src\test\resources\golden"


def xor(data: bytes) -> bytes:
    return bytes(b ^ KEY[i & 3] for i, b in enumerate(data))


def encode(plain: bytes) -> bytes:
    body = xor(plain)
    out = bytearray()
    for b in body:
        out.append(b)
        if b == 0xEE:
            out.append(0x00)
    out.extend(b"\xEE\xEE")
    return bytes(out)


def decode_frames(buf: bytes):
    frames, body = [], bytearray()
    i = 0
    while i < len(buf):
        b = buf[i]
        if b == 0xEE:
            if i + 1 >= len(buf):
                break  # 尾部不完整
            nxt = buf[i + 1]
            if nxt == 0x00:
                body.append(0xEE)
                i += 2
            elif nxt == 0xEE:
                frames.append(xor(bytes(body)))
                body = bytearray()
                i += 2
            else:
                raise ValueError(f"invalid escape EE {nxt:02X}")
        else:
            body.append(b)
            i += 1
    return frames


def recv_all(sock, idle_ms=1500, total_s=12.0):
    sock.settimeout(idle_ms / 1000.0)
    deadline = time.time() + total_s
    data = bytearray()
    while time.time() < deadline:
        try:
            chunk = sock.recv(65536)
            if not chunk:
                break
            data.extend(chunk)
        except socket.timeout:
            if data:
                break
    return bytes(data)


def create_role_frame(name: str) -> bytes:
    name_bytes = name.encode("utf-16-le")
    cloth = b"cloth_b0001"
    frame = bytearray(0x49 + 1 + 4 + len(cloth) + 1 + 16)
    frame[0] = 0x05
    frame[5:5 + len(name_bytes)] = name_bytes
    off = 0x49
    frame[off] = 0x06
    size = len(cloth) + 1
    frame[off + 1:off + 5] = size.to_bytes(4, "little")
    frame[off + 5:off + 5 + len(cloth)] = cloth
    return bytes(frame)


def main() -> None:
    host, port, out_path = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    first_flight = open(rf"{GOLDEN}\connect_first_flight.bin", "rb").read()

    sock = socket.create_connection((host, port), timeout=5)
    records = []

    def step(label: str, payload: bytes, expect_frames: bool = True) -> None:
        sock.sendall(payload)
        if not expect_frames:
            return
        wire = recv_all(sock)
        for frame in decode_frames(wire):
            records.append({
                "after": label,
                "opcode": frame[0],
                "length": len(frame),
                "sha1": hashlib.sha1(frame).hexdigest(),
                "b64": __import__("base64").b64encode(frame).decode("ascii"),
            })

    # 抓包账号在两侧 seed 存档中已有角色：login(带角色) → world_info → choose → ready → activity。
    # （0x05 创角在 RoleSelection 相位会被状态机拒绝断连，故不在此序列中。）
    step("login", first_flight)
    step("world_info", encode(b"\x03"))
    choose = bytearray(0x54)
    choose[0] = 0x04
    step("choose_role", encode(bytes(choose)))
    step("ready", encode(b"\x09"))
    step("activity", encode(b"\x0a\x00"))
    sock.close()

    with open(out_path, "w", encoding="utf-8") as out:
        json.dump(records, out, indent=1)
    print(f"captured {len(records)} frames -> {out_path}")


if __name__ == "__main__":
    main()
