# -*- coding: utf-8 -*-
"""两服战斗流量对拍工具（参考服 9yin_server1 vs Java 9yin_server_new captures/local）。

用法(建议 -I 隔离运行):
  python -I combat_compare.py census  <stem>...                 # 会话战斗普查(帧型/消息号/记录行全量)
  python -I combat_compare.py casts   <stem> [limit]            # 逐出招 S2C 窗口(0x11 rec446 分段)
  python -I combat_compare.py c2s     <stem>                    # C2S 战斗上行(211/431/54/240/15)
  python -I combat_compare.py diffseq <ref_stem> <java_stem>    # 逐出招帧序模板对比
  python -I combat_compare.py detail  <stem> <frame_idx_range>  # 帧区间明细

帧布局(两服一致, 已对字节核实):
  0x09 属性表: u16 count + (name\\0 u8 type)*
  0x0A(S2C) 记录表: u16 count + (name\\0 u16 ncols + u8 coltype*)*
  0x10 属性帧: u8 isView | u32 obj@2 | u32 owner@6 | u16 count@10 | (u16 idx + 值)*
               isView=1 时 obj=view号 owner=行号(View40 CanUse 行帧)
  0x11 记录加行: u32 obj@2 | u32 owner@6 | u16 rec@10 | u16 srcRow@12 | u16 rowCount@14 | payload@16
  0x12 记录删行: u32 obj@2 | u32 owner@6 | u16 rec@10 | u16 row@12
  0x13 记录格子: u32 obj@2 | u32 owner@6 | u16 rec@10 | u16 row@12 | u16 col@14 | u8 0@16 | u32 val@17
  0x2D 受击特效: 25B 固定布局(§combat-skill-211.md)
"""
import importlib.util
import os
import struct
import sys
from collections import Counter, OrderedDict

_SPEC = importlib.util.spec_from_file_location(
    'capture_deep', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture_deep.py'))
_CD = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_CD)

TSIZE = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 0, 7: 0, 8: 0, 9: 8}


class Session(object):
    def __init__(self, stem):
        self.stem = stem
        self.tag = os.path.basename(stem)
        self.c2s = _CD.load(stem + '_c2s.bin')
        self.s2c = _CD.load(stem + '_s2c.bin')
        self.props = self._prop_table()      # idx -> (name, type)
        self.pidx = {n: i for i, (n, t) in self.props.items()}
        self.recs = self._record_table()     # recid -> (name, [coltypes])
        self.player_obj = None
        self._find_player()

    def _prop_table(self):
        for fr in self.s2c:
            if fr and fr[0] == 0x09 and len(fr) > 3:
                cnt, entries, _ = _CD.parse_09(fr)
                return dict(enumerate(entries))
        return {}

    def _record_table(self):
        for fr in self.s2c:
            if fr and fr[0] == 0x0A and len(fr) > 4:
                count = struct.unpack_from('<H', fr, 1)[0]
                out, off = {}, 3
                for i in range(count):
                    end = fr.index(b'\x00', off)
                    name = fr[off:end].decode('latin-1')
                    off = end + 1
                    ncols = struct.unpack_from('<H', fr, off)[0]
                    off += 2
                    cols = list(fr[off:off + ncols])
                    off += ncols
                    out[i] = (name, cols)
                return out
        return {}

    def _find_player(self):
        # 冷却行(0x11 rec=109)写在施法者对象上 → 众数即玩家(GCD cat=50000 行更稳)
        c = Counter()
        for fr in self.s2c:
            if fr and fr[0] == 0x11 and len(fr) >= 20:
                rec = struct.unpack_from('<H', fr, 10)[0]
                if rec == 109 and len(fr) >= 20:
                    cat = struct.unpack_from('<i', fr, 16)[0]
                    if cat == 50000:
                        c[struct.unpack_from('<I', fr, 2)[0]] += 1
        if c:
            self.player_obj = c.most_common(1)[0][0]

    # ---------- 值解析 ----------

    def read_value(self, fr, off, ptype, name=''):
        try:
            if ptype == 1:
                return fr[off], off + 1
            if ptype == 2:
                return struct.unpack_from('<h', fr, off)[0], off + 2
            if ptype == 3:
                return struct.unpack_from('<i', fr, off)[0], off + 4
            if ptype == 4:
                i = struct.unpack_from('<q', fr, off)[0]
                d = struct.unpack_from('<d', fr, off)[0]
                # 大数值(BufferInfo FILETIME)取 i64, 小数值可能是 double
                return (d if abs(i) < 10 ** 12 and (name or '').find('Time') < 0 else i), off + 8
            if ptype == 5:
                return round(struct.unpack_from('<f', fr, off)[0], 3), off + 4
            if ptype in (6, 7):
                n = struct.unpack_from('<I', fr, off)[0]
                off += 4
                return fr[off:off + n].rstrip(b'\x00').decode('gbk', 'replace'), off + n
            if ptype == 8:  # 宽串
                n = struct.unpack_from('<I', fr, off)[0]
                off += 4
                return fr[off:off + n].decode('utf-16-le', 'replace').rstrip('\x00'), off + n
            if ptype == 9:  # obj 对
                oid, owner = struct.unpack_from('<II', fr, off)
                return 'obj(%x,%x)' % (oid, owner), off + 8
        except (struct.error, UnicodeDecodeError):
            pass
        return None, off

    def parse_10(self, fr):
        """0x10 → (isView, obj, owner, OrderedDict[name→value])"""
        if not fr or fr[0] != 0x10 or len(fr) < 12:
            return None
        isv = fr[1]
        obj, owner = struct.unpack_from('<II', fr, 2)
        cnt = struct.unpack_from('<H', fr, 10)[0]
        off, out = 12, OrderedDict()
        for _ in range(cnt):
            if off + 2 > len(fr):
                break
            idx = struct.unpack_from('<H', fr, off)[0]
            off += 2
            name, ptype = self.props.get(idx, ('idx%d' % idx, 0))
            if ptype not in TSIZE:
                break
            v, off = self.read_value(fr, off, ptype, name)
            out[name] = v
        return isv, obj, owner, out

    def parse_18(self, fr):
        if not fr or fr[0] != 0x18 or len(fr) < 7:
            return None
        view, oidx, cnt = struct.unpack_from('<HHH', fr, 1)
        off, out = 7, OrderedDict()
        for _ in range(cnt):
            if off + 2 > len(fr):
                break
            idx = struct.unpack_from('<H', fr, off)[0]
            off += 2
            name, ptype = self.props.get(idx, ('idx%d' % idx, 0))
            if ptype not in TSIZE:
                break
            v, off = self.read_value(fr, off, ptype, name)
            out[name] = v
        return view, oidx, out

    def parse_11(self, fr):
        if not fr or fr[0] != 0x11 or len(fr) < 16:
            return None
        obj, owner = struct.unpack_from('<II', fr, 2)
        rec, src, nrow = struct.unpack_from('<HHH', fr, 10)
        name, cols = self.recs.get(rec, ('rec%d' % rec, []))
        payload, off = OrderedDict(), 16
        for i, ct in enumerate(cols):
            if off >= len(fr):
                break
            v, off = self.read_value(fr, off, ct, name)
            payload['c%d' % i] = v
        return obj, owner, rec, name, payload, fr[16:]

    def parse_13(self, fr):
        if not fr or fr[0] != 0x13 or len(fr) < 21:
            return None
        obj, owner = struct.unpack_from('<II', fr, 2)
        rec, row, col = struct.unpack_from('<HHH', fr, 10)
        val = struct.unpack_from('<I', fr, 17)[0]
        name, _ = self.recs.get(rec, ('rec%d' % rec, []))
        return obj, owner, rec, name, row, col, val

    def parse_2d(self, fr):
        if not fr or fr[0] != 0x2D or len(fr) < 25:
            return None
        obj, owner = struct.unpack_from('<II', fr, 3)
        a, b = struct.unpack_from('<HH', fr, 11)
        hp1 = struct.unpack_from('<I', fr, 15)[0]
        c = struct.unpack_from('<H', fr, 19)[0]
        hp2 = struct.unpack_from('<I', fr, 21)[0]
        return dict(obj=obj, owner=owner, a=a, b=b, hp1=hp1, c=c, hp2=hp2)

    # ---------- 战斗事件流 ----------

    def s2c_events(self):
        """yield (idx, kind, dict) — 战斗相关 S2C 事件。"""
        for i, fr in enumerate(self.s2c):
            if not fr:
                continue
            op = fr[0]
            if op == 0x10:
                r = self.parse_10(fr)
                if not r:
                    continue
                isv, obj, owner, props = r
                if isv == 1:
                    yield i, 'vrow', dict(view=obj, row=owner, props=props, size=len(fr))
                else:
                    yield i, 'panel', dict(obj=obj, owner=owner, props=props, size=len(fr))
            elif op == 0x11:
                r = self.parse_11(fr)
                if not r:
                    continue
                obj, owner, rec, name, payload, raw = r
                yield i, 'rec_add', dict(obj=obj, rec=rec, name=name, payload=payload,
                                         raw=raw.hex(), size=len(fr))
            elif op == 0x12:
                obj, owner = struct.unpack_from('<II', fr, 2)
                rec, row = struct.unpack_from('<HH', fr, 10)
                name, _ = self.recs.get(rec, ('rec%d' % rec, []))
                yield i, 'rec_del', dict(obj=obj, rec=rec, name=name, row=row, size=len(fr))
            elif op == 0x13:
                r = self.parse_13(fr)
                if r:
                    obj, owner, rec, name, row, col, val = r
                    yield i, 'rec_grid', dict(obj=obj, rec=rec, name=name, row=row, col=col, val=val)
            elif op == 0x2D:
                yield i, 'hit2D', self.parse_2d(fr)
            elif op in (0x1E, 0x27):
                vals = _CD.parse_tvarlist(fr[1:])
                if vals and isinstance(vals[0], int):
                    yield i, 'custom', dict(op=op, mid=vals[0], vals=vals[1:], size=len(fr))


# ---------- 输出 ----------

def brief(v, n=40):
    s = str(v)
    return s if len(s) <= n else s[:n] + '~'


def cmd_census(stems):
    for stem in stems:
        s = Session(stem)
        print('=' * 100)
        print('会话 %s  帧数 c2s=%d s2c=%d  玩家obj=%s' % (s.tag, len(s.c2s), len(s.s2c),
              ('%#x' % s.player_obj) if s.player_obj else '?'))
        op_c2s, op_s2c = Counter(), Counter()
        for fr in s.c2s:
            op_c2s[fr[0] if fr else -1] += 1
        for fr in s.s2c:
            op_s2c[fr[0] if fr else -1] += 1
        combat_ops = [0x10, 0x11, 0x12, 0x13, 0x2D, 0x1E, 0x27, 0x18, 0x1F, 0x20, 0x21]
        print('S2C 战斗相关帧型: ' + '  '.join('0x%02X:x%d' % (o, op_s2c[o]) for o in combat_ops if op_s2c[o]))
        cmid = Counter()
        for _, k, d in s.s2c_events():
            if k == 'custom':
                cmid[(d['op'], d['mid'])] += 1
        print('S2C custom 消息号: ' + '  '.join('op%02X/%d:x%d' % (o, m, c)
              for (o, m), c in sorted(cmid.items()) if c >= 2))
        # 记录行统计
        rec_add, rec_del, rec_grid = Counter(), Counter(), Counter()
        for _, k, d in s.s2c_events():
            if k == 'rec_add':
                rec_add[d['name']] += 1
            elif k == 'rec_del':
                rec_del[d['name']] += 1
            elif k == 'rec_grid':
                rec_grid[d['name']] += 1
        print('记录加行: ' + '  '.join('%s:+%d' % kv for kv in rec_add.most_common()))
        print('记录删行: ' + ('  '.join('%s:-%d' % kv for kv in rec_del.most_common()) or '无'))
        print('记录格子: ' + ('  '.join('%s:%d' % kv for kv in rec_grid.most_common()) or '无'))
        # C2S
        cm = Counter()
        for _, _, m, _ in _CD.c2s_customs(s.c2s):
            cm[m] += 1
        c2s_ops = '  '.join('%d:x%d' % (m, c) for m, c in sorted(cm.items()) if m in
                            (211, 431, 54, 240, 99, 15, 216))
        print('C2S 战斗上行 custom: ' + (c2s_ops or '无'))
        sk = Counter()
        for _, _, m, v in _CD.c2s_customs(s.c2s):
            if m == 211 and len(v) > 1:
                sk[v[1]] += 1
        if sk:
            print('C2S 211 出招技能: ' + '  '.join('%s:x%d' % kv for kv in sk.most_common()))


def cmd_casts(stem, limit=6):
    s = Session(stem)
    evs = list(s.s2c_events())
    # 窗口 = 0x11 rec446(active_skill) 加行 → 下一个加行(或文件尾)
    starts = [i for i, (idx, k, d) in enumerate(evs)
              if k == 'rec_add' and d['name'] == 'active_skill_rec']
    print('出招窗口数=%d  (active_skill_rec 加行)' % len(starts))
    shown = 0
    for wi in range(len(starts)):
        if shown >= limit:
            break
        a = starts[wi]
        b = starts[wi + 1] if wi + 1 < len(starts) else len(evs)
        skill = evs[a][2]['payload'].get('c0')
        print('-' * 90)
        print('窗口#%d skill=%s' % (wi, skill))
        for idx, k, d in evs[a:b]:
            if k == 'rec_add':
                print('  #%d +%s %s' % (idx, d['name'], {k2: brief(v) for k2, v in d['payload'].items()}))
            elif k == 'rec_del':
                print('  #%d -%s row=%s' % (idx, d['name'], d['row']))
            elif k == 'rec_grid':
                print('  #%d grid %s obj=%#x row=%s col=%s val=%s' % (idx, d['name'], d['obj'], d['row'], d['col'], d['val']))
            elif k == 'custom':
                vs = [brief(v, 30) for v in d['vals']]
                print('  #%d 0x%02X msg%-4d %s' % (idx, d['op'], d['mid'], vs))
            elif k == 'hit2D':
                print('  #%d 0x2D %s' % (idx, d))
            elif k == 'vrow':
                keys = {k2: v for k2, v in d['props'].items() if k2 in ('CanUse', 'ConfigID', 'CoolDownTime')}
                if keys:
                    print('  #%d View%d 行%s %s' % (idx, d['view'], d['row'], keys))
            elif k == 'panel':
                interesting = {}
                for nm in ('LogicState', 'HP', 'MaxHP', 'HPRatio', 'SP', 'MaxSP', 'State',
                           'CurSkillID', 'CurSkillTarget', 'Dead', 'SpeedRatio'):
                    if nm in d['props']:
                        interesting[nm] = d['props'][nm]
                bi = {k2: brief(v, 24) for k2, v in d['props'].items() if k2.startswith('BufferInfo')
                      and v not in (0, '', None, 'obj(0,0)')}
                tag = '玩家' if d['obj'] == s.player_obj else 'obj%#x' % d['obj']
                print('  #%d panel[%s] %dB n=%d %s %s' % (idx, tag, d['size'], len(d['props']),
                      interesting, ('buff:' + str(bi)) if bi else ''))
        shown += 1


def cmd_c2s(stem):
    s = Session(stem)
    print('== C2S %s (玩家obj=%s)' % (s.tag, ('%#x' % s.player_obj) if s.player_obj else '?'))
    for idx, seq, m, v in _CD.c2s_customs(s.c2s):
        if m in (211, 431, 54, 240, 216, 99):
            vs = [brief(x, 36) for x in v[1:]]
            print('  c2s#%-5d seq=%-6d msg%-4d %s' % (idx, seq, m, vs))
    # 0x07 目标请求
    n7 = sum(1 for fr in s.c2s if fr and fr[0] == 0x07)
    n15 = sum(1 for fr in s.c2s if fr and fr[0] == 0x15)
    print('  0x07 对象请求 x%d, 0x15 x%d' % (n7, n15))


def cast_template(s):
    """聚合所有出招窗口的帧序模板 → [(seq_of_kinds, count)]"""
    evs = list(s.s2c_events())
    starts = [i for i, (idx, k, d) in enumerate(evs)
              if k == 'rec_add' and d['name'] == 'active_skill_rec']
    tmpl = Counter()
    for wi, a in enumerate(starts):
        b = starts[wi + 1] if wi + 1 < len(starts) else len(evs)
        seq = []
        for idx, k, d in evs[a:b]:
            if k == 'rec_add':
                nm = d['name']
                if nm == 'cooldown_rec':
                    cat = d['payload'].get('c0')
                    seq.append('cd(personal)' if cat != 50000 else 'cd(GCD)')
                else:
                    seq.append('+%s' % nm)
            elif k == 'rec_del':
                seq.append('-%s' % d['name'])
            elif k == 'rec_grid':
                seq.append('grid(%s)' % d['name'])
            elif k == 'custom':
                seq.append('m%s' % d['mid'])
            elif k == 'hit2D':
                seq.append('2D')
            elif k == 'panel':
                if d['obj'] == s.player_obj:
                    seq.append('panelP(%d)' % len(d['props']))
                else:
                    seq.append('panelN')
            elif k == 'vrow':
                seq.append('vrow')
        tmpl[' > '.join(seq)] += 1
    return tmpl


def cmd_diffseq(ref_stem, java_stem):
    for tag, stem in (('参考服', ref_stem), ('Java服', java_stem)):
        s = Session(stem)
        print('=' * 100)
        print('%s %s 出招帧序模板(共 %d 窗口):' % (tag, s.tag, len([1 for _, k, d in s.s2c_events()
              if k == 'rec_add' and d['name'] == 'active_skill_rec'])))
        for t, c in cast_template(s).most_common():
            print('  x%-3d %s' % (c, t))


def cmd_detail(stem, lo, hi):
    s = Session(stem)
    for i, (idx, k, d) in enumerate(s.s2c_events()):
        if lo <= idx <= hi:
            if k == 'custom':
                print('  #%d 0x%02X msg%-4d %s' % (idx, d['op'], d['mid'],
                      [brief(v, 44) for v in d['vals']]))
            elif k == 'rec_add':
                print('  #%d +%s %s' % (idx, d['name'], {k2: brief(v) for k2, v in d['payload'].items()}))
            elif k == 'rec_del':
                print('  #%d -%s row=%s' % (idx, d['name'], d['row']))
            elif k == 'rec_grid':
                print('  #%d grid %s obj=%#x row=%s col=%s val=%s' % (idx, d['name'], d['obj'], d['row'], d['col'], d['val']))
            elif k == 'hit2D':
                print('  #%d 0x2D %s' % (idx, d))
            elif k == 'panel':
                tag = '玩家' if d['obj'] == s.player_obj else 'obj%#x' % d['obj']
                print('  #%d panel[%s] %s' % (idx, tag, {k2: brief(v, 26) for k2, v in d['props'].items()}))
            elif k == 'vrow':
                print('  #%d View%d 行%s %s' % (idx, d['view'], d['row'], dict(d['props'])))


def main():
    cmd = sys.argv[1]
    if cmd == 'census':
        cmd_census(sys.argv[2:])
    elif cmd == 'casts':
        cmd_casts(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 6)
    elif cmd == 'c2s':
        cmd_c2s(sys.argv[2])
    elif cmd == 'diffseq':
        cmd_diffseq(sys.argv[2], sys.argv[3])
    elif cmd == 'detail':
        cmd_detail(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
    else:
        print(__doc__)


if __name__ == '__main__':
    main()
