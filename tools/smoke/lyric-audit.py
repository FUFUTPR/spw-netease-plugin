#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""歌词层只读审计（W4）——缓存目录统计 + LRC 抽样校验 + 与库内曲目数对照 + 失败分类。

用法：
    python tools/smoke/lyric-audit.py                          # 真机默认：%APPDATA%\\Salt Player for Windows\\spw.db
    python tools/smoke/lyric-audit.py --db <path\\to\\spw.db>
    python tools/smoke/lyric-audit.py --dir <path\\to\\com.example.netease>
    python tools/smoke/lyric-audit.py --sample 20              # 抽样校验 20 个 .lrc（默认 10）
    python tools/smoke/lyric-audit.py --all                    # 校验全部 .lrc（不抽样）
    python tools/smoke/lyric-audit.py --expect-ready N         # 断言就绪数 ≥ N（日志回报的「已就绪」）
    python tools/smoke/lyric-audit.py --expect-audio-files N --expect-audio-bytes B
                                                              # A16：audio-stream\\ 必须「零新增」时钉死前值

判据（任一条不满足 ⇒ 结论 FAIL，exit 1）：
  1. lyric\\ 里的 .lrc 文件名必须是纯数字 <songId>.lrc（无 .tmp 残留、无其它后缀）
  2. 每个被抽到的 .lrc 必须是 UTF-8 可解码、非空、含至少 1 行带时间戳 `[mm:ss.xx]` 的正文
  3. 抽样里出现的「读不到」（空文件 / 乱码 / 无时间戳行）必须为 0
  4. .lrc 的 songId 必须能对上宿主库 `Track(id='netease-<songId>')`（对不上的算「孤儿 lrc」）
  5. --expect-ready N：就绪数（= .lrc 文件数）必须 ≥ N
  6. --expect-audio-files/--expect-audio-bytes：`audio-stream\\` 实测值必须与给定值**完全相等**
     （A16「预热全程音频目录零新增」的机器判据：不是「差不多」，是逐字节相等）

★ 本工具**只读**：库用 `mode=ro` 打开、目录只 stat/read，绝不写一个字节、绝不删文件。
★ 歌词缓存**不随音频缓存删除**（D5-A）：所以「lyric\\ 有文件 而 audio-stream\\ 为空」是
  **预期状态**，不是故障；本工具把它单列成一行提示，不计入 FAIL。
"""

import os
import random
import re
import sqlite3
import sys

# Windows 控制台默认 GBK，非 GBK 字符（⇒ ⚠ ✓）会让 print 直接抛 UnicodeEncodeError 而中断审计。
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

DEFAULT_DB = r'%APPDATA%\Salt Player for Windows\spw.db'
DEFAULT_DIR = r'%APPDATA%\Salt Player for Windows\workshop\data\com.example.netease'

TS = re.compile(rb'\[\d{1,3}:\d{1,2}[.:]\d{1,3}\]')
META = (b'[ti:', b'[ar:', b'[al:', b'[by:', b'[length:')
LRC_NAME = re.compile(r'^\d+\.lrc$')
# 抽样固定种子：同一批数据每次抽到同样的样本（证据可复核、可复现）
SAMPLE_SEED = 20261001

db_path = None
data_dir = None
sample = 10
check_all = False
expect_ready = None
expect_audio_files = None
expect_audio_bytes = None


def _parse_argv(argv):
    global db_path, data_dir, sample, check_all, expect_ready
    global expect_audio_files, expect_audio_bytes
    i = 0
    positional = []
    need = {
        '--db': 'db_path', '--dir': 'data_dir', '--sample': 'sample',
        '--expect-ready': 'expect_ready',
        '--expect-audio-files': 'expect_audio_files',
        '--expect-audio-bytes': 'expect_audio_bytes',
    }
    while i < len(argv):
        a = argv[i]
        if a in ('-h', '--help'):
            print(__doc__)
            return 0
        if a == '--all':
            check_all = True
            i += 1
            continue
        if a in need:
            if i + 1 >= len(argv):
                print('%s 后面要跟一个值' % a)
                return 2
            v = argv[i + 1]
            if need[a] == 'sample':
                try:
                    sample = int(v)
                except ValueError:
                    print('--sample 的值不是整数：%s' % v)
                    return 2
            elif need[a] == 'expect_audio_bytes':
                try:
                    expect_audio_bytes = int(v)
                except ValueError:
                    print('--expect-audio-bytes 的值不是整数：%s' % v)
                    return 2
            elif need[a] == 'expect_ready':
                try:
                    expect_ready = int(v)
                except ValueError:
                    print('--expect-ready 的值不是整数：%s' % v)
                    return 2
            elif need[a] == 'expect_audio_files':
                try:
                    expect_audio_files = int(v)
                except ValueError:
                    print('--expect-audio-files 的值不是整数：%s' % v)
                    return 2
            else:
                globals()[need[a]] = v
            i += 2
            continue
        if a.startswith('--'):
            print('未知参数：%s' % a)
            return 2
        positional.append(a)
        i += 1
    if positional and db_path is None:
        db_path = positional[0]
    if db_path is None:
        db_path = os.path.expandvars(DEFAULT_DB)
    if data_dir is None:
        data_dir = os.path.expandvars(DEFAULT_DIR)
    return None


def dir_stat(path):
    """(文件数, 总字节, 子目录名集合)；目录不存在返回 (0, 0, set())。"""
    if not os.path.isdir(path):
        return 0, 0, set()
    files = 0
    total = 0
    subs = set()
    for name in os.listdir(path):
        full = os.path.join(path, name)
        if os.path.isdir(full):
            subs.add(name)
            continue
        try:
            files += 1
            total += os.path.getsize(full)
        except OSError:
            pass
    return files, total, subs


def read_lrc(path):
    """返回 (verdict, detail)。verdict ∈ ok/bom/empty/not_utf8/not_lrc。"""
    try:
        data = open(path, 'rb').read()
    except OSError as e:
        return 'io', str(e)
    if not data:
        return 'empty', '0 B'
    try:
        text = data.decode('utf-8')
    except UnicodeDecodeError as e:
        return 'not_utf8', str(e)[:60]
    if not text.strip():
        return 'empty', '只有空白字符'
    if TS.search(data) is None:
        return 'not_lrc', '无带时间戳的正文行'
    return 'ok', ''


def lrc_stats(path):
    """给抽样行用的统计：(字节, 行数, 带时间戳行数, 元数据头, 同时间戳 >1 行(=翻译/罗马音已并入))。"""
    data = open(path, 'rb').read()
    lines = data.split(b'\n')
    timed = sum(1 for ln in lines if TS.search(ln))
    head = [h.decode('ascii', 'replace') for h in META if h in data[:2048]]
    stamps = {}
    for ln in lines:
        m = TS.search(ln)
        if m is not None:
            stamps[m.group(0)] = stamps.get(m.group(0), 0) + 1
    twin = sum(1 for v in stamps.values() if v > 1)
    return len(data), len(lines), timed, ','.join(x.strip('[').rstrip(':') for x in head), twin


def main():
    rc = _parse_argv(sys.argv[1:])
    if rc is not None:
        return rc

    lyric_dir = os.path.join(data_dir, 'lyric')
    audio_dir = os.path.join(data_dir, 'audio-stream')
    cover_dir = os.path.join(data_dir, 'audio-cover')

    print('数据目录    = %s' % data_dir)
    print('歌词缓存    = %s' % lyric_dir)
    print('库文件      = %s' % db_path)
    print()

    lf, lb, _ = dir_stat(lyric_dir)
    af, ab, _ = dir_stat(audio_dir)
    cf, cb, _ = dir_stat(cover_dir)
    print('--- 目录计数（只 stat，不读内容） ---')
    print('  lyric\\        %6d 个文件   %12d B (%.1f MB)' % (lf, lb, lb / 1048576.0))
    print('  audio-stream\\ %6d 个文件   %12d B (%.1f MB)   ← A16 判据对象' % (
        af, ab, ab / 1048576.0))
    print('  audio-cover\\  %6d 个文件   %12d B (%.1f MB)   ← W3 直连副本（现行违规）' % (
        cf, cb, cb / 1048576.0))
    print()

    # ---- 文件清单（不读内容，只列名字）
    reasons = []
    names = []
    bad_names = []
    if not os.path.isdir(lyric_dir):
        reasons.append('歌词缓存目录不存在：%s（插件可能还没跑过歌词预热）' % lyric_dir)
    else:
        for name in os.listdir(lyric_dir):
            full = os.path.join(lyric_dir, name)
            if not os.path.isfile(full):
                continue
            names.append(name)
            if not LRC_NAME.match(name):
                bad_names.append(name)
        names.sort(key=lambda n: int(n[:-4]) if LRC_NAME.match(n) else 0)
        print('--- 文件清单 ---')
        print('  .lrc 文件 = %d 个' % len(names))
        if bad_names:
            print('  ⚠ 命名异常文件 = %d 个：%s' % (len(bad_names), ', '.join(bad_names[:10])))
        # 首尾各 3 个：便于报告里对上日志时间戳
        show = names[:3] + (['…'] if len(names) > 6 else []) + names[-3:]
        print('  首尾样本 = %s' % ', '.join(show))
        ids = set()
        for n in names:
            if LRC_NAME.match(n):
                ids.add(int(n[:-4]))
        print('  唯一 songId = %d 个' % len(ids))
        print()

        # ---- 抽样校验
        picks = names if check_all else random.Random(SAMPLE_SEED).sample(
            names, min(sample, len(names)))
        print('--- LRC 抽样校验（%s，%d 个） ---' % (
            '全量' if check_all else 'sample=%d' % sample, len(picks)))
        bad = []
        ok = 0
        for n in picks:
            full = os.path.join(lyric_dir, n)
            v, d = read_lrc(full)
            if v == 'ok':
                ok += 1
                b, lines, timed, head, twin = lrc_stats(full)
                print('  ✓ %-12s %6d B  %3d 行  带时间戳 %3d 行  重时间戳 %3d 行  头=%s' % (
                    n, b, lines, timed, twin, head or '(已按 LrcUtil.clean 去除 ti/ar/by)'))
            else:
                bad.append((n, v, d))
                print('  ✗ %-12s %s %s' % (n, v, d))
        print('  抽样结果 = 合格 %d / 异常 %d' % (ok, len(bad)))
        print()
        if bad:
            reasons.append('抽样校验不合格 %d 个（%s）' % (
                len(bad), '; '.join('%s:%s' % (n, v) for n, v, _ in bad[:5])))

    # ---- 与库内曲目数对照
    n_track = None
    orphans = []
    if not os.path.isfile(db_path):
        print('库不存在（跳过覆盖对照）：%s' % db_path)
        print()
    else:
        con = sqlite3.connect('file:' + db_path.replace('\\', '/') + '?mode=ro', uri=True)
        cur = con.cursor()
        n_track = cur.execute(
            "select count(*) from Track where id like 'netease-%'").fetchone()[0]
        print('--- 与宿主库对照 ---')
        print('  库内插件曲目 = %d 首（Track.id LIKE netease-%%）' % n_track)
        if names:
            ids = sorted(int(n[:-4]) for n in names if LRC_NAME.match(n))
            # 分批查，避开 SQLite 变量上限
            known = set()
            for i in range(0, len(ids), 400):
                chunk = ids[i:i + 400]
                q = 'select id from Track where id in (%s)' % ','.join(
                    "'netease-%d'" % x for x in chunk)
                for (tid,) in cur.execute(q):
                    known.add(int(tid.split('-')[-1]))
            orphans = [x for x in ids if x not in known]
            print('  就绪（.lrc）/ 曲目 = %d / %d = %.2f%%' % (
                len(ids), n_track, 100.0 * len(ids) / n_track if n_track else 0))
            print('  孤儿 .lrc（songId 不在库内）= %d 个%s' % (
                len(orphans), ('：%s' % orphans[:8]) if orphans else ''))
        con.close()
        if orphans:
            reasons.append('孤儿 .lrc %d 个（songId 不在库内；可能是同步前抓的旧曲目）' % len(orphans))
        print()

    # ---- 断言
    print('--- 断言 ---')
    if expect_ready is not None:
        print('  期望就绪 ≥ %d（日志回报的「已就绪」）；实测 %d ⇒ %s' % (
            expect_ready, lf, 'PASS' if lf >= expect_ready else 'FAIL'))
        if lf < expect_ready:
            reasons.append('就绪 .lrc %d 个 < 期望 %d' % (lf, expect_ready))
    if expect_audio_files is not None:
        same = af == expect_audio_files
        print('  期望 audio-stream\\ 文件数 = %d；实测 %d ⇒ %s' % (
            expect_audio_files, af, 'PASS' if same else 'FAIL'))
        if not same:
            reasons.append('audio-stream\\ 文件数 %d ≠ 期望 %d（预热期新增了音频）' % (
                af, expect_audio_files))
    if expect_audio_bytes is not None:
        same = ab == expect_audio_bytes
        print('  期望 audio-stream\\ 字节数 = %d；实测 %d ⇒ %s' % (
            expect_audio_bytes, ab, 'PASS' if same else 'FAIL'))
        if not same:
            reasons.append('audio-stream\\ 字节数 %d ≠ 期望 %d（逐字节相等才是「零新增」）' % (
                ab, expect_audio_bytes))
    if expect_ready is None and expect_audio_files is None and expect_audio_bytes is None:
        print('  （本次未带 --expect-* 参数：只出数字不打分）')
    print()

    # ---- 失败分类（从日志读，只读不解析复杂时序）
    log_dir = os.path.join(data_dir, 'logs')
    print('--- 失败分类（读插件日志；本工具不改日志） ---')
    if not os.path.isdir(log_dir):
        print('  日志目录不存在（跳过）：%s' % log_dir)
    else:
        import glob as _glob
        files = sorted(_glob.glob(os.path.join(log_dir, '*.log')))
        print('  日志文件 = %d 个（最新：%s）' % (
            len(files), os.path.basename(files[-1]) if files else '-'))
        keys = ['歌词预热：', '歌词预取完成：', 'NO_LYRIC', '风控', '退避', 'RISK', '失败']
        counts = {k: 0 for k in keys}
        sample_lines = []
        for f in files[-3:]:
            try:
                for ln in open(f, encoding='utf-8', errors='replace'):
                    for k in keys:
                        if k in ln:
                            counts[k] += 1
                            if len(sample_lines) < 6 and k == '歌词预热：':
                                sample_lines.append(ln.rstrip()[:160])
                            break
            except OSError:
                pass
        for k in keys:
            print('  %-14s %d 行' % (k, counts[k]))
        for s in sample_lines:
            print('    · %s' % s)
    print()

    if bad_names:
        reasons.append('.lrc 命名异常 %d 个：%s' % (len(bad_names), ', '.join(bad_names[:5])))

    ok = not reasons
    print('--- 结论 ---')
    if ok:
        print('结论：PASS（就绪 .lrc %d 个 / %.1f MB；audio-stream\\ %d 个 / %d B；抽样全合格%s）' % (
            lf, lb / 1048576.0, af, ab,
            '；命名零异常' if not bad_names else ''))
    else:
        print('结论：FAIL')
        for r in reasons:
            print('  - %s' % r)
    # 提示：歌词独立于音频缓存（D5-A）
    if lf > 0 and af == 0:
        print('提示：lyric\\ 有 %d 个 .lrc 而 audio-stream\\ 为空 —— 这是 D5-A 的**预期状态**'
              '（歌词不随音频缓存删除），不是故障。' % lf)
    return 0 if ok else 1


if __name__ == '__main__':
    raise SystemExit(main())
