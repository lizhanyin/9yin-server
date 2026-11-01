#!/usr/bin/env python3
"""Thin JSON-RPC client for the ida-pro-mcp HTTP endpoint (127.0.0.1:13337).

Usage:
  python ida_mcp.py <tool> '<json-args>'
  python ida_mcp.py --list
Reads code for py_eval/py_exec from stdin when args are '-':
  echo 'print(1+1)' | python ida_mcp.py py_eval -
"""
import json
import sys
import urllib.request

URL = "http://127.0.0.1:13337/mcp"


def call(name: str, args: dict, timeout: int = 300):
    payload = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                          "params": {"name": name, "arguments": args}}).encode()
    req = urllib.request.Request(URL, data=payload,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = json.load(r)
    if "error" in body:
        raise SystemExit(f"MCP error: {body['error']}")
    result = body["result"]
    texts = [c.get("text", "") for c in result.get("content", [])
             if c.get("type") == "text"]
    out = "\n".join(texts)
    if result.get("isError"):
        raise SystemExit(f"tool error: {out}")
    return out


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--list":
        payload = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}).encode()
        req = urllib.request.Request(URL, data=payload,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as r:
            body = json.load(r)
        for t in body["result"]["tools"]:
            print(t["name"])
        return
    name = sys.argv[1]
    raw = sys.argv[2] if len(sys.argv) > 2 else "{}"
    args = json.loads(raw)
    if isinstance(args, str):
        args = json.loads(args)
    print(call(name, args))


if __name__ == "__main__":
    main()
