#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""只读：核对插件写入的 Track.path / coverRevision / readable 现状。

用法：
    python tools/smoke/path-audit.py [<spw.db>] [--copy]

判据：
  - path 非空的 netease- 行应占绝大多数（D8 甲：path = http://127.0.0.1:<port>/netease/<songId>）
  - coverRevision 应 >0（CoverStore 写桩后会 +1；0 说明宿主还没请求过封面）
  - 打印 path 前缀分布，便于发现 null / 空串 / 旧前缀残留
"""
import argparse
import os
import shutil
import sqlite3
import sys
import tempfile


def default_db() -> str:
    return os.path.join(os.environ.get("APPDATA", ""), "Salt Player for Windows", "spw.db")


def connect(path: str) -> sqlite3.Connection:
    uri = "file:" + path.replace("\\", "/").replace("?", "%3f") + "?mode=ro"
    return sqlite3.connect(uri, uri=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("db", nargs="?", default=default_db())
    ap.add_argument("--copy", action="store_true", help="拷到临时目录再读（含 -wal/-shm）")
    args = ap.parse_args()

    path = args.db
    if args.copy:
        tmp = tempfile.mkdtemp(prefix="spw-path-")
        for suf in ("", "-wal", "-shm"):
            src = path + suf
            if os.path.exists(src):
                shutil.copy2(src, os.path.join(tmp, os.path.basename(src)))
        path = os.path.join(tmp, os.path.basename(path))

    print("库文件 =", path, "存在 =", os.path.exists(path))
    con = connect(path)
    c = con.cursor()

    total = c.execute("SELECT COUNT(*) FROM Track WHERE id LIKE 'netease-%'").fetchone()[0]
    nonnull = c.execute(
        "SELECT COUNT(*) FROM Track WHERE id LIKE 'netease-%' AND path IS NOT NULL AND path <> ''"
    ).fetchone()[0]
    print("netease- Track 行 = %d，其中 path 非空 = %d" % (total, nonnull))

    print("\npath 前缀分布（前 8）：")
    rows = c.execute(
        "SELECT substr(path,1,40) AS p, COUNT(*) FROM Track WHERE id LIKE 'netease-%' "
        "GROUP BY p ORDER BY 2 DESC LIMIT 8"
    ).fetchall()
    for p, n in rows:
        print("  %-42s %d" % (p, n))

    print("\ncoverRevision 分布：")
    for rev, n in c.execute(
        "SELECT coverRevision, COUNT(*) FROM Track WHERE id LIKE 'netease-%' GROUP BY 1 ORDER BY 1 LIMIT 6"
    ).fetchall():
        print("  coverRevision=%-6s %d" % (rev, n))

    print("\nreadable 分布：")
    for r, n in c.execute(
        "SELECT readable, COUNT(*) FROM Track WHERE id LIKE 'netease-%' GROUP BY 1"
    ).fetchall():
        print("  readable=%-6s %d" % (r, n))

    print("\n当前播放曲样本（若有）：")
    for tid in ("netease-2748448986", "netease-2643127259"):
        r = c.execute(
            "SELECT id,title,path,size,coverRevision,readable FROM Track WHERE id=?", (tid,)
        ).fetchone()
        print("  %s" % (r,))
    con.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
