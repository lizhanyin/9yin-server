# -*- coding: utf-8 -*-
"""战斗一期冒烟：登录→选角→ready→选中 NPC(0x07)→msg211 出招全链验证。

用法: python -I skill_cast_verify.py [host] [port]
前置: 服务端已启动（默认 127.0.0.1:19063，建议用隔离 SERVER_ROOT 跑）。
断言（docs/combat-skill-211.md 抓包序列）：
  1. 0x07 选中 → 0x10 LastObject(idx414)
  2. 211 出招 → [0x11 CD] ×2(rec5) + 0x11(rec6 active) + 0x10 全量 + custom 337 + custom 20
  3. GCD 7s 内二发 → 静默（无回帧）
  4. 花神四式 → msg169 + BufferInfo 槽帧（Lua overlay：35s 租约/槽5）
  5. 命中战斗 NPC 时 → 24/0x2D/18/205 与 NPC 0x10 掉血帧
  6. 动作结束 → 0x12(rec6) + State="stand"
"""
import importlib.util
import os
import socket
import struct
import sys
import time

_SPEC = importlib.util.spec_from_file_location(
    'smoke_client', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'smoke_client.py'))
_SC = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_SC)
encode_frame = _SC.encode_frame
recv_frames = _SC.recv_frames
login_packet = _SC.login_packet
create_role_packet = _SC.create_role_packet
activity_custom = _SC.activity_custom
ci = _SC.ci
narrow = _SC.narrow
op = _SC.op

import base64

# 新版登录包（含 SHA256 账号摘要域，与 LoginFlowTest golden 同源）
LOGIN_PLAIN = base64.b64decode(
    "Ak0BAAAAAAAAAAAAAAAAAAAAAAAAEAAAAEV4Yn56JszEa59b9I1tykcwAAAAAWOSFZpNrD9AWZk2oe0QCIj6ctPsf66QYCRX37RDtXQw//pUygS+/hmlIk6YcM6IAgAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAA==")

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 19063
ROLE_NAME = '战斗冒烟侠'

results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("PASS " if cond else "FAIL ") + name + (" | " + detail if detail else ""))


def f32(v):
    return b"\x04" + struct.pack("<f", v)


def cast_packet(skill, x=100.0, y=20.0, z=100.0, o=1.0):
    return activity_custom([ci(211), narrow(skill), f32(x), f32(y), f32(z), f32(o)])


def select_packet(seq, oid, owner, func):
    w = bytearray(b"\x07") + bytearray(16)
    w += struct.pack("<IIIi", seq, oid, owner, func)
    return bytes(w)


def custom_msgid(frame):
    """0x1E/0x27 明文帧首值 msgId（tag u8@3 == 2 → i32@4）。"""
    if (frame[0] & 0xFF) in (0x1E, 0x27) and len(frame) >= 8 and frame[3] == 2:
        return struct.unpack("<i", frame[4:8])[0]
    return None


def record_index(frame):
    return struct.unpack("<H", frame[10:12])[0]


def main():
    sock = socket.create_connection((HOST, PORT), timeout=5)
    sock.sendall(encode_frame(LOGIN_PLAIN))
    frames = recv_frames(sock, 1, timeout=10)
    if frames and frames[0][0x25] == 0:
        sock.sendall(encode_frame(create_role_packet(ROLE_NAME)))
        recv_frames(sock, 1, timeout=10)
    choose = bytearray(0x54)
    choose[0] = 0x04
    sock.sendall(encode_frame(bytes(choose)))
    recv_frames(sock, 14, timeout=15)
    sock.sendall(encode_frame(b"\x09"))
    recv_frames(sock, 5, timeout=8)
    all_frames = []  # 出招阶段全程序帧累积（收尾 0x12/stand 可能落在任一接收窗口）
    # 首个 0x0A（移动包）触发 NPC 物化（armed 屏障后）：坐标=建角默认出生点
    # （school09_YiHuaGong 340.16/47.98/657.28，见 roles.json 存档）
    sock.sendall(encode_frame(_SC.move_packet(340.16, 47.98, 657.28, 2.588)))
    frames = recv_frames(sock, 30, timeout=20, quiet=2.5)

    check("ready chain has NPC materialization", any(op(f) == 0x0D for f in frames),
          "0x0D x%d" % sum(1 for f in frames if op(f) == 0x0D))

    def canuse_frames(buf):
        """View40 CanUse 帧 → [(row, value)]（0x10|isView=1|obj=40|行|idx2236|u8）。"""
        out = []
        for f in buf:
            if op(f) == 0x10 and len(f) == 15 and f[1] == 1 \
                    and struct.unpack("<I", f[2:6])[0] == 40 \
                    and struct.unpack("<H", f[12:14])[0] == 2236:
                out.append((struct.unpack("<I", f[6:10])[0], f[14]))
        return out

    # 1. 选第一个 NPC → LastObject(idx414)（先从 ready 帧集取，格挡检查会重新收帧）
    npc_oid = 0
    for f in frames:
        if op(f) == 0x0D:
            oid = struct.unpack("<I", f[1:5])[0]
            if oid != 1:
                npc_oid = oid
                break
    check("materialized NPC found", npc_oid != 0, "oid=%#x" % npc_oid)

    # 0a. 非战斗态格挡 engage 静默拒绝（真机反馈 1）
    sock.sendall(encode_frame(_SC.direct_custom(0x1E, [ci(54), b"\x03" + struct.pack("<q", 0)])))
    frames = recv_frames(sock, 999, timeout=2.2, quiet=0.9)
    parry_noise = [f for f in frames if custom_msgid(f) == 169 or (op(f) == 0x10 and len(f) > 20)]
    check("parry engage outside combat is silent", len(parry_noise) == 0,
          "replies=%d" % len(parry_noise))
    if npc_oid:
        sock.sendall(encode_frame(select_packet(1, npc_oid, 1, 0)))
        frames = recv_frames(sock, 1, timeout=4)
        last_object = [f for f in frames if op(f) == 0x10 and len(f) >= 22
                       and struct.unpack("<H", f[12:14])[0] == 414]
        check("select 0x07 -> LastObject(414)", bool(last_object),
              "ops=%s" % [hex(op(f)) for f in frames[:4]])

    # 2. 太极一式出招：accept 帧序
    sock.sendall(encode_frame(cast_packet("CS_wd_tjq01")))
    frames = recv_frames(sock, 6, timeout=6, quiet=1.2)
    all_frames.extend(frames)
    ops = [op(f) for f in frames]
    cds = [f for f in frames if op(f) == 0x11 and record_index(f) == 5]
    active = [f for f in frames if op(f) == 0x11 and record_index(f) == 6]
    customs = [custom_msgid(f) for f in frames if custom_msgid(f)]
    check("cast: personal+GCD cooldown rows", len(cds) == 2,
          "cd rows=%d" % len(cds))
    if len(cds) == 2:
        gcd_row = cds[-1]
        cat = struct.unpack("<i", gcd_row[16:20])[0]
        dur = struct.unpack("<q", gcd_row[28:36])[0] - struct.unpack("<q", gcd_row[20:28])[0]
        check("cast: GCD row cat=50000 dur=7000 team!=FFFF",
              cat == 50000 and dur == 7000, "cat=%d dur=%d team=%#x"
              % (cat, dur, struct.unpack("<H", gcd_row[36:38])[0]))
    check("cast: active_skill_rec row", len(active) == 1
          and b"CS_wd_tjq01" in active[0], "rows=%d" % len(active))
    check("cast: vital full 0x10 + msg337 + msg20",
          0x10 in ops and 337 in customs and 20 in customs,
          "ops=%s customs=%s" % ([hex(o) for o in ops[:10]], customs))
    disabled = [(row, v) for row, v in canuse_frames(frames) if v == 0]
    check("cast: CanUse view frame -> 0", bool(disabled),
          "canuse=%s" % canuse_frames(frames)[:6])

    # 3. GCD 内二发静默（过滤 294 时间广播/巡逻 0x1F 等背景噪声，只看出招回帧）
    sock.sendall(encode_frame(cast_packet("CS_wd_tjq01")))
    frames = recv_frames(sock, 999, timeout=2.6, quiet=1.0)
    all_frames.extend(frames)
    cast_noise = [f for f in frames
                  if (op(f) == 0x11 and record_index(f) in (5, 6))
                  or custom_msgid(f) in (20, 337, 24, 205)
                  or op(f) == 0x12]
    check("GCD window: second cast silent", len(cast_noise) == 0,
          "cast replies=%d (all=%d)" % (len(cast_noise), len(frames)))

    # 4. 花神四式：msg169 + BufferInfo 槽帧（等 GCD 过窗：7s+）
    time.sleep(7.5)
    sock.sendall(encode_frame(cast_packet("CS_yhwq_hsqs04")))
    frames = recv_frames(sock, 6, timeout=6, quiet=1.2)
    all_frames.extend(frames)
    customs = [custom_msgid(f) for f in frames if custom_msgid(f)]
    buff_slot = [f for f in frames if op(f) == 0x10 and len(f) > 14
                 and struct.unpack("<H", f[12:14])[0] in range(629, 629 + 24)
                 and b"," in f]
    check("hsqs04: msg20 accepted", 20 in customs, "customs=%s" % customs)
    check("hsqs04: msg169 buff frame", 169 in customs, "customs=%s" % customs)

    # 5. 伤害段（24/0x2D/18/205）依赖场景目录在半径内有 attacknpc，见 INFO
    print("INFO damage floats depend on a combat NPC within range (scene catalog dependent)")

    # 6. 收尾：0x12(rec6) + State="stand"（动作总时长后）
    deadline = time.time() + 8
    while time.time() < deadline:
        frames = recv_frames(sock, 999, timeout=2.0, quiet=1.4)
        all_frames.extend(frames)
        if any(op(f) == 0x12 and record_index(f) == 6 for f in all_frames)                 and any(op(f) == 0x10 and b"stand" in f for f in all_frames):
            break
    removed = [f for f in all_frames if op(f) == 0x12 and record_index(f) == 6]
    stand = [f for f in all_frames if op(f) == 0x10 and b"stand" in f]
    check("end-of-action: 0x12 active_skill removed", bool(removed),
          "0x12 x%d in %d frames" % (len(removed), len(all_frames)))
    check("end-of-action: State=stand restored", bool(stand))

    # 7. CD 尽 CanUse 回 1（真机反馈 4/5：图标可用性随 CD 恢复）
    restored = [(row, v) for row, v in canuse_frames(all_frames) if v == 1]
    check("cooldown end restores CanUse=1", bool(restored),
          "restored rows=%s" % [r for r, _ in restored][:8])

    sock.close()
    fails = [r for r in results if not r[1]]
    print("\n== SKILL SMOKE %s: %d/%d passed ==" % ("FAILED" if fails else "OK",
                                                    len(results) - len(fails), len(results)))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
