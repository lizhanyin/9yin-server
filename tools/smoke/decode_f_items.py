# -*- coding: utf-8 -*-
"""解码 F 盘参考服 probe 日志里 grant bag item=... frame=<hex> 的 0x18 物品帧。

帧布局（对齐 Java InventoryPackets.viewAddHeader）：
  [0x18][view:u16 LE][slot:u16 LE][propCount:u16 LE] + 记录流
记录 = [key:u16 LE][value]，value 宽度由该 key 在属性表中的类型决定：
  1 字节 / 2 字节 / 4 字节 int / u32 长度前缀字符串(含结尾 NUL)
宽度未知时用回溯推断（帧尾对齐 + propCount 校验）。
"""
import re
import sys
from collections import Counter, defaultdict

LOG = sys.argv[1] if len(sys.argv) > 1 else \
    r"F:/Game/Online/9yin/9yin_server1/logs/protocol-probe-live.log"

# key -> 值宽度（字节数）或 'str'。锚定依据：手工对齐 + "下一 key 必须已知"约束，
# 0x07xx 段在 F 新表(2370)普遍比 Java 表(2424) 同名字段偏移 +2~+3。
WIDTH = {
    0x00b5: 1,               # 物件类型标记，恒 8（=Item）
    0x05a0: 'str',           # ConfigID（物品名）
    0x0761: 4,               # ItemType 细分（武器 101~129、宝箱 2102、融合石 2065）
    0x0762: 4,               # 物品分类（box=6）
    0x0763: 4,
    0x0764: 4,               # 品质/颜色档（1~6）
    0x0765: 'str',           # UniqueID "7100568-xxx-xxxxxxxxxx-xxxx"
    0x0766: 4,               # Amount（可堆叠 220/271）
    0x0767: 4,
    0x076a: 4,
    0x076d: 4,
    0x0779: 4,
    0x077a: 4,               # 武器价值 17001 等
    0x0786: 4,
    0x0787: 4,
    0x0788: 4,
    0x078f: 4,               # 耐久 100
    0x0790: 4,               # 最大耐久 100
    0x0791: 1,               # ColorLevel u8（=2）
    0x0792: 'str',           # EquipType（"Weapon"/"FashionHat"/"ClothesH"）
    0x079d: 4,               # 1
    0x079e: 4,
    0x07a0: 4,               # EffectPackID 2506
    0x07a1: 4,
    0x07a3: 4,
    0x07af: 4,               # 10001/10032/0
    0x07b1: 4,               # 4000
    0x07b2: 1,
    0x01fd: 4,               # 127
    0x01fe: 4,               # 64
    0x05b9: 1,               # Level u8（剑 111、甲 21/61）
    0x0248: 4,               # 护甲扩展（0）
    0x07da: 4,               # 103
    0x07e4: 4,               # 书籍 config（5172/5293）
    0x0820: 4,               # 书籍层数（7~12）
}
STR_KEYS = {k for k, w in WIDTH.items() if w == 'str'}


def parse_records(data, start, count):
    """按 WIDTH 表线性解析；返回记录列表，遇到未知 key 或长度不匹配返回 None。"""
    out = []
    pos = start
    for _ in range(count):
        if pos + 2 > len(data):
            return None
        key = int.from_bytes(data[pos:pos + 2], 'little')
        if key not in WIDTH:
            return None
        w = WIDTH[key]
        if w == 'str':
            if pos + 6 > len(data):
                return None
            ln = int.from_bytes(data[pos + 2:pos + 6], 'little')
            end = pos + 6 + ln
            if not (0 < ln <= 512 and end <= len(data) and data[end - 1] == 0):
                return None
            out.append((key, 'str', data[pos + 6:end - 1]
                        .decode('utf-8', 'replace')))
            pos = end
        else:
            end = pos + 2 + w
            if end > len(data):
                return None
            out.append((key, f'u{w * 8}',
                        int.from_bytes(data[pos + 2:end], 'little')))
            pos = end
    return out if pos == len(data) else None


def main():
    pat = re.compile(r'grant bag item=(\S+) view=(\d+) slot=(\d+) frame=([0-9a-f]+)')
    items = []
    for line in open(LOG, encoding='utf-8', errors='replace'):
        m = pat.search(line)
        if m:
            items.append((m.group(1), int(m.group(2)), int(m.group(3)),
                          bytes.fromhex(m.group(4))))
    print(f'共 {len(items)} 个物品帧')
    width_fix = {}
    fails = 0
    all_rows = []
    for name, view, slot, frame in items:
        count = int.from_bytes(frame[5:7], 'little')
        recs = parse_records(frame, 7, count)
        if recs is None:
            # 兼容首记录前无额外字节的情况
            fails += 1
            print(f'  FAIL {name} view={view} slot={slot} count={count}')
            continue
        for key, typ, val in recs:
            width_fix.setdefault(key, typ)
        all_rows.append((name, view, slot, count, recs))
    print(f'解析失败: {fails}')
    print()

    # key -> 各类型值样本
    bykey = defaultdict(lambda: defaultdict(list))
    for name, view, slot, count, recs in all_rows:
        for key, typ, val in recs:
            bykey[key][typ].append((name, view, val))

    print('== key 总表（按出现次数排序）==')
    def kind_of(name):
        if name.startswith(('sword_', 'blade_', 'sblade_', 'axe_', 'thorn_', 'lstuff_')):
            return 'weapon'
        if name.startswith(('HELMET_', 'cloth_')):
            return 'armor'
        if name.startswith(('box_', 'book_')):
            return 'box/book'
        return 'misc'
    order = sorted(bykey, key=lambda k: -sum(len(v) for v in bykey[k].values()))
    for key in order:
        typs = bykey[key]
        main_typ = max(typs, key=lambda t: len(typs[t]))
        samples = typs[main_typ]
        vals = Counter(v for _, _, v in samples if not isinstance(v, str))
        kinds = Counter(kind_of(n) for n, _, _ in samples)
        preview = ''
        if main_typ == 'str':
            preview = ' | '.join(repr(v) for v in
                                 [s for _, _, s in samples[:2]][:2])
        else:
            preview = ','.join(str(v) for v, _ in vals.most_common(6))
        print(f'  key=0x{key:04x}({key}) {main_typ:<4} x{len(samples):<4} '
              f'类目{dict(kinds)} 值[{preview[:80]}]')

    print()
    print('== propCount / 每类物品属性数 ==')
    stat = defaultdict(Counter)
    for name, view, slot, count, recs in all_rows:
        stat[kind_of(name)][count] += 1
    for k, c in stat.items():
        print(f'  {k:<8} {dict(c)}')

    print()
    print('== view/slot 布局 ==')
    vs = defaultdict(list)
    for name, view, slot, count, recs in all_rows:
        vs[view].append(slot)
    for v in sorted(vs):
        ss = sorted(vs[v])
        print(f'  view={v:<4} 物品数={len(ss):<4} slot范围={ss[0]}~{ss[-1]} '
              f'完整={ss == list(range(ss[0], ss[-1] + 1))}')

    print()
    print('== 单帧示例（每 view 一个）==')
    seen = set()
    for name, view, slot, count, recs in all_rows:
        if view in seen:
            continue
        seen.add(view)
        print(f'  [{name}] view={view} slot={slot} count={count}')
        for key, typ, val in recs:
            print(f'    0x{key:04x} {typ:<4} {val!r}')
        if len(seen) >= 4:
            break


if __name__ == '__main__':
    main()
