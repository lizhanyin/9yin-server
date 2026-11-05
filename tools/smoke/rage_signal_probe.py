# -*- coding: utf-8 -*-
"""57378 非bak捕获·怒招可用信号专项：
问题：怒招有两个限制（CD + 怒气SP）。服务端有没有下发"怒招可用"帧（非CD语义）？
方法：
 (1) 玩家对象 0x10 全行解析 → SP/MaxSP/MP/QGP/LogicState 全程演进（不限帧头100B）
 (2) 全帧裸扫 u16=SP下标，捕捉任何形态的 SP 下发（0x10/0x0D/0x2D/0x0F...）
 (3) CanUse(2236) 全部翻转点 + 上下文（±30帧内 c2s 出招/冲锋/轻功、0x13/0x2D、资源变化）
 (4) s2c 0x1E/0x27 custom msgId 直方图 + 战斗窗口内的 custom 序列
用法: python -I rage_signal_probe.py <captures_dir>
"""
import importlib.util
import os
import struct
import sys
from collections import Counter

_SPEC = importlib.util.spec_from_file_location(
    'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
_CD = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_CD)


def read_prop(fr, off, ptype):
    if ptype == 1:
        return fr[off], off + 1
    if ptype == 2:
        return struct.unpack_from('<H', fr, off)[0], off + 2
    if ptype == 3:
        return struct.unpack_from('<i', fr, off)[0], off + 4
    if ptype == 4:
        return struct.unpack_from('<q', fr, off)[0], off + 8
    if ptype == 5:
        return round(struct.unpack_from('<f', fr, off)[0], 3), off + 4
    if ptype == 6:
        return round(struct.unpack_from('<d', fr, off)[0], 3), off + 8
    if ptype == 9:
        oid, owner = struct.unpack_from('<II', fr, off)
        return 'obj(%#x,%#x)' % (oid, owner), off + 8
    if ptype == 7:
        n = struct.unpack_from('<I', fr, off)[0]
        off += 4
        return fr[off:off + n].rstrip(b'\x00').decode('gbk', 'replace'), off + n
    if ptype == 8:
        n = struct.unpack_from('<I', fr, off)[0]
        off += 4
        return fr[off:off + n].decode('utf-16-le', 'replace'), off + n
    return None, off


def parse_props(fr, entries, count, prop_off):
    """0x10 body: u16 pc + (u16 idx + val)* —— 逐个读满，返回 dict（尽力而为）"""
    row = {}
    off = prop_off
    if off + 2 > len(fr):
        return row
    pc = struct.unpack_from('<H', fr, off)[0]
    off += 2
    for _ in range(pc):
        if off + 2 > len(fr):
            break
        idx = struct.unpack_from('<H', fr, off)[0]
        off += 2
        if idx >= count:
            break
        name, ptype = entries[idx]
        try:
            val, off = read_prop(fr, off, ptype)
        except Exception:
            break
        row[name] = val
    return row


def main():
    base = sys.argv[1]
    s2c = _CD.load(os.path.join(base, '127-0-0-1-57378_s2c.bin'))
    c2s = _CD.load(os.path.join(base, '127-0-0-1-57378_c2s.bin'))
    table = next(f for f in s2c if f and f[0] == 0x09)
    count, entries, _ = _CD.parse_09(table)
    idx_of = {}
    for i, (n, _t) in enumerate(entries):
        idx_of.setdefault(n, i)

    for nm in ('SP', 'MaxSP', 'MP', 'MaxMP', 'QingGongPoint', 'LogicState', 'CanUse', 'CurSkillID'):
        print('%-14s idx=%s' % (nm, idx_of.get(nm)))

    # 玩家对象
    obj_hist = Counter()
    for fr in s2c:
        if fr and fr[0] == 0x10 and fr[1] == 0 and len(fr) >= 12:
            obj_hist[struct.unpack_from('<I', fr, 2)[0]] += 1
    player = obj_hist.most_common(1)[0][0]
    print('玩家对象=%#x  0x10(isView=0)帧=%d' % (player, obj_hist[player]))

    watch = ('SP', 'MaxSP', 'MP', 'MaxMP', 'QingGongPoint', 'LogicState', 'CurSkillID')
    res_events = []   # (帧号, {name:val} 变化集)
    state = {}
    canuse = []       # (帧号, 行, 值)
    for n, fr in enumerate(s2c):
        if not fr or fr[0] != 0x10 or len(fr) < 12:
            continue
        isview = fr[1]
        obj = struct.unpack_from('<I', fr, 2)[0]
        owner = struct.unpack_from('<I', fr, 6)[0]
        props = parse_props(fr, entries, count, 10)
        if isview == 1 and obj == 40 and 'CanUse' in props:
            canuse.append((n, owner, props['CanUse']))
            continue
        if isview == 0 and obj == player:
            diff = {}
            for k in watch:
                if k in props and state.get(k) != props[k]:
                    diff[k] = props[k]
                    state[k] = props[k]
            if diff:
                res_events.append((n, diff))

    print('\n== 玩家资源/状态演进（全行解析，共 %d 次变化）==' % len(res_events))
    for n, d in res_events[:80]:
        print('   #%d %s' % (n, d))

    # SP 下标裸扫（任何帧形态）
    sp_idx = idx_of.get('SP')
    maxsp_idx = idx_of.get('MaxSP')
    print('\n== u16=SP(%d)/MaxSP(%s) 下标裸扫（全帧全偏移，前后文4B）==' % (sp_idx, maxsp_idx))
    hits = 0
    for n, fr in enumerate(s2c):
        if not fr or len(fr) < 8:
            continue
        for off in range(0, len(fr) - 6):
            v = struct.unpack_from('<H', fr, off)[0]
            if v == sp_idx:
                tail = struct.unpack_from('<i', fr, off + 2)[0]
                print('   #%d op=0x%02X len=%d off=%d SP=%d 帧尾余=%d' % (
                    n, fr[0], len(fr), off, tail, len(fr) - off - 6))
                hits += 1
                break
        if hits >= 25:
            break

    # CanUse 翻转点
    print('\n== CanUse 翻转点（值变化的行）==')
    flips = []
    last = {}
    for n, r, v in canuse:
        if last.get(r) is not None and last[r] != v:
            flips.append((n, r, last[r], v))
        last[r] = v
    print('翻转总数=%d' % len(flips))
    for n, r, a, b in flips:
        print('   #%d 行%d: %d→%d' % (n, r, a, b))

    # c2s 事件流
    print('\n== c2s 0x0A custom 事件（211/15/212/216/54 等）==')
    c2s_ev = []
    for idx, seq, mid, vals in _CD.c2s_customs(c2s):
        if mid in (211, 15, 212, 216, 54, 1006):
            c2s_ev.append((idx, mid, vals[:4]))
    print('总数=%d' % len(c2s_ev))
    for idx, mid, vals in c2s_ev[:60]:
        print('   c2s#%d msg%d %s' % (idx, mid, vals))

    # s2c custom 直方图
    print('\n== s2c 0x1E/0x27 custom msgId 直方图 ==')
    hist = Counter()
    for _idx, _op, mid, _vals in _CD.s2c_customs(s2c):
        hist[mid] += 1
    for mid, c in sorted(hist.items(), key=lambda kv: -kv[1]):
        print('   msg%-6s x%d' % (mid, c))


if __name__ == '__main__':
    main()
