#!/usr/bin/env python3
"""只读统计：现有 PlaylistTrack 里到底有多少首**不重复**的歌（P0 自检算术取证用）。

用途：0.11.7 的写入自检把「本轮遍历到的 (歌单×曲目) 对数」当作「应有 Track 行数」，
对同一首歌出现在多张歌单里（本机实测 ×3 很常见）的情况必然误报。本脚本给出真值：
distinct trackId 数 vs 关联行数。
"""
import os
import sqlite3
import sys

DB = os.path.join(os.environ['APPDATA'], 'Salt Player for Windows', 'spw.db')


def main() -> int:
    uri = 'file:' + DB.replace('\\', '/') + '?mode=ro'
    c = sqlite3.connect(uri, uri=True)
    one = lambda s: c.execute(s).fetchone()[0]  # noqa: E731

    print('库文件          =', DB)
    print('PlaylistTrack   =', one('select count(*) from PlaylistTrack'))
    print('  其中 netease- =', one("select count(*) from PlaylistTrack where trackId like 'netease-%'"))
    print('  distinct 曲目 =', one("select count(distinct trackId) from PlaylistTrack where trackId like 'netease-%'"))
    print('  distinct 歌单 =', one("select count(distinct playlistId) from PlaylistTrack where playlistId like 'netease-%'"))
    print('Track 总计      =', one('select count(*) from Track'))
    print()
    print('每张歌单：关联行 / 不重复曲目')
    for pid, name, n, d in c.execute(
            "select p.id, p.title, count(*), count(distinct pt.trackId) "
            "from PlaylistTrack pt join Playlist p on p.id = pt.playlistId "
            "where pt.playlistId like 'netease-%' group by pt.playlistId order by 3 desc"):
        print('  %-28s %-20s %6d / %6d' % (pid, name, n, d))
    c.close()
    return 0


if __name__ == '__main__':
    sys.exit(main())
