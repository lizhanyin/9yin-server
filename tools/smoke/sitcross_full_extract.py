# -*- coding: utf-8 -*-
"""52905/12141 打坐交互完整提取: c2s 240 + s2c 玩家 0x10 关键属性差分时间线。"""
import importlib.util, os, struct, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sitcross_local_dump as SD

WATCH = ('LogicState', 'HP', 'MP', 'MaxHP', 'MaxMP', 'HPUpSpeed', 'MPUpSpeed',
         'HPHeartSpeed', 'State', 'SP', 'CurSkillID')

def run(tag, c2s_path, s2c_path):
    c2s = SD._CD.load(c2s_path)
    s2c = SD._CD.load(s2c_path)
    table = next(f for f in s2c if f and f[0] == 0x09)
    count, entries, _ = SD._CD.parse_09(table)
    names = [n for n, _ in entries]
    types = [t for _, t in entries]
    from collections import Counter
    objs = Counter()
    for fr in s2c:
        if fr and fr[0] == 0x10 and len(fr) >= 12 and fr[1] == 0:
            objs[struct.unpack_from('<I', fr, 2)[0]] += 1
    player_obj = objs.most_common(1)[0][0]
    print('### %s  player=0x%x c2s_frames=%d s2c_frames=%d' % (tag, player_obj, len(c2s), len(s2c)))
    print('-- C2S 240 全部 --')
    for idx, fr in enumerate(c2s):
        if not fr or fr[0] != 0x0A or len(fr) < 24:
            continue
        seq = struct.unpack_from('<I', fr, 17)[0]
        vals = SD._CD.parse_tvarlist(fr[21:])
        if vals and isinstance(vals[0], int) and vals[0] == 240:
            print('  c2s#%d seq=%d 240 vals=%s' % (idx, seq, vals[1:]))
    print('-- S2C 玩家 0x10 关键差分（首帧后）--')
    prev = {}
    for n, fr in enumerate(s2c):
        if not fr or fr[0] != 0x10 or len(fr) < 12 or fr[1] != 0:
            continue
        if struct.unpack_from('<I', fr, 2)[0] != player_obj:
            continue
        cnt = struct.unpack_from('<H', fr, 10)[0]
        props = SD.parse_profile(fr, 12, names, types, cnt)
        if props is None:
            continue
        if prev:
            diff = {k: v for k, v in props.items() if k in WATCH and prev.get(k) != v}
            if diff:
                print('  s2c#%-5d props=%-3d %s' % (n, cnt, diff))
        prev.update(props)

base = r'F:\Game\Online\9yin\9yin_server1\captures\local\bak'
run('52905', os.path.join(base, '127-0-0-1-52905_c2s.bin'), os.path.join(base, '127-0-0-1-52905_s2c.bin'))
print()
run('12141', os.path.join(base, '127-0-0-1-12141_c2s.bin'), os.path.join(base, '127-0-0-1-12141_s2c.bin'))
