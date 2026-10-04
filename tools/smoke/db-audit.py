#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""宿主曲库只读审计（不改一个字节）——用于验证「歌单里没有歌」这类映射故障。

用法：
    python tools/smoke/db-audit.py                 # 审计默认宿主库 %APPDATA%\\Salt Player for Windows\\spw.db
    python tools/smoke/db-audit.py <path\\to\\spw.db>
     python tools/smoke/db-audit.py [<db>] --expect-tracks N   # 额外断言插件曲目行数恰为 N（N=日志里回报的「曲目 N 首」）

★ 真机硬门必须**不带 --copy**跑（默认判据即为硬门四条）。`--copy` 只用于审计「从真机拷出来、
宿主从未启动过」的库副本：那种库上 Album/Artist 必然是 0（派生只发生在宿主自己启动扫描之后），
跳过这一条是为了让副本能真实反映「曲目映射链」本身的状态。

判据（任一不满足即说明「曲目映射」这条链断了）：
  1. PlaylistTrack 里不存在孤儿行（trackId 在 Track 里必须存在）
  2. 有插件歌单时，Track(id LIKE 'netease-%') 行数必须 > 0
  3. Album / Artist 行数 > 0（宿主按 Track 派生；Track=0 时它们必然是 0）
  4. --expect-tracks N（可选）：插件曲目行数必须恰好等于 N（把「库内行数 == 同步日志回报数」钉死）

背景：0.11.6 真机实测（2026-09-30 19:03~19:08）——插件报「曲目 5935 首 / 关联 5935 行」，
但 Track 实为 0 行、PlaylistTrack 5935 行全是孤儿 ⇒ 宿主歌单详情 join 不到曲目 ⇒ 空歌单。
根因：宿主 Track 表有 4 个 `REAL NOT NULL` 新列（trackGain / trackPeak / albumGain / albumPeak）
不在插件的 INSERT 列清单里，`INSERT OR IGNORE` 把 NOT NULL 违约**静默吞掉**。
"""

import os
import sqlite3
import sys

# Windows 控制台默认 GBK，非 GBK 字符（⇒ ⚠）会让 print 直接抛 UnicodeEncodeError 而中断审计本身。
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

DB = None
EXPECT_TRACKS = None
COPY_MODE = False


def _parse_argv(argv):
    """只读工具也要防手滑：解析 --expect-tracks N，其余位置参数按「库路径」处理。"""
    global DB, EXPECT_TRACKS, COPY_MODE
    positional = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == '--copy':
            COPY_MODE = True
            i += 1
            continue
        if a == '--expect-tracks':
            if i + 1 >= len(argv):
                print('--expect-tracks 后面要跟一个整数（日志里回报的曲目数）')
                return 2
            try:
                EXPECT_TRACKS = int(argv[i + 1])
            except ValueError:
                print('--expect-tracks 的值不是整数：%s' % argv[i + 1])
                return 2
            i += 2
            continue
        if a in ('-h', '--help'):
            print(__doc__)
            return 0
        if a.startswith('--'):
            print('未知参数：%s（本工具只读，仅支持 --expect-tracks N）' % a)
            return 2
        positional.append(a)
        i += 1
    DB = positional[0] if positional else os.path.expandvars(
        r'%APPDATA%\Salt Player for Windows\spw.db')
    return None

PREFIX = 'netease-'
# 插件写入 Track 时必须覆盖的列（宿主 schema 里的 NOT NULL 列，均无默认值）
EXPECTED_NOT_NULL = [
    'id', 'title', 'artist', 'album', 'albumArtist', 'genre', 'year', 'number',
    'isFavorite', 'playCount', 'order', 'path', 'readable', 'size', 'addedTime',
    'modifiedTime', 'coverRevision', 'duration', 'bitsPerSample', 'sampleRate',
    'bitrate', 'trackGain', 'trackPeak', 'albumGain', 'albumPeak',
]


def main() -> int:
    rc = _parse_argv(sys.argv[1:])
    if rc is not None:
        return rc
    if not os.path.isfile(DB):
        print('曲库不存在：%s' % DB)
        return 2
    con = sqlite3.connect('file:' + DB.replace('\\', '/') + '?mode=ro', uri=True)
    cur = con.cursor()

    def one(sql, *a):
        return cur.execute(sql, a).fetchone()[0]

    print('库文件      = %s (%d B)' % (DB, os.path.getsize(DB)))
    if COPY_MODE:
        print('模式        = --copy（副本：跳过宿主派生类判据 Album/Artist>0）')
    print('user_version= %s  room_master= %s' % (
        one('PRAGMA user_version'),
        (cur.execute('select identity_hash from room_master_table').fetchone() or ['?'])[0]))

    n_track = one('select count(*) from Track')
    n_track_pl = one('select count(*) from Track where id like ?', PREFIX + '%')
    n_pl = one("select count(*) from Playlist where id like ?", PREFIX + '%')
    n_link = one("select count(*) from PlaylistTrack where playlistId like ?", PREFIX + '%')
    n_orphan = one("select count(*) from PlaylistTrack pt where pt.playlistId like ?"
                   " and not exists (select 1 from Track t where t.id = pt.trackId)", PREFIX + '%')
    n_album = one('select count(*) from Album')
    n_artist = one('select count(*) from Artist')

    print('Track 总计   = %d（其中插件曲目 %d）' % (n_track, n_track_pl))
    print('Playlist     = %d 个插件歌单' % n_pl)
    print('PlaylistTrack= %d 行（孤儿 %d 行）' % (n_link, n_orphan))
    print('Album/Artist = %d / %d' % (n_album, n_artist))

    cols = [r[1] for r in cur.execute('PRAGMA table_info(Track)')]
    missing = [c for c in EXPECTED_NOT_NULL if c not in cols]
    if missing:
        print('⚠ Track 表存在插件未覆盖的列：%s' % ', '.join(missing))
    print('Track 全部列 = %s' % ', '.join(cols))

    print()
    print('--- 每张歌单的可见曲目数（宿主侧 join 的结果） ---')
    rows = list(cur.execute(
        "select p.id, p.title, count(pt.trackId) from Playlist p"
        " left join PlaylistTrack pt on pt.playlistId = p.id"
        " where p.id like ? group by p.id order by p.`order`", (PREFIX + '%',)))
    for pid, title, declared in rows:
        visible = one("select count(*) from PlaylistTrack pt join Track t on t.id = pt.trackId"
                      " where pt.playlistId = ?", pid)
        print('  %-28s %-24s 关联 %-5d 可见 %d' % (pid, title[:24], declared, visible))

    print()
    reasons = []
    if n_orphan != 0:
        reasons.append('孤儿关联行 %d 行（trackId 在 Track 里不存在）' % n_orphan)
    if n_pl > 0 and n_track_pl == 0:
        reasons.append('插件歌单 %d 个但插件曲目 0 行（宿主 join 不到曲目 ⇒ 歌单是空的）' % n_pl)
    if n_pl > 0 and (n_album == 0 or n_artist == 0):
        if COPY_MODE:
            print('⚠ --copy 模式：跳过「Album/Artist > 0」判据（Album/Artist = %d / %d；'
                  '宿主未参与派生 ⇒ 副本上必然是 0，真机硬门必须不带 --copy 跑）'
                  % (n_album, n_artist))
        else:
            reasons.append('Album/Artist = %d / %d（宿主按 Track 派生，Track 正常时必然 > 0）'
                           % (n_album, n_artist))
            if n_orphan == 0 and n_track_pl > 0:
                print('    ↳ 提示：映射链本身看起来是好的（插件曲目 %d 行、孤儿 0 行）⇒ 这一条大概率是'
                      '「宿主还没把自己扫完」（Album/Artist 行只在宿主启动扫描后才派生）；'
                      '判「映射链是否修好」看上面两行就够，别把它读成链接断裂。'
                      % n_track_pl)
    if missing:
        reasons.append('Track 表存在插件未覆盖的列：%s' % ', '.join(missing))
    if EXPECT_TRACKS is not None:
        print('期望插件曲目 = %d 行（来自同步日志）' % EXPECT_TRACKS)
        if n_track_pl != EXPECT_TRACKS:
            reasons.append('插件曲目 %d 行 ≠ 日志回报 %d 首'
                           % (n_track_pl, EXPECT_TRACKS))

    ok = not reasons
    if ok:
        print('结论：PASS（曲目映射完好：插件曲目 %d 行、孤儿 0、Album/Artist %d/%d%s）'
              % (n_track_pl, n_album, n_artist,
                 '；--copy 模式已跳过 Album/Artist 判据' if COPY_MODE else ''))
    else:
        print('结论：FAIL（曲目映射断裂，见上面数字）')
        for r in reasons:
            print('  - %s' % r)
    return 0 if ok else 1


if __name__ == '__main__':
    raise SystemExit(main())
