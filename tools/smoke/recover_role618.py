# -*- coding: utf-8 -*-
"""按冒烟 wipe 批次精确恢复 role 618 背包行（recover_role618 第二阶段）。

用法: python -I recover_role618.py [binlog 文件]
背景：verify_move_item.py 冒烟开场的整表 DELETE（261103 22:49:50 批，35 行）
就是冒烟前唯一现场；binlog 里更早的 DELETE 镜像是历史会话的陈旧代际。
本脚本：清空 role 618 背包 → 只回插 22:49:50 批次的行镜像。
（装备 7 件已由第一阶段按同法恢复，本脚本顺带幂等重做。）
"""
import datetime
import re
import subprocess
import sys

BINLOG = sys.argv[1] if len(sys.argv) > 1 else r'G:/pojie/ai/mysql-26/data/binlog.000327'
MYSQL = r'G:/pojie/ai/mysql-26/bin/mysql.exe'
WIPE_TAG = '261103 22:49:50'  # 冒烟 wipe 事务时间戳（秒级）


def binlog_dump():
    r = subprocess.run([MYSQL.replace('mysql.exe', 'mysqlbinlog.exe'), '--no-defaults', '-vv',
                        '--base64-output=DECODE-ROWS', BINLOG], capture_output=True)
    return r.stdout.decode('utf-8', 'replace')


def parse_wipe(out):
    """收集 (table, row) —— 时间戳命中 WIPE_TAG 的 DELETE 事件且 @1=618。"""
    wiped = []
    cur_time = None
    block = None  # (table, cols, time)

    def flush():
        if block and block[2] == WIPE_TAG and block[1] and block[1][0].startswith('@1=618'):
            wiped.append((block[0], block[1]))

    for ln in out.splitlines():
        s = ln.strip()
        m = re.match(r'#(\d{6} \d{2}:\d{2}:\d{2}) server id', s)
        if m:
            cur_time = m.group(1)
        if s.startswith('### DELETE FROM '):
            flush()
            block = [s[len('### DELETE FROM '):], [], cur_time]
            continue
        if block is not None:
            if s.startswith('###   @'):
                block[1].append(s[6:])
            elif s.startswith('### WHERE'):
                continue  # 行头一部分，不终结块
            elif s.startswith('###'):
                flush()
                block = None
    flush()
    return wiped


def sqlval(v):
    v = v.split('/*')[0].strip()  # 剥掉 mysqlbinlog 的类型注释尾巴
    if v == 'NULL':
        return 'NULL'
    if v.startswith("'"):
        return v  # binlog 已带引号（内部转义保留原样）
    if '.' in v:  # epoch 浮点 = timestamp(6) 列
        stamp = datetime.datetime.fromtimestamp(float(v), datetime.timezone.utc)
        return "'%s'" % stamp.strftime('%Y-%m-%d %H:%M:%S.%f')
    return v


def run(sql):
    r = subprocess.run([MYSQL, '-unineyin', '-pnineyin123', 'nineyin', '-e', sql],
                       capture_output=True)
    return r.returncode, r.stderr.decode('utf-8', 'replace').strip()


def main():
    wiped = parse_wipe(binlog_dump())
    counts = {}
    for table, _ in wiped:
        counts[table] = counts.get(table, 0) + 1
    print('wipe batch rows:', counts)
    run('DELETE FROM role_equip_items WHERE role_id=618')
    run('DELETE FROM role_bag_items WHERE role_id=618')
    ok = 0
    for table, row in wiped:
        vals = [sqlval(c.partition('=')[2]) for c in row]
        code, err = run('INSERT INTO %s VALUES (%s)' % (table, ','.join(vals)))
        if code:
            print('  ERR', err.replace('Warning', '')[-140:])
        else:
            ok += 1
    print('inserted', ok)


if __name__ == '__main__':
    main()
