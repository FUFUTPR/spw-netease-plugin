#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""宿主封面磁盘缓存「标记图」复原工具（Lead 所有，只针对 B2 轮探针事故）

背景
----
B2 轮 P-4 的 S5i 标记图实验把宿主 coil 磁盘缓存 `cache\\shared_cover` 里 **74 个键**
的内容临时换成了 2074 字节的品红标记图，计划在窗口末原样写回；写回 **0/74 全部失败**
（`harness\\logs\\p4-20261001-020544.txt`：`标记图写回：写回成功 0 / 74`）。
后果：宿主列表行封面会显示品红标记图，且宿主自己不会重新请求（它认为缓存里已有）。

本工具把受污染的条目**原位复原**为原始图字节：
  1. 只读宿主库取 `Track` 行（id / path / size / coverRevision / album）；
  2. 对 74 个「2074 字节 + sha256 前 12 位 = 1c6ff19ecd20」的缓存文件，
     用 `sha256(候选键) == 文件名` 反查它对应哪首曲、哪个尺寸、哪套公式；
  3. 原始字节来源优先级：
     a) P-4 报告里记的「原字节 sha256=xxxxxxxxxxxx」+ 原字节数 ⇒ 在 `cover\\img` 里按哈希找同字节文件；
     b) 报告没记到（报告只列了前 60 条）⇒ 用 `Track.album` → `cover\\cover-index.json`（`a` 字段匹配）→ `i` 文件名；
  4. 默认 dry-run 只打印计划；加 `--apply` 才真的覆盖写回。

用法
----
    python tools\\smoke\\host-cache-restore.py                 # 只看计划
    python tools\\smoke\\host-cache-restore.py --apply         # 真的复原
    python tools\\smoke\\host-cache-restore.py --data <插件数据目录> --host-cache <shared_cover 目录>

只读宿主库（`file:...?mode=ro`，带 -wal/-shm），不改库、不改插件数据、不联网。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys
from pathlib import Path

MARKER_SIZE = 2074
MARKER_SHA12 = "1c6ff19ecd20"
PINNED_T = 1735689600000
SIZES = (96, 192, 256, 384, 500, 1200)

sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def default_plugin_data() -> Path:
    return Path(os.environ["APPDATA"]) / "Salt Player for Windows" / "workshop" / "data" / "com.example.netease"


def default_host_cache() -> Path:
    return Path(os.environ["APPDATA"]) / "Salt Player for Windows" / "cache" / "shared_cover"


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_tracks(db: Path):
    """只读读宿主库的 Track 行。"""
    uri = f"file:{db.as_posix()}?mode=ro"
    con = sqlite3.connect(uri, uri=True)
    try:
        cur = con.execute("SELECT id, path, size, coverRevision, album, artist, title FROM Track")
        rows = cur.fetchall()
    finally:
        con.close()
    return rows


def poisoned_entries(host_cache: Path):
    out = []
    for f in sorted(host_cache.iterdir()):
        if not f.is_file() or f.stat().st_size != MARKER_SIZE:
            continue
        if sha256_file(f).startswith(MARKER_SHA12):
            out.append(f)
    return out


def candidate_keys(path: str, size: int, rev: int, wh: int):
    """P-4 用过的三套公式族（原 `svc\ProbeCover.java:1241/1282/1286`，该类已于 0.11.10 随探针组退役删除；
    公式原文冻结在 `docs\51-探针报告-P4P5.md` 与 `harness\probe\_*.txt` 取证留档里）。"""
    yield path + f"?v=1&t={PINNED_T}&s={size}&r={rev}&w={wh}&h={wh}"
    yield path + f"?v=1&t={PINNED_T}&s={size}&r=0&w={wh}&h={wh}"
    yield path + f"?t={PINNED_T}&s={size}&w={wh}&h={wh}"


def load_report_originals(report: Path):
    """P-4 报告里形如：netease-3406368630 | keyer(pinned w/h=96 | 4350B → 标记 2074B | 写后回验=一致 | 原字节 sha256=27a5887c4c99"""
    pat = re.compile(r"(netease-\d+)\s*\|\s*keyer\(pinned w/h=(\d+)\s*\|\s*(\d+)B\s*→\s*标记\s*(\d+)B.*原字节 sha256=([0-9a-f]{12})")
    out = {}
    if not report or not report.is_file():
        return out
    for line in report.read_text(encoding="utf-8", errors="replace").splitlines():
        m = pat.search(line)
        if m:
            out[(m.group(1), int(m.group(2)))] = (int(m.group(3)), m.group(5))
    return out


def build_img_index(img_dir: Path):
    """cover\\img 下按 sha256 前 12 位建索引（值是文件路径）。"""
    idx = {}
    if not img_dir.is_dir():
        return idx
    for f in img_dir.iterdir():
        if f.is_file():
            idx.setdefault(sha256_file(f)[:12], f)
    return idx


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=default_plugin_data(), help="插件数据目录")
    ap.add_argument("--host-cache", type=Path, default=default_host_cache(), help="宿主 shared_cover 目录")
    ap.add_argument("--db", type=Path, default=None, help="宿主库（缺省 %APPDATA%\\Salt Player for Windows\\spw.db）")
    ap.add_argument("--report", type=Path, default=None, help="P-4 报告 txt（缺省自动找 harness\\logs\\p4-*.txt）")
    ap.add_argument("--apply", action="store_true", help="真的写回（缺省 dry-run）")
    args = ap.parse_args()

    db = args.db or Path(os.environ["APPDATA"]) / "Salt Player for Windows" / "spw.db"
    report = args.report
    if report is None:
        cands = sorted(Path("harness/logs").glob("p4-*.txt")) if Path("harness/logs").is_dir() else []
        report = cands[-1] if cands else None

    print(f"[1] 插件数据目录 = {args.data}")
    print(f"    宿主缓存目录 = {args.host_cache}")
    print(f"    宿主库       = {db}")
    print(f"    P-4 报告     = {report}")

    poisoned = poisoned_entries(args.host_cache)
    print(f"[2] 受污染条目（{MARKER_SIZE} B 且 sha256 前 12 = {MARKER_SHA12}）= {len(poisoned)} 个")
    if not poisoned:
        print("    无需复原。")
        return 0

    tracks = read_tracks(db)
    print(f"[3] Track 行 = {len(tracks)}")

    originals = load_report_originals(report)
    print(f"    报告里记到原字节的键 = {len(originals)} 个（其余需按专辑回查 cover\\img）")

    img_idx = build_img_index(args.data / "cover" / "img")
    print(f"[4] cover\\img 索引 = {len(img_idx)} 个哈希")

    index_json = args.data / "cover" / "cover-index.json"
    album_to_img = {}
    if index_json.is_file():
        data = json.loads(index_json.read_text(encoding="utf-8"))
        for key, rec in data.items():
            if isinstance(rec, dict) and rec.get("a") and rec.get("i"):
                album_to_img.setdefault(rec["a"], args.data / "cover" / "img" / rec["i"])
    print(f"    专辑名 → 图片 映射 = {len(album_to_img)}")

    # 反查：候选键 → 文件名
    name_map = {}
    for tid, path, size, rev, album, artist, title in tracks:
        if not path:
            continue
        for wh in SIZES:
            for k in candidate_keys(path, int(size or 0), int(rev or 0), wh):
                name_map.setdefault(hashlib.sha256(k.encode("utf-8")).hexdigest(), (tid, wh, path))

    resolved, unresolved, missing_bytes = [], [], []
    for f in poisoned:
        hit = name_map.get(f.name[:-2] if f.name.endswith((".0", ".1")) else f.name)
        if not hit:
            unresolved.append(f)
            continue
        tid, wh, path = hit
        src = None
        how = ""
        if (tid, wh) in originals:
            size, sha12 = originals[(tid, wh)]
            cand = img_idx.get(sha12)
            if cand and cand.stat().st_size == size:
                src, how = cand, f"报告原字节（{size} B, sha256={sha12}）"
        if src is None:
            album = next((r[4] for r in tracks if r[0] == tid), None)
            cand = album_to_img.get(album)
            if cand and cand.is_file():
                src, how = cand, f"专辑回查（{album}）"
        if src is None:
            missing_bytes.append((f, tid, wh))
            continue
        resolved.append((f, tid, wh, src, how))

    print(f"[5] 反查结果：可复原 {len(resolved)} / 键未识别 {len(unresolved)} / 原图缺失 {len(missing_bytes)}")
    for f, tid, wh, src, how in resolved[:10]:
        print(f"    ✔ {f.name[:16]}… ← {tid} w/h={wh} ← {src.name}（{src.stat().st_size} B，{how}）")
    for f in unresolved[:10]:
        print(f"    ✗ 未识别键：{f.name}")
    for f, tid, wh in missing_bytes[:10]:
        print(f"    ✗ 原图缺失：{f.name[:16]}… ({tid} w/h={wh})")

    if not args.apply:
        print("\n[6] dry-run：未写任何文件（加 --apply 真的复原）")
        return 0 if not unresolved and not missing_bytes else 1

    done = 0
    for f, tid, wh, src, how in resolved:
        f.write_bytes(src.read_bytes())
        if sha256_file(f) == sha256_file(src):
            done += 1
        else:
            print(f"    ⚠ 写回后校验不一致：{f.name}")
    print(f"\n[6] 已复原 {done} / {len(resolved)}；未识别 {len(unresolved)}；原图缺失 {len(missing_bytes)}")
    return 0 if done == len(resolved) and not unresolved and not missing_bytes else 1


if __name__ == "__main__":
    raise SystemExit(main())
