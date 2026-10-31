# -*- coding: utf-8 -*-
"""official_hb.py — 从官服 pcap 中提取 0x38 heartbeat 帧与 C2S 0x18 ping

复用 players_pipeline.py 的 tshark/重组/解码链路，只输出心跳相关证据：
  - C2S 0x18 ping（len、前 12 字节、间隔）
  - S2C 0x38 heartbeat（完整 hex、间隔、字节布局）
用法: python official_hb.py <pcap...>
"""
import struct
import subprocess
import sys

KEY = struct.pack("<I", 0xFA6D3BCC)
SERVER_PORTS = {"7061", "7060", "19061", "8001", "7081", "6001", "4000"}


def read_pcapng(path):
    """极简 pcapng 读取：返回 [(time, src, sport, dst, dport, seq, payload)]（仅 IPv4/TCP）。
    兼容 pcap (0xd4c3b2a1/0xa1b2c3d4) 与 pcapng。"""
    import socket
    import struct as st
    data = open(path, "rb").read()
    pkts = []
    off = 0
    n = len(data)
    magic = data[:4]
    if magic in (b"\xd4\xc3\xb2\xa1", b"\xa1\xb2\xc3\xd4", b"\x4d\x3c\xb2\xa1", b"\xa1\xb2\x3c\x4d"):
        # classic pcap
        endian = "<" if magic in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1") else ">"
        off = 24
        while off + 16 <= n:
            ts, tuf, caplen, _olen = st.unpack_from(endian + "IIII", data, off)
            off += 16
            if off + caplen > n:
                break
            pkts.append((ts + tuf / 1e6, data[off:off + caplen]))
            off += caplen
        linktype = st.unpack_from(endian + "I", data, 20)[0] & 0xFFFF
        eth_skip = 14 if linktype == 1 else 0
    else:
        # pcapng
        linktype = 1
        while off + 12 <= n:
            btype, blen = st.unpack_from("<II", data, off)
            if blen < 12 or off + blen > n:
                break
            if btype == 6:  # Enhanced Packet Block
                iface, th, tl, caplen = st.unpack_from("<IIII", data, off + 8)
                pkt = data[off + 28: off + 28 + caplen]
                pkts.append((th + tl / 1e6, pkt))
            elif btype == 3:  # Simple Packet Block
                bl = blen - 12
                pkt = data[off + 12: off + 12 + bl]
                pkts.append((0.0, pkt))
            elif btype == 1:  # Interface Description
                linktype = st.unpack_from("<H", data, off + 8)[0]
            off += blen
        eth_skip = 14 if linktype == 1 else 0

    rows = []
    for t, pkt in pkts:
        if len(pkt) < eth_skip + 20:
            continue
        ip = pkt[eth_skip:]
        if ip[0] >> 4 != 4:
            continue
        ihl = (ip[0] & 0xF) * 4
        if ip[9] != 6:  # TCP
            continue
        total = st.unpack_from(">H", ip, 2)[0]
        src = socket.inet_ntoa(ip[12:16])
        dst = socket.inet_ntoa(ip[16:20])
        tcp = ip[ihl:total if total else len(ip)]
        if len(tcp) < 20:
            continue
        sport, dport, seq = st.unpack_from(">HHI", tcp, 0)
        doff = (tcp[12] >> 4) * 4
        payload = tcp[doff:]
        if payload:
            rows.append(("%.6f" % t, src, str(sport), dst, str(dport), str(seq), payload.hex(":"), payload))
    return rows


def tshark(path):
    rows = read_pcapng(path)
    return "\n".join("\t".join(r[:7]) for r in rows), rows


def streams_of(text):
    streams = {}
    for line in text.splitlines():
        parts = line.rstrip("\r\n").split("\t")
        if len(parts) < 7:
            continue
        t, src, sport, dst, dport, seq, pay = parts[:7]
        if not pay:
            continue
        try:
            seq = int(seq)
            data = bytes.fromhex(pay.replace(":", ""))
            t = float(t)
        except ValueError:
            continue
        k = (src, sport, dst, dport)
        st = streams.get(k)
        if st is None:
            st = streams[k] = {"next": seq, "buf": bytearray(), "pkts": []}
        if seq < st["next"]:
            off = st["next"] - seq
            if off >= len(data):
                continue
            data = data[off:]
            seq = st["next"]
        st["pkts"].append((t, len(st["buf"]), len(data)))
        st["buf"] += data
        st["next"] = seq + len(data)
    return streams


def frames_of(buf):
    out, body, i = [], bytearray(), 0
    while i < len(buf):
        b = buf[i]
        if b != 0xEE:
            body.append(b)
            i += 1
            continue
        if i + 1 >= len(buf):
            break
        n = buf[i + 1]
        if n == 0x00:
            body.append(0xEE)
            i += 2
        elif n == 0xEE:
            out.append(bytes(body))
            body = bytearray()
            i += 2
        else:
            body.append(b)
            i += 1
    return out


def decode(b):
    return bytes(x ^ KEY[i & 3] for i, x in enumerate(b))


def main(paths):
    for path in paths:
        print("=" * 20, path)
        text, rows = tshark(path)
        if not text.strip():
            print("  (no tcp payloads)")
            continue
        for k, st in streams_of(text).items():
            src, sport, dst, dport = k
            is_from_server = sport in SERVER_PORTS
            fr = frames_of(bytes(st["buf"]))
            if not fr:
                continue
            pings, hbs = [], []
            for idx, f in enumerate(fr):
                if not f:
                    continue
                # 每帧的时间戳：找到覆盖该帧的包
                plain = decode(f)
                op = plain[0] if plain else 0
                if not is_from_server and op == 0x18:
                    pings.append((idx, plain))
                if is_from_server and op == 0x38:
                    hbs.append((idx, plain))
            if not pings and not hbs:
                continue
            tag = "S2C" if is_from_server else "C2S"
            print("  %s %s:%s -> %s:%s  frames=%d" % (tag, src, sport, dst, dport, len(fr)))
            if pings:
                print("   C2S 0x18 pings: %d" % len(pings))
                for idx, p in pings[:4]:
                    print("     len=%d  %s" % (len(p), p.hex(" ")))
            if hbs:
                print("   S2C 0x38 heartbeats: %d" % len(hbs))
                lens = {}
                for idx, h in hbs:
                    lens.setdefault(len(h), 0)
                    lens[len(h)] += 1
                print("     lengths:", lens)
                for idx, h in hbs[:6]:
                    print("     %s" % h.hex(" "))


if __name__ == "__main__":
    main(sys.argv[1:])
