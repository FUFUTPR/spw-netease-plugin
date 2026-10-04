#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""宿主库全表盘点（**只读**）——回答「宿主自己派生哪些表、哪些表必须插件自己写」。

用途（task-6 W2 第二段交付物 1）：P0 修好以后真机库是 `Track 3267 / 孤儿 0 / Album 2404 / Artist 1714`，
说明宿主**会按 Track 行派生 Album/Artist**。本工具把同一问题推广到所有表：

    TrackArtist / TrackTagOverride / MusicVideo / … 这些派生/关联表，宿主运行期会不会自己填？
      · 有 netease- 行  ⇒ 宿主自己派生（插件不要写，写了会被宿主重派生抹掉，且可能触发 CASCADE）
      · 0 行            ⇒ 宿主不派生 ⇒ 要显示该信息必须由插件写（P2 才需要动）

用法：
    python tools/smoke/tables-audit.py                      # 自动拷真机库三件套到 build\\probe\\real-db-live 再读
    python tools/smoke/tables-audit.py --db <path\\spw.db>   # 读指定库（副本；真机库请确保 -wal/-shm 一起在）
    python tools/smoke/tables-audit.py --no-copy            # 直接读真机库（宿主在跑时有风险，默认不这么做）

★ 真机库必须连 `-wal` / `-shm` 一起拷：`spw.db` 本体只有 4 KB，数据全在 WAL 里，只拷本体读到的是空库。
★ 本工具只开 `mode=ro` 连接，绝不写一个字节。
"""

import os
import shutil
import sqlite3
import sys
import tempfile

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

PREFIX = 'netease-'
HOST_DIR = 'Salt Player for Windows'
# 内部表 / Room 自己的表 / FTS 影子表：不参与「宿主派生 vs 插件写」判断
SKIP_TABLES = {
    'room_master_table', 'android_metadata', 'sqlite_sequence',
    'room_table_modification_log', 'sqlite_stat1',
}
SKIP_SUFFIX = ('_fts', '_fts_data', '_fts_idx', '_fts_content', '_fts_docsize', '_fts_config',
               '_fts_segdir', '_fts_segments', '_content', '_data', '_idx', '_docsize', '_config')

# 用来判断「这行是不是插件写的」的候选列（TEXT 主键或外键列）
ID_COLUMNS = ('id', 'trackId', 'playlistId', 'albumId', 'artistId', 'track_id', 'playlist_id',
              'album_id', 'artist_id', 'trackArtistId', 'musicVideoId')


def _default_db() -> str:
    return os.path.expandvars(r'%APPDATA%' + '\\' + HOST_DIR + r'\spw.db')


def _parse_argv(argv):
    """返回 (db_path, copy_first)；解析失败返回 ('', False) 并由调用方退出。"""
    db = None
    copy_first = True
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == '--db':
            if i + 1 >= len(argv):
                print('--db 后面要跟库路径')
                return None, False
            db = argv[i + 1]
            i += 2
            continue
        if a == '--no-copy':
            copy_first = False
            i += 1
            continue
        if a in ('-h', '--help'):
            print(__doc__)
            return None, False
        if a.startswith('--'):
            print('未知参数：%s' % a)
            return None, False
        db = a
        i += 1
    if db is None:
        db = _default_db()
    return db, copy_first


def _copy_triplet(src: str) -> str:
    """把 spw.db 三件套拷到 build\\probe\\real-db-live\\，返回副本里的 spw.db 路径。"""
    root = os.path.join('build', 'probe', 'real-db-live')
    os.makedirs(root, exist_ok=True)
    dst = os.path.join(root, os.path.basename(src))
    for suffix in ('', '-wal', '-shm'):
        s = src + suffix
        if os.path.isfile(s):
            shutil.copyfile(s, dst + suffix)
    return dst


def _is_skipped(table: str) -> bool:
    if table in SKIP_TABLES:
        return True
    if table.startswith('sqlite_'):
        return True
    if table.startswith('room_'):
        return True
    return table.endswith(SKIP_SUFFIX)


def main() -> int:
    db, copy_first = _parse_argv(sys.argv[1:])
    if db is None:
        return 2
    if not os.path.isfile(db):
        print('曲库不存在：%s' % db)
        return 2
    origin = db
    if copy_first:
        db = _copy_triplet(db)
        print('已拷贝真机库三件套 → %s' % db)

    con = sqlite3.connect('file:' + db.replace('\\', '/') + '?mode=ro', uri=True)
    cur = con.cursor()
    print('库文件      = %s (%d B)' % (origin, os.path.getsize(origin)))
    print('user_version= %s' % cur.execute('PRAGMA user_version').fetchone()[0])
    print()

    tables = [r[0] for r in cur.execute(
        "select name from sqlite_master where type='table' order by name")]
    tables = [t for t in tables if not _is_skipped(t)]

    print('=== 全表行数（★ = 含 netease- 行）===')
    print('%-34s %8s  %-40s %s' % ('表', '行数', 'netease- 行数（按 id 类列判断）', '列数'))
    detail = {}
    for t in tables:
        try:
            n = cur.execute('select count(*) from "%s"' % t).fetchone()[0]
        except sqlite3.Error as e:
            print('%-34s %8s  %s' % (t, 'ERR', e))
            continue
        cols = [r[1] for r in cur.execute('PRAGMA table_info("%s")' % t)]
        id_cols = [c for c in ID_COLUMNS if c in cols]
        n_pl = 0
        if n and id_cols:
            where = ' OR '.join('"%s" like ?' % c for c in id_cols)
            n_pl = cur.execute('select count(*) from "%s" where %s' % (t, where),
                               tuple([PREFIX + '%'] * len(id_cols))).fetchone()[0]
        detail[t] = {'rows': n, 'cols': cols, 'id_cols': id_cols, 'netease': n_pl}
        if n and not id_cols:
            # 没有 id 类列的表（Album / Artist / Genre…）：主键是业务键（title/name），前缀判断不适用
            mark = '（无 id 类列）'
        else:
            mark = '%d%s' % (n_pl, '  ★' if n_pl else '')
        print('%-34s %8d  %-40s %d' % (t, n, mark, len(cols)))
    print()

    # ---- 与 Track / Playlist 有外键关系的表（CASCADE 风险面 + 派生候选）----
    print('=== 谁引用 Track / Playlist（外键 + ON DELETE 动作）===')
    track_tables = []
    for t in tables:
        try:
            fks = list(cur.execute('PRAGMA foreign_key_list("%s")' % t))
        except sqlite3.Error:
            continue
        for fk in fks:
            ref = fk[2]
            if ref in ('Track', 'Playlist', 'Album', 'Artist'):
                print('  %-30s → %-10s (%s) ON DELETE %s' % (t, ref, fk[3], fk[6]))
                if ref == 'Track':
                    track_tables.append((t, fk[3]))
    print()

    # ---- 结论：哪些派生表宿主自己填、哪些必须插件写 ----
    print('=== 结论：netease- 曲目进了哪些表 ===')
    fill = []
    for t, _ in track_tables:
        info = detail.get(t)
        if not info:
            continue
        fill.append((t, info['rows'], info['netease']))
    n_track_pl = detail.get('Track', {}).get('netease', 0)
    for t, rows, n_pl in sorted(fill, key=lambda x: -x[2]):
        verdict = '宿主自己派生（插件不要再写）' if n_pl else '空 ⇒ 宿主不派生，要显示须插件写'
        print('  %-30s 总行 %-7d netease- 行 %-7d %s' % (t, rows, n_pl, verdict))
        # 覆盖率：宿主是否把**每个** netease 曲目都派生到了这张表
        info = detail[t]
        if n_pl and 'trackId' in info['cols'] and n_track_pl:
            covered = cur.execute(
                'select count(distinct "trackId") from "%s" where "trackId" like ?' % t,
                (PREFIX + '%',)).fetchone()[0]
            print('  %-30s ↳ 覆盖 %d / %d 首插件曲目（%.1f%%）'
                  % ('', covered, n_track_pl, 100.0 * covered / n_track_pl))
    if not fill:
        print('  （没有引用 Track 的表）')
    print()

    print('=== Track / Album / Artist 概览 ===')
    for t in ('Track', 'Album', 'Artist'):
        info = detail.get(t)
        if info:
            print('  %-8s 总行 %-7d netease- 行 %-7d 列 %s'
                  % (t, info['rows'], info['netease'], ', '.join(info['cols'][:8]) + '…'))
    print()
    print('提示：宿主下次启动/重建曲库时会**重派生** Album/Artist（docs\\00 §6.13），'
          '所以「0 行」也可能是宿主还没跑完；若某表长期 0 行且宿主 UI 缺该信息 ⇒ 才需要插件补写。')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
