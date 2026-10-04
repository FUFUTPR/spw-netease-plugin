#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
歌词接口抽样对照（只读探针，0.11.8 W4 验收辅助）。

用途：C 轮全量预热后，`lyric\\` 里只落了 A 个 `.lrc`，其余按插件口径记为 NO_LYRIC。
本工具直接拿宿主库里的曲目 id 抽样问网易云歌词接口，对照「有盘上文件 / 无盘上文件」
两组，判断那批 NO_LYRIC 是**接口真的没词**还是**插件侧漏判**（cookie/风控/串行差异）。

纪律：
  * 只读宿主库（拷副本 + WAL/SHM，绝不动原库）；
  * 只发 GET，不写任何宿主文件；
  * 不打印 cookie（本脚本不读 cookie，匿名请求）。

用法：
    python tools\\smoke\\lyric-api-sample.py                # 默认每组 6 首
    python tools\\smoke\\lyric-api-sample.py --per-group 10
"""
from __future__ import annotations

import argparse
import json
import os
import random
import re
import shutil
import sqlite3
import sys
import tempfile
import urllib.request
from pathlib import Path

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
API = "https://music.163.com/api/song/lyric?os=pc&id={}&lv=-1&kv=-1&tv=-1"


def data_dir() -> Path:
    return (Path(os.environ["APPDATA"]) / "Salt Player for Windows"
            / "workshop" / "data" / "com.example.netease")


def host_db() -> Path:
    return Path(os.environ["APPDATA"]) / "Salt Player for Windows" / "spw.db"


def copy_db(src: Path, dst_dir: Path) -> Path:
    dst = dst_dir / src.name
    for suffix in ("", "-wal", "-shm"):
        s = Path(str(src) + suffix)
        if s.exists():
            shutil.copy2(s, Path(str(dst) + suffix))
    return dst


def track_ids(db: Path) -> list[str]:
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    try:
        cur = con.execute("SELECT id, path FROM Track")
        out = []
        for tid, path in cur.fetchall():
            v = str(tid or "")
            if v.lower().startswith("netease-"):
                v = v[8:]
            if v.isdigit():
                out.append(v)
                continue
            p = str(path or "")
            i = p.find("/netease/")
            if i >= 0:
                j = i + 9
                k = j
                while k < len(p) and p[k].isdigit():
                    k += 1
                if k > j:
                    out.append(p[j:k])
        return out
    finally:
        con.close()


META_WORDS = ("作词", "作曲", "编曲", "混音", "母带", "制作人", "出品", "监制",
              "纯音乐，请欣赏", "OP：", "SP：", "词：", "曲：", "录音", "和声", "人声")


def kind_of(lyric: str) -> str:
    """把接口返回的歌词判成 real / placeholder / empty（判据与插件侧 NO_LYRIC 对齐）。"""
    if not lyric:
        return "empty"
    body = []
    for line in lyric.splitlines():
        t = re.sub(r"^(\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]\s*)+", "", line).strip()
        if not t or any(t.startswith(w) or w in t for w in META_WORDS):
            continue
        body.append(t)
    n = sum(len(x) for x in body)
    return "real" if n >= 15 else "placeholder"


def ask(song_id: str) -> dict:
    req = urllib.request.Request(API.format(song_id), headers={
        "User-Agent": UA,
        "Referer": "https://music.163.com",
        "Accept": "*/*",
    })
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read()
            code = r.status
    except Exception as e:  # noqa: BLE001
        return {"id": song_id, "http": -1, "err": f"{type(e).__name__}: {e}"}
    try:
        js = json.loads(raw.decode("utf-8", "replace"))
    except Exception:  # noqa: BLE001
        return {"id": song_id, "http": code, "bytes": len(raw), "err": "非 JSON"}
    lyric = ((js.get("lrc") or {}).get("lyric") or "").strip()
    return {
        "id": song_id,
        "http": code,
        "bytes": len(raw),
        "api_code": js.get("code"),
        "fact": "nolyric" if js.get("nolyric") else ("uncollected" if js.get("uncollected") else ""),
        "lyric_chars": len(lyric),
        "kind": kind_of(lyric),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-group", type=int, default=6)
    ap.add_argument("--seed", type=int, default=20261001)
    args = ap.parse_args()

    d = data_dir()
    lrc = {p.stem for p in (d / "lyric").glob("*.lrc")}
    db = host_db()
    if not db.exists():
        print(f"[ERR] 找不到宿主库 {db}")
        return 2
    with tempfile.TemporaryDirectory() as tmp:
        copy = copy_db(db, Path(tmp))
        ids = track_ids(copy)

    have = [i for i in ids if i in lrc]
    miss = [i for i in ids if i not in lrc]
    rng = random.Random(args.seed)
    rng.shuffle(have)
    rng.shuffle(miss)

    print(f"曲目 id 总数 = {len(ids)}｜盘上有 .lrc = {len(have)}｜盘上无 .lrc = {len(miss)}")
    summary: dict[str, dict[str, int]] = {}
    for label, pool in (("HAVE", have), ("MISS", miss)):
        print(f"\n--- 抽样组 {label}（{len(pool)} 首可取，取 {args.per_group}）---")
        tally = {"real": 0, "placeholder": 0, "empty": 0, "err": 0}
        for sid in pool[: args.per_group]:
            r = ask(sid)
            if r.get("err"):
                tally["err"] += 1
            else:
                tally[r.get("kind", "empty")] += 1
            print("  " + json.dumps(r, ensure_ascii=False))
        summary[label] = tally
        print(f"  [{label}] 小计 = {json.dumps(tally, ensure_ascii=False)}")
    print("\n=== 对照结论 ===")
    print(f"HAVE 组 {json.dumps(summary.get('HAVE', {}), ensure_ascii=False)}"
          f" —— 有词却拿不到 real ⇒ 插件漏抓/漏写")
    print(f"MISS 组 {json.dumps(summary.get('MISS', {}), ensure_ascii=False)}"
          f" —— real>0 ⇒ 插件把「有词」误判成 NO_LYRIC")
    return 0


if __name__ == "__main__":
    sys.exit(main())
