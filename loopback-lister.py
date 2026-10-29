#!/usr/bin/env python3
"""loopback-lister.ps1 的跨平台版本。

监听回环端口，把客户端发来的数据读到首个 CR/LF 为止并存为 request.bin，
随后回发固定的 server list 应答（同时存为 response.bin）。
"""

import argparse
import socket
import sys
from pathlib import Path

# 与 ps1 逐字节一致：\u672C\u5730\u6D4B\u8BD5 是字面 ASCII（PowerShell 单引号不解码转义）
RESPONSE = b'$svrlist #\\u672C\\u5730\\u6D4B\\u8BD5 $127.0.0.1 19061 0\r\n'


def read_request(conn: socket.socket) -> bytes:
    capture = bytearray()
    while b'\r' not in capture and b'\n' not in capture:
        chunk = conn.recv(4096)
        if not chunk:
            break
        capture += chunk
    return bytes(capture)


def serve(port: int, output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(('127.0.0.1', port))
        listener.listen(16)
        print(f'listening on 127.0.0.1:{port}, output dir: {output_dir}', file=sys.stderr, flush=True)
        while True:
            with listener.accept()[0] as conn:
                try:
                    capture = read_request(conn)
                    (output_dir / 'request.bin').write_bytes(capture)
                    (output_dir / 'response.bin').write_bytes(RESPONSE)
                    conn.sendall(RESPONSE)
                    print(f'captured {len(capture)} bytes', file=sys.stderr, flush=True)
                except OSError as exc:
                    print(f'connection error: {exc}', file=sys.stderr, flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description='回环 server list 抓包/假应答器')
    parser.add_argument('-p', '--port', type=int, default=19060, help='监听端口（默认 19060）')
    parser.add_argument('-o', '--output-dir', default='', help='输出目录（默认 <脚本目录>/logs/lister）')
    args = parser.parse_args()
    output_dir = Path(args.output_dir) if args.output_dir else Path(__file__).resolve().parent / 'logs' / 'lister'
    serve(args.port, output_dir)


if __name__ == '__main__':
    main()
