#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一键采证（W6 / A12）——每波收口跑一次，产出可直接粘贴的文本证据。

它只做「读」：读宿主库的**临时副本**、读插件数据目录的计数与索引、读日志文件。
不启动宿主、不拉起客户端、不写插件数据目录、不碰真库（唯一写盘位置是临时目录与本脚本
`-o` 指定的输出文件）。

采集七段：
  [1] 曲库硬门        调用 tools/smoke/db-audit.py（只读硬门），原样透传它的输出与结论
  [2] 缓存目录计数    插件数据目录各子目录的文件数 / 字节数（封面、歌词、音频流、备份…）
  [3] 封面完成度      读 cover\\cover-index.json，按「原图在不在」分成 已就绪 / 待处理 / 失败
  [4] 歌词完成度      lyric\\*.lrc 计数 + 曲库里 netease- 曲目数 + 覆盖率
  [5] 日志信号        最近 N 天日志的分级计数、ERROR/WARN 原文、关键信号最后一条
  [6] 目录去向表      每个子目录「归谁管 / 该不该归零」（--require 可点名升级为硬门）
  [7] 宿主封面缓存污染  数宿主 coil 磁盘缓存 cache\\shared_cover 的标记图残留（--require host-cache 可点名）
  结论              PASS / FAIL 汇总 + 一条快照指纹（波次之间可直接 diff）

用法：
  python tools\\smoke\\collect-evidence.py                      # 打印到控制台
  python tools\\smoke\\collect-evidence.py -o ev-w1.txt          # 写文件（同时打印摘要行）
  python tools\\smoke\\collect-evidence.py --expect-tracks 3267  # 顺带卡曲目数
  python tools\\smoke\\collect-evidence.py --copy --no-db         # 只看缓存与日志（不动真库）
  python tools\\smoke\\collect-evidence.py --days 3 --errors 50   # 多收几天日志 / 多列几条错误
  python tools\\smoke\\collect-evidence.py --json ev.json          # 额外落一份机器可读快照
  python tools\\smoke\\collect-evidence.py --compare ev-w1.json     # 与上一波快照做逐项差分
  python tools\\smoke\\collect-evidence.py --require host-cache,audio-cover   # 宿主缓存不许有标记图残留 + 旧通路必须归零

退出码：0 = PASS，1 = FAIL，2 = 参数或环境错（数据目录不存在等）。
"""
from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

# ---------------------------------------------------------------- 常量

HOST_APPDATA_DIR = Path(os.environ.get("APPDATA", "")) / "Salt Player for Windows"
DEFAULT_DATA_DIR = HOST_APPDATA_DIR / "workshop" / "data" / "com.example.netease"
DEFAULT_DB = HOST_APPDATA_DIR / "spw.db"
# 宿主 coil 磁盘缓存（列表行封面就吃这里）；B2 轮 P-4 的标记图事故写坏过它
HOST_CACHE_DIR = HOST_APPDATA_DIR / "cache" / "shared_cover"
MARKER_BYTES = 2074            # 标记图签名尺寸：这个字节数的文件极可疑
MARKER_SHA12 = "1c6ff19ecd20"  # 事故那次标记图的 sha256 前 12 位（只作参考标注，不做唯一判据）

# 采集的关键信号：日志里出现这些词说明某条管线跑过，取最后一次出现作为证据。
SIGNALS = [
    "原生同步结果",
    "封面核对",
    "[primer]",
    "[cover]",
    "去重省下",
    "[lyric]",
    "封面预热",
    "歌词预热",
    "同连接写库成功",
    "InvalidationTracker.refreshAsync",
    "档位",
    "曲目 ",
    # --- 0.11.7 起冻结的判据词形（缺行 = 对应管线还没落地/没接线）---
    "档位 请求=",          # W5：请求/实得档位成对标注（A15/D7）
    "封面失败",            # W3：失败分类行（R11）
    "失败分类",            # W4：歌词失败分类计数行
    "预热完成",            # W4：歌词预热完成汇总
    "预热开跑",            # W4：歌词预热开跑
    "邻曲保温",            # W4：邻曲保温入队（R14）
    "配置页入口",          # 宿主 on_click 留痕（guard 升级后每次点击都有）
    "写入自检未通过",      # 写库自检兜底（禁止静默失败）
]

# 配置页文件（宿主按 preference_config.json 组名明文写入插件数据目录）。
# 0.11.10：临时探针组 probe.json 已随整组退役删除（docs/00 §7/§8、docs\51-W1c探针组退役-0.11.10.md）。
PREF_FILES = [
    ("account_cfg.json", False),
    ("cache_cfg.json", False),
    ("online.json", False),
    ("config.json", False),
]

# [2] 里要计数的子目录（顺序即输出顺序）
COUNT_DIRS = [
    "cover",
    "cover/img",
    "cover/stub",
    "lyric",
    "audio",
    "audio-stream",
    "audio-cover",
    "playlist-cover",
    "db-backup",
    "logs",
]

# [6] 目录去向表：每个子目录「归谁管 / 该不该归零」。
#   expect = zero   必须为 0 文件（红线或已被取代的旧通路）
#            budget 受配置预算控制，不做绝对值判定（只看趋势）
#            keep   正常保留（清理只能由用户点对应按钮触发）
#            info   只在验收/排障时参考
DIR_OWNERS = [
    ("cover/img", "W3 封面", "keep", "原图缓存（R8 无上限，只有点「清理音乐封面缓存」才清空）"),
    ("cover/stub", "W3 封面", "keep", "占位封面桩（未播放也要出图，R7′ 靠它）"),
    ("lyric", "W4 歌词", "keep", "独立生命周期：不随音频缓存删除（D5-A），只有「清理歌词缓存」才清空"),
    ("playlist-cover", "W3 歌单卡", "keep", "歌单卡片封面源图（与 cover/ 同一条封面管线，R7）"),
    ("audio-stream", "W5 播放缓存", "budget", "受配置「播放缓存 GB」+ 切歌策略控制；预热期间必须零新增（A16）"),
    ("audio-cover", "旧 RowCover 整首音频副本", "zero",
     "R8/R7′：播放条只走零下载路线 ⇒ 此处必须归零（W3 删 RowCover 后）；"
     "非零 = 仍在整首下载音乐，属红线嫌疑"),
    ("audio", "插件音频目录（旧）", "zero", "红线：插件不得下载音乐；非零即违规"),
    ("db-backup", "W2 映射层备份", "keep", "写库前 backupOnce 备份；保留份数由 NativeLibrary 定"),
    ("logs", "W6 日志", "keep", "plugin-YYYYMMDD.log（7 天保留 / 单文件 ≤2MB）"),
    ("cover", "W3 封面容器", "info", "容器目录本身（img/ stub/ 与 cover-index.json、cover-refs.json 在内）"),
]

MAX_LINE = 400  # 单行截断长度，防止日志行把报告撑爆


# ---------------------------------------------------------------- 小工具


def out(msg: str = "") -> None:
    print(msg)


def clip(text: str, limit: int = MAX_LINE) -> str:
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


def dir_stat(path: Path) -> tuple[int, int]:
    """(文件数, 字节数)。目录不存在返回 (0, 0)。"""
    if not path.is_dir():
        return 0, 0
    files = 0
    total = 0
    for p in path.rglob("*"):
        try:
            if p.is_file():
                files += 1
                total += p.stat().st_size
            elif p.is_symlink():
                files += 1
        except OSError:
            continue
    return files, total


def mb(n: int) -> str:
    return f"{n / 1024 / 1024:.1f} MB"


def image_size(path: Path):
    """只读文件头拿 (宽, 高, 格式)；认不出返回 None。纯标准库，不依赖 PIL。"""
    try:
        with open(path, "rb") as f:
            head = f.read(4096)
            if head[:8] == b"\x89PNG\r\n\x1a\n" and len(head) >= 24:
                return (int.from_bytes(head[16:20], "big"),
                        int.from_bytes(head[20:24], "big"), "png")
            if head[:2] == b"\xff\xd8":  # JPEG：扫到 SOFn 段
                data = head + f.read(1 << 16)
                i = 2
                while i < len(data) - 9:
                    if data[i] != 0xFF:
                        i += 1
                        continue
                    marker = data[i + 1]
                    if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                        i += 2
                        continue
                    seglen = int.from_bytes(data[i + 2:i + 4], "big")
                    if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
                        return (int.from_bytes(data[i + 7:i + 9], "big"),
                                int.from_bytes(data[i + 5:i + 7], "big"), "jpg")
                    i += 2 + seglen
                return None
            if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
                if head[12:16] == b"VP8X" and len(head) >= 30:
                    return (int.from_bytes(head[24:27], "little") + 1,
                            int.from_bytes(head[27:30], "little") + 1, "webp")
                if head[12:16] == b"VP8 " and len(head) >= 30:
                    return (int.from_bytes(head[26:28], "little") & 0x3FFF,
                            int.from_bytes(head[28:30], "little") & 0x3FFF, "webp")
                return None
    except OSError:
        return None
    return None


def cover_sizes(img_dir: Path, cap: int = 4000) -> dict:
    """cover\\img 的尺寸/格式分布（A13：所有文件必须同一尺寸，建议 1200×1200）。"""
    res = {"scanned": 0, "sizes": {}, "formats": {}, "unreadable": 0}
    if not img_dir.is_dir():
        return res
    files = [p for p in img_dir.iterdir() if p.is_file()][:cap]
    for p in files:
        res["scanned"] += 1
        got = image_size(p)
        if not got:
            res["unreadable"] += 1
            continue
        w, h, fmt = got
        key = f"{w}×{h}"
        res["sizes"][key] = res["sizes"].get(key, 0) + 1
        res["formats"][fmt] = res["formats"].get(fmt, 0) + 1
    return res


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8", errors="replace"))
    except Exception:
        return None


# ---------------------------------------------------------------- [1] 曲库硬门


def run_db_audit(db_audit: Path, args: argparse.Namespace) -> dict:
    if not db_audit.is_file():
        return {"ok": False, "code": -1, "text": f"（找不到 {db_audit}）", "verdict": "SKIP"}
    cmd = [sys.executable, str(db_audit)]
    if args.db:
        cmd.append(str(args.db))
    if args.copy:
        cmd.append("--copy")
    if args.expect_tracks is not None:
        cmd += ["--expect-tracks", str(args.expect_tracks)]
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(db_audit.parent.parent.parent),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            env=env,
            timeout=180,
        )
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "code": -1, "text": f"（db-audit 调用失败：{exc}）", "verdict": "SKIP"}
    text = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode == 0:
        verdict = "PASS"
    elif proc.returncode == 1:
        verdict = "FAIL"
    elif proc.returncode == 2:
        verdict = "FAIL(库不存在/参数错)"
    else:
        verdict = f"FAIL(exit={proc.returncode})"
    return {"ok": proc.returncode == 0, "code": proc.returncode, "text": text, "verdict": verdict,
            "cmd": " ".join(cmd)}


# ---------------------------------------------------------------- [3] 封面完成度


def cover_stats(data_dir: Path) -> dict:
    cover = data_dir / "cover"
    img = cover / "img"
    stub = cover / "stub"
    res: dict = {"dir": str(cover), "exists": cover.is_dir()}

    res["img_files"], res["img_bytes"] = dir_stat(img)
    res["stub_files"], res["stub_bytes"] = dir_stat(stub)
    for name in ("cover-index.json", "cover-refs.json"):
        p = cover / name
        res[name] = p.stat().st_size if p.is_file() else None

    index = read_json(cover / "cover-index.json")
    refs = read_json(cover / "cover-refs.json")
    res["index_entries"] = len(index) if isinstance(index, dict) else None
    res["ref_entries"] = len(refs) if isinstance(refs, dict) else None

    ready = pending = failed = other = 0
    failed_samples: list[str] = []
    pending_samples: list[str] = []
    if isinstance(index, dict):
        for idx, rec in index.items():
            if not isinstance(rec, dict):
                other += 1
                continue
            name = str(rec.get("i") or "")
            album = clip(rec.get("a") or "?", 60)
            attempts = rec.get("n") or 0
            try:
                attempts = int(attempts)
            except (TypeError, ValueError):
                attempts = 0
            have = bool(name) and (img / name).is_file()
            if have:
                ready += 1
            elif attempts > 0:
                failed += 1
                if len(failed_samples) < 8:
                    failed_samples.append(f"{album} | 尝试 {attempts} 次 | {clip(rec.get('u') or '(空URL)', 70)}")
            else:
                pending += 1
                if len(pending_samples) < 5:
                    pending_samples.append(f"{album} | {clip(rec.get('u') or '(空URL)', 70)}")
    res.update(ready=ready, pending=pending, failed=failed, other=other,
               failed_samples=failed_samples, pending_samples=pending_samples)
    res["sizes"] = cover_sizes(img)
    return res


# ---------------------------------------------------------------- [4] 歌词完成度


def lyric_stats(data_dir: Path) -> dict:
    lyric = data_dir / "lyric"
    files = []
    if lyric.is_dir():
        for p in lyric.iterdir():
            try:
                if p.is_file() and p.suffix.lower() == ".lrc":
                    files.append((p.name, p.stat().st_size))
            except OSError:
                continue
    total = sum(sz for _, sz in files)
    res = {"dir": str(lyric), "exists": lyric.is_dir(), "lrc_files": len(files),
           "lrc_bytes": total, "sample": [n for n, _ in files[:5]]}
    # 不是 .lrc 的散落文件（负缓存等）
    others = []
    if lyric.is_dir():
        others = [p.name for p in lyric.iterdir() if p.is_file() and p.suffix.lower() != ".lrc"]
    res["other_files"] = others[:10]
    return res


def netease_tracks(db_path: Path | None) -> dict:
    """在真库的临时副本上数 netease- 前缀曲目（不动真库）。"""
    if db_path is None or not Path(db_path).is_file():
        return {"ok": False, "why": "库文件不存在"}
    tmp = Path(tempfile.mkdtemp(prefix="collect-evidence-"))
    try:
        for suffix in ("", "-wal", "-shm"):
            src = Path(str(db_path) + suffix)
            if src.is_file():
                shutil.copy2(src, tmp / src.name)
        con = sqlite3.connect(str(tmp / Path(db_path).name))
        try:
            row = con.execute("SELECT COUNT(*) FROM Track WHERE id LIKE 'netease-%'").fetchone()
            tracks = int(row[0]) if row else 0
            albums = None
            for col in ("album", "albumId"):  # 宿主 schema 里专辑列名可能是 album 或 albumId
                try:
                    row = con.execute(
                        f"SELECT COUNT(DISTINCT {col}) FROM Track WHERE id LIKE 'netease-%'").fetchone()
                    albums = int(row[0]) if row else 0
                    break
                except sqlite3.Error:
                    continue
            return {"ok": True, "tracks": tracks, "albums": albums}
        finally:
            con.close()
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "why": str(exc)}
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------- [7] 宿主封面缓存污染


def host_cache_stats(host_cache: Path = HOST_CACHE_DIR) -> dict:
    """宿主 coil 磁盘缓存（cache\\shared_cover）的污染指纹——只读、非递归（该目录是平铺的）。

    三项指纹（进 JSON 与快照指纹）：
      ① 文件总数 / 字节数
      ② MARKER_BYTES(2074) 字节的文件数 —— 标记图事故的签名尺寸
      ③ 这些文件的 sha256 前 12 位去重计数（事故那次是单个 `1c6ff19ecd20`）
    附带参考值：0 字节文件数（疑似空壳落盘，只提示、不判定）。
    """
    res: dict = {"dir": str(host_cache), "exists": host_cache.is_dir(), "files": 0, "bytes": 0,
                 "marker_bytes": MARKER_BYTES, "marker_files": 0, "marker_hash_count": 0,
                 "marker_hashes": {}, "marker_known": 0, "zero_files": 0,
                 "subdirs": 0, "readable": True, "why": "", "samples": []}
    if not res["exists"]:
        res["why"] = "目录不存在（宿主没起过，或缓存路径变了）"
        return res
    try:
        entries = list(host_cache.iterdir())
    except OSError as exc:
        res["readable"] = False
        res["why"] = f"读不了目录：{exc}"
        return res
    for p in entries:
        try:
            if p.is_dir():
                res["subdirs"] += 1
                continue
            if not p.is_file():
                continue
            size = p.stat().st_size
        except OSError:
            continue
        res["files"] += 1
        res["bytes"] += size
        if size == 0:
            res["zero_files"] += 1
            continue
        if size != MARKER_BYTES:
            continue
        res["marker_files"] += 1
        try:
            h = hashlib.sha256(p.read_bytes()).hexdigest()[:12]
        except OSError:
            h = "?"
        res["marker_hashes"][h] = res["marker_hashes"].get(h, 0) + 1
        if h == MARKER_SHA12:
            res["marker_known"] += 1
        if len(res["samples"]) < 5:
            res["samples"].append(f"{p.name[:16]}… {size} B sha256[:12]={h}")
    res["marker_hash_count"] = len(res["marker_hashes"])
    return res


def host_cache_restore_dryrun(repo: Path, timeout: int = 300) -> dict:
    """跑 Lead 的 `tools\\smoke\\host-cache-restore.py` **dry-run**（只读），抄它的结论行。

    它自己只读宿主库、只打印计划；本函数**不传 `--apply`**，不会写任何文件。
    找不到脚本 / 跑不起来 / 超时 ⇒ 返回 SKIP + 原因字符串，绝不抛异常（采证必须能跑完）。
    """
    tool = repo / "tools" / "smoke" / "host-cache-restore.py"
    res: dict = {"ok": False, "skip": "", "poisoned": None, "resolvable": None,
                 "unresolved": None, "missing": None, "note": "", "cmd": "", "tail": []}
    if not tool.is_file():
        res["skip"] = f"找不到 {tool}"
        return res
    cmd = [sys.executable, str(tool)]
    res["cmd"] = " ".join(cmd)
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    try:
        proc = subprocess.run(cmd, cwd=str(repo), capture_output=True, text=True,
                              encoding="utf-8", errors="replace", env=env, timeout=timeout)
    except subprocess.TimeoutExpired:
        res["skip"] = f"超时（>{timeout}s，宿主可能正在写缓存）"
        return res
    except Exception as exc:  # noqa: BLE001
        res["skip"] = f"调用失败：{exc}"
        return res
    text = (proc.stdout or "") + (proc.stderr or "")
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    res["tail"] = [clip(ln, 200) for ln in lines[-4:]]
    m = re.search(r"\[2\] 受污染条目.*?=\s*(\d+)\s*个", text)
    if m:
        res["poisoned"] = int(m.group(1))
    m = re.search(r"可复原\s*(\d+)\s*/\s*键未识别\s*(\d+)\s*/\s*原图缺失\s*(\d+)", text)
    if m:
        res["resolvable"], res["unresolved"], res["missing"] = (int(x) for x in m.groups())
    if res["poisoned"] == 0:
        res["ok"] = True
        res["note"] = "无需复原"
    elif res["poisoned"] is None:
        res["skip"] = f"输出没认出结论行（exit={proc.returncode}）"
    else:
        res["ok"] = True
        res["note"] = (f"可复原 {res['resolvable']} / 键未识别 {res['unresolved']} / "
                       f"原图缺失 {res['missing']}（dry-run，未写盘）")
    return res


# ---------------------------------------------------------------- [5] 日志信号


def log_stats(data_dir: Path, days: int, max_errors: int) -> dict:
    logs = data_dir / "logs"
    res: dict = {"dir": str(logs), "files": [], "counts": {}, "errors": [], "signals": {}}
    if not logs.is_dir():
        res["exists"] = False
        return res
    res["exists"] = True

    files = sorted((p for p in logs.glob("plugin-*.log") if p.is_file()),
                   key=lambda p: p.name)[-days:]
    counts = {"ERROR": 0, "WARN": 0, "INFO": 0, "DEBUG": 0}
    errors: list[str] = []
    signals: dict[str, tuple[str, int]] = {s: ("", 0) for s in SIGNALS}
    for p in files:
        try:
            lines = p.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        res["files"].append({"name": p.name, "bytes": p.stat().st_size, "lines": len(lines)})
        for line in lines:
            for level in counts:
                if f"[{level}" in line:  # 日志形如 "01:34:37 [ERROR] [tag] …"
                    counts[level] += 1
                    if level in ("ERROR", "WARN") and "配置页入口异常" not in line:
                        errors.append(f"{p.name}: {clip(line, 220)}")
                    break
            for sig in SIGNALS:
                if sig in line:
                    _, n = signals[sig]
                    signals[sig] = (clip(line, 220), n + 1)
    res["counts"] = counts
    res["errors"] = errors[-max_errors:]
    res["error_total"] = len(errors)
    res["signals"] = {k: {"last": v[0], "count": v[1]} for k, v in signals.items()}
    res["flags"] = sorted(p.name for p in data_dir.glob("*.flag"))
    return res


# ---------------------------------------------------------------- 波次对比


def flat_metrics(snap: dict) -> dict:
    m: dict = {}
    m["曲目数(netease-)"] = (snap.get("netease_tracks") or {}).get("tracks")
    c = snap.get("cover") or {}
    for k, label in (("img_files", "cover/img 文件"), ("stub_files", "cover/stub 文件"),
                     ("index_entries", "封面索引条目"), ("ready", "封面已就绪"),
                     ("pending", "封面待处理"), ("failed", "封面失败")):
        m[label] = c.get(k)
    m["专辑数"] = (snap.get("netease_tracks") or {}).get("albums")
    m["歌词 .lrc"] = (snap.get("lyric") or {}).get("lrc_files")
    for k, v in ((snap.get("logs") or {}).get("counts") or {}).items():
        m[f"日志 {k}"] = v
    for rel, d in (snap.get("dirs") or {}).items():
        m[f"{rel} 文件"] = (d or {}).get("files")
        m[f"{rel} 字节"] = (d or {}).get("bytes")
    hc = snap.get("host_cache") or {}
    if hc:
        m["宿主缓存文件"] = hc.get("files")
        m["宿主缓存标记图残留"] = hc.get("marker_files")
        m["宿主缓存 0 字节文件"] = hc.get("zero_files")
    rd = snap.get("host_cache_restore") or {}
    if rd.get("ok") and rd.get("poisoned"):
        m["宿主缓存待复原条目"] = rd.get("poisoned")
    m["结论"] = snap.get("verdict")
    return m


def render_compare(prev_snap: dict, prev_name: str, snap: dict) -> tuple[list[str], list[str]]:
    """拿两份 JSON 快照做逐项差分，返回 (渲染行, 警告)。"""
    a, b = flat_metrics(prev_snap), flat_metrics(snap)
    lines = [f"[6] 与上一波对比（基线：{prev_name}｜{prev_snap.get('stamp', '?')}"
             f"｜指纹 {prev_snap.get('fingerprint', '?')}｜上一波结论 {prev_snap.get('verdict') or '?'}）"]
    key_order = [k for k in list(a.keys()) + [k for k in b if k not in a] if k != "结论"]
    changed = 0
    for k in key_order:
        va, vb = a.get(k), b.get(k)
        if va == vb:
            continue
        changed += 1
        if isinstance(va, (int, float)) and isinstance(vb, (int, float)):
            delta = vb - va
            lines.append(f"    {k:<22} {va} → {vb}   ({delta:+,})")
        else:
            lines.append(f"    {k:<22} {va} → {vb}   （注意：结论/字段变化）")
    if changed == 0:
        lines.append("    （所有指标与上一波完全一致）")
    warns: list[str] = []
    as_a, as_b = a.get("audio-stream 文件"), b.get("audio-stream 文件")
    img_a, img_b = a.get("cover/img 文件"), b.get("cover/img 文件")
    if isinstance(as_a, int) and isinstance(as_b, int) and as_b > as_a:
        if isinstance(img_a, int) and isinstance(img_b, int) and img_b > img_a:
            warns.append(f"封面涨了而 audio-stream 也涨了 {as_b - as_a} 个文件"
                         "（A16 解耦存疑；若本波确实播过歌则属正常，请人工确认）")
        else:
            lines.append(f"    · audio-stream 新增 {as_b - as_a} 个文件（本波播过歌；预热解耦看封面涨的那波）")
    fl_a, fl_b = a.get("封面失败"), b.get("封面失败")
    if isinstance(fl_a, int) and isinstance(fl_b, int) and fl_b > fl_a:
        warns.append(f"封面失败条目 {fl_a} → {fl_b}（新增失败，A8 要求有分类日志可查）")
    mk_a, mk_b = a.get("宿主缓存标记图残留"), b.get("宿主缓存标记图残留")
    if isinstance(mk_a, int) and isinstance(mk_b, int) and mk_b > mk_a:
        warns.append(f"宿主封面缓存标记图残留 {mk_a} → {mk_b}（品红标记图变多：跑 "
                     "python tools\\smoke\\host-cache-restore.py --apply）")
    lines.append("")
    return lines, warns


# ---------------------------------------------------------------- [6] 目录去向表


def _norm_rel(rel: str) -> str:
    return rel.replace("\\", "/").strip("/").lower()


def require_set(require) -> set:
    """把 --require 的写法（字符串/列表，逗号、顿号、空格分隔）归一成小写集合。"""
    if isinstance(require, str):
        raw = [x for x in re.split(r"[,;、\s]+", require) if x.strip()]
    else:
        raw = list(require or ())
    return {_norm_rel(x) for x in raw}


def dir_owners_report(data_dir: Path, dirs: dict, require=None
                      ) -> tuple[list[str], list[str], list[str], dict]:
    """目录去向表：每个子目录标「归属管线 / 是否该归零」。

    返回 (渲染行, 警告, 硬失败, 机器可读数据)。`require` 里的目录名（大小写、斜杠随意）
    其「该归零」判据升级为硬门（FAIL）。
    require: 字符串（逗号/中文顿号分隔）或字符串列表均可。
    """
    if isinstance(require, str):
        raw = [x for x in re.split(r"[,;、\s]+", require) if x.strip()]
    else:
        raw = list(require or ())
    need = require_set(raw)
    lines = ["[6] 目录去向表（归属管线 / 是否该归零）"]
    warns: list[str] = []
    hard: list[str] = []
    data: dict = {}
    badge = {"zero": ("✓ 已归零", "✗ 未归零"), "budget": ("· 受预算控制", "· 受预算控制"),
             "keep": ("· 保留", "· 保留"), "info": ("· 参考", "· 参考")}
    for rel, owner, expect, note in DIR_OWNERS:
        n, b = dirs.get(rel, (0, 0))
        ok = (n == 0 and b == 0) if expect == "zero" else True
        mark = badge[expect][0 if ok else 1]
        lines.append(f"    {rel:<15} {owner:<22} {n:>6} 文件 {mb(b):>10}   {mark}")
        lines.append(f"        └ {note}")
        data[rel] = {"owner": owner, "expect": expect, "files": n, "bytes": b, "ok": ok,
                     "note": note}
        if not ok:
            msg = f"{rel}\\ 未归零（{n} 文件 / {mb(b)}）：{note}"
            if _norm_rel(rel) in need:
                hard.append(msg + "　【--require 硬门】")
            else:
                warns.append(msg)
    known = {_norm_rel(r).split("/")[0] for r, *_ in DIR_OWNERS}
    unknown = sorted(p.name + "\\" for p in data_dir.glob("*")
                     if p.is_dir() and p.name.lower() not in known)
    if unknown:
        lines.append(f"    未登记的子目录（请在 DIR_OWNERS 补一行，说明归属与去向）：{', '.join(unknown)}")
        warns.append(f"有 {len(unknown)} 个未登记子目录：{', '.join(unknown)}")
    data["_unknown_dirs"] = unknown
    for rel, rec in data.items():
        if rel != "_unknown_dirs" and rec.get("expect") == "zero" and not rec.get("ok"):
            lines.append(f"    ★ A9/R8 判据：`{rel}` 应归零；采证 JSON 里的 dir_owners[\"{rel}\"].ok = false"
                         " 可直接当硬门用。")
    # --require 点名项自检：不认识的项绝不能静默通过（否则写错一个字 = 假绿）
    valid = {_norm_rel(r) for r, *_ in DIR_OWNERS}
    valid |= {_norm_rel(r).split("/")[0] for r, *_ in DIR_OWNERS}
    valid.add("host-cache")
    for x in sorted(need - valid):
        msg = (f"--require 点名项无法识别：{x}（可用：目录名（见 [6] 表，如 audio-cover、audio）"
               "或 host-cache）⇒ 该项判据没生效，别把它当成通过")
        lines.append(f"    ✗ {msg}")
        warns.append(msg)
    lines.append("")
    lines.append("    配置页文件（宿主按 preference_config.json 的组名明文写入；密码类不走这里）：")
    for name, optional in PREF_FILES:
        p = data_dir / name
        exists = p.is_file()
        size = p.stat().st_size if exists else 0
        mark = "✓" if exists else ("· 可缺（探针组，验收后整组删）" if optional else "✗ 缺")
        lines.append(f"        {name:<18} {mark:<24} {size:>8,} B")
        if not exists and not optional:
            warns.append(f"配置页文件缺失：{name}（用户打开配置页后宿主就会生成；已打开仍缺 ⇒ 接线没生效）")
    data["_pref_files"] = {name: (data_dir / name).is_file() for name, _ in PREF_FILES}
    lines.append("")
    return lines, warns, hard, data


# ---------------------------------------------------------------- 渲染


def render(args: argparse.Namespace, db: dict, dirs: dict, cover: dict, lyric: dict,
           ntracks: dict, logd: dict, data_dir: Path, version: str,
           prev: dict | None = None, prev_name: str = "",
           hc: dict | None = None, rd: dict | None = None) -> tuple[str, str, dict]:
    stamp = _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    L: list[str] = []
    add = L.append
    fails: list[str] = []
    warns: list[str] = []

    add("=" * 78)
    add(f"一键采证 · {stamp}")
    add("=" * 78)
    add(f"插件版本   = {version}")
    add(f"数据目录   = {data_dir}")
    add(f"宿主库     = {args.db or DEFAULT_DB}")
    add(f"宿主缓存   = {(hc or {}).get('dir') or HOST_CACHE_DIR}"
        + ("　（--no-restore：不跑 dry-run）" if getattr(args, "no_restore", False) else ""))
    add(f"采集范围   = 日志最近 {args.days} 天 / 最多 {args.errors} 条 ERROR·WARN 原文"
        + ("；--no-db 已跳过曲库段" if args.no_db else "") + ("；--copy 副本模式" if args.copy else ""))
    add("")

    # ---- [1]
    add("[1] 曲库硬门（db-audit.py，只读）")
    if args.no_db:
        add("    （--no-db：本段已跳过）")
    else:
        add(f"    $ {db.get('cmd', 'db-audit.py')}")
        for line in (db["text"] or "").splitlines():
            add(f"    {line}")
        add(f"    → 库硬门：{db['verdict']}")
        if not db["ok"] and db["verdict"] != "SKIP":
            fails.append(f"库硬门 {db['verdict']}")
        elif db["verdict"] == "SKIP":
            warns.append("库硬门未执行")
    add("")

    # ---- [2]
    add("[2] 缓存目录计数（文件数 / 字节）")
    for rel in COUNT_DIRS:
        n, b = dirs.get(rel, (0, 0))
        flag = "" if n or b else "   （空）"
        add(f"    {rel:<16} {n:>7} 文件 {b:>14,} B  {mb(b):>10}{flag}")
    add("")
    add(f"    数据目录散落文件：{', '.join(sorted(p.name for p in data_dir.glob('*') if p.is_file())) or '(无)'}")
    add(f"    探针开关(*.flag)：{', '.join(logd.get('flags', [])) or '(无)'}")
    as_files = dirs.get("audio-stream", (0, 0))[0]
    au_files = dirs.get("audio", (0, 0))[0]
    add(f"    A16 解耦基线：audio-stream\\ = {as_files} 文件，audio\\ = {au_files} 文件"
        "（预热期间这两处必须零新增；与上一波快照对比即可作证）")
    add("")

    # ---- [3]
    add("[3] 封面完成度（cover\\cover-index.json 逐条核对原图是否在 img\\ 里）")
    add(f"    索引条目 = {cover.get('index_entries')}   album 引用条目 = {cover.get('ref_entries')}")
    add(f"    img\\ = {cover['img_files']} 文件 / {mb(cover['img_bytes'])}"
        f"   stub\\ = {cover['stub_files']} 文件 / {mb(cover['stub_bytes'])}")
    add(f"    已就绪（原图在）      = {cover['ready']}")
    add(f"    待处理（从未尝试且无图）= {cover['pending']}")
    add(f"    失败（尝试过仍无图）    = {cover['failed']}")
    if cover["other"]:
        add(f"    索引里结构异常条目    = {cover['other']}")
    if cover["failed_samples"]:
        add("    失败样例：")
        for s in cover["failed_samples"]:
            add(f"      - {s}")
    if cover["pending_samples"]:
        add("    待处理样例：")
        for s in cover["pending_samples"]:
            add(f"      - {s}")
    if cover.get("index_entries") is None and cover["exists"]:
        warns.append("封面索引读不出（cover-index.json 缺失或结构变化）")
    if cover["failed"]:
        warns.append(f"封面有 {cover['failed']} 条失败（尝试过仍无原图）")
    if cover["img_files"] != cover["stub_files"]:
        warns.append(f"img\\{cover['img_files']} 与 stub\\{cover['stub_files']} 张数不等")
    sz = cover.get("sizes") or {}
    if sz.get("scanned"):
        dist = "、".join(f"{k}×{v} 张" for k, v in
                       sorted(sz["sizes"].items(), key=lambda kv: -kv[1])[:6])
        add(f"    尺寸分布（A13：必须全部同一尺寸，目标 1200×1200）：{dist or '(无)'}")
        add(f"    格式分布：{'、'.join(f'{k} {v}' for k, v in sz['formats'].items())}"
            f"   读不出尺寸 = {sz['unreadable']}")
        if len(sz["sizes"]) > 1:
            warns.append(f"封面尺寸不唯一：{dist}")
        if sz["unreadable"]:
            warns.append(f"{sz['unreadable']} 张封面读不出尺寸（格式异常）")
    add("")

    # ---- [4]
    add("[4] 歌词完成度")
    add(f"    lyric\\ = {lyric['lrc_files']} 个 .lrc / {mb(lyric['lrc_bytes'])}")
    if lyric["other_files"]:
        add(f"    lyric\\ 里的非 .lrc 文件：{', '.join(lyric['other_files'])}")
    if ntracks.get("ok"):
        base = ntracks["tracks"]
        cover_pct = (lyric["lrc_files"] / base * 100) if base else 0.0
        add(f"    曲库里 netease- 曲目 = {base} 首"
            + (f"（涉及专辑 {ntracks['albums']} 张）" if ntracks.get("albums") is not None else ""))
        add(f"    歌词覆盖 = {lyric['lrc_files']}/{base} = {cover_pct:.2f}%"
            "（歌词按需落盘，没播过的曲目本来就没有 .lrc，看趋势不看绝对值）")
        if cover.get("index_entries") is not None and base:
            add(f"    封面索引/曲目 = {cover['index_entries']}/{base}")
        albums = ntracks.get("albums")
        if albums:
            ok = "✓" if cover["img_files"] < albums else "✗（同图共享没发生）"
            add(f"    A13 去重：cover\\img {cover['img_files']} 张 < 专辑数 {albums} → {ok}")
    else:
        add(f"    （曲目基数未取到：{ntracks.get('why')}）")
        warns.append("歌词覆盖率基数未取到")
    add("")

    # ---- [5]
    add(f"[5] 日志信号（{logd.get('dir')}）")
    if not logd.get("exists"):
        add("    （logs\\ 不存在）")
        warns.append("logs\\ 不存在")
    else:
        for f in logd["files"]:
            add(f"    {f['name']}  {f['bytes']:>10,} B  {f['lines']:>7} 行")
        c = logd["counts"]
        add(f"    分级计数：ERROR {c['ERROR']} / WARN {c['WARN']} / INFO {c['INFO']} / DEBUG {c['DEBUG']}")
        add(f"    错误原文（共 {logd.get('error_total', 0)} 条，列最近 {len(logd['errors'])} 条）：")
        if logd["errors"]:
            for line in logd["errors"]:
                add(f"      ! {line}")
        else:
            add("      （无 ERROR / WARN 行：本轮没有可查的失败）")
        add("    关键信号最后一条：")
        for sig, v in logd["signals"].items():
            if v["count"]:
                add(f"      · {sig} ×{v['count']} → {v['last']}")
            else:
                add(f"      · {sig} ×0（本次窗口内没出现）")
        if c["ERROR"]:
            warns.append(f"日志里有 {c['ERROR']} 条 ERROR（失败是否已可见，对着上面原文核）")
    add("")

    # ---- [7] 宿主封面缓存污染（宿主 coil 磁盘缓存；只读，非递归）
    hc = hc or {}
    rd = rd or {}
    need = require_set(getattr(args, "require", None))
    # 快照先立骨架（verdict / warns / fingerprint 在结论算完后回填），波次对比直接吃它。
    snapshot = {"stamp": stamp, "version": version, "data_dir": str(data_dir),
                "db": {k: v for k, v in db.items() if k != "text"}, "cover": cover,
                "lyric": lyric, "netease_tracks": ntracks,
                "host_cache": hc, "host_cache_restore": rd,
                "logs": {**{k: v for k, v in logd.items() if k != "files"},
                         "files": logd.get("files", []), "flags": logd.get("flags", [])},
                "dirs": {k: {"files": v[0], "bytes": v[1]} for k, v in dirs.items()},
                "verdict": "", "warns": [], "fails": [], "fingerprint": "",
                "db_audit_text": db.get("text", "")}

    # ---- [6] 目录去向表
    dir_lines, dir_warns, dir_fails, dir_data = dir_owners_report(data_dir, dirs,
                                                                   getattr(args, "require", None))
    snapshot["dir_owners"] = dir_data
    L.extend(dir_lines)
    warns.extend(dir_warns)
    fails.extend(dir_fails)

    # ---- [7] 宿主封面缓存污染（宿主 coil 磁盘缓存；只读，非递归；排在 [6] 之后保持段号升序）
    add("[7] 宿主封面缓存污染（宿主 coil 磁盘缓存 cache\\shared_cover；只读自查）")
    if not hc.get("exists"):
        add(f"    目录 = {hc.get('dir', HOST_CACHE_DIR)}")
        add(f"    （读不到：{hc.get('why') or '目录不存在'}）")
        warns.append("宿主封面缓存目录不存在：宿主没起过，或缓存路径变了（本段无数据）")
    elif not hc.get("readable", True):
        add(f"    目录 = {hc.get('dir')}")
        add(f"    （读不了：{hc.get('why')}）")
        warns.append(f"宿主封面缓存读不了：{hc.get('why')}")
    else:
        add(f"    目录 = {hc['dir']}")
        add(f"    文件总数 = {hc['files']}（其中子目录 {hc['subdirs']} 个） / {mb(hc['bytes'])}")
        add(f"    标记图残留（{hc['marker_bytes']} 字节 = 标记图签名尺寸）= {hc['marker_files']} 个"
            f"    sha256[:12] 去重 = {hc['marker_hash_count']} 种")
        for h, n in sorted(hc["marker_hashes"].items(), key=lambda kv: -kv[1])[:6]:
            tag = "　← 事故签名（B2 轮 P-4 标记图）" if h == MARKER_SHA12 else ""
            add(f"        · sha256[:12] = {h} × {n}{tag}")
        for s in hc.get("samples", []):
            add(f"        残留样例：{s}")
        if hc["zero_files"]:
            add(f"    0 字节文件 = {hc['zero_files']} 个（参考值：疑似空壳落盘，需人判；不计入硬门）")
        if hc["marker_files"]:
            msg = (f"宿主封面缓存有 {hc['marker_files']} 个标记图残留（{hc['marker_bytes']} 字节）："
                   "宿主列表行会显示品红标记图，且宿主自己不会重新请求 ⇒ "
                   "跑 python tools\\smoke\\host-cache-restore.py --apply 原位复原")
            if "host-cache" in need:
                fails.append(msg + "　【--require 硬门】")
            else:
                warns.append(msg)
        elif "host-cache" in need:
            add("    ✓ --require host-cache 硬门：标记图残留 = 0（宿主缓存干净）")
    add("    host-cache-restore.py dry-run（Lead 的工具，只读：不传 --apply、不写宿主缓存）：")
    if rd.get("ok"):
        if rd.get("poisoned"):
            add(f"        受污染条目 = {rd['poisoned']}；可复原 {rd['resolvable']} / "
                f"键未识别 {rd['unresolved']} / 原图缺失 {rd['missing']}")
        else:
            add(f"        受污染条目 = 0（{rd.get('note') or '无需复原'}）")
    else:
        add(f"        SKIP：{rd.get('skip') or '未运行'}")
    for line in (rd.get("tail") or [])[-3:]:
        add(f"        │ {line}")
    add("")

    # ---- [8] 波次对比（可选）/ [9] 结论
    if prev:
        cmp_lines, cmp_warns = render_compare(prev, prev_name, snapshot)
        L.extend(cmp_lines)
        warns.extend(cmp_warns)
    add(f"[{'9' if prev else '8'}] 结论")
    if args.strict:
        fails.extend(warns)
        warns = []
    fingerprint = hashlib.sha256(
        json.dumps({"db": db["verdict"], "cover": [cover["img_files"], cover["stub_files"],
                                                  cover["index_entries"], cover["ready"],
                                                  cover["pending"], cover["failed"]],
                    "lyric": lyric["lrc_files"], "logs": logd.get("counts", {}),
                    "host_cache": [hc.get("files"), hc.get("marker_files"),
                                   hc.get("marker_hash_count")],
                    "tracks": ntracks.get("tracks")}, sort_keys=True, ensure_ascii=False)
        .encode("utf-8")).hexdigest()[:12]
    if fails:
        verdict = "FAIL"
        for f in fails:
            add(f"    ✗ {f}")
    else:
        verdict = "PASS"
        add("    ✓ 库硬门、目录计数、封面/歌词完成度、日志扫描、宿主封面缓存全部通过")
    for w in warns:
        add(f"    ! {w}（不阻断；--strict 可升级为 FAIL）")
    add(f"    结论：{verdict}   快照指纹：{fingerprint}")
    add("=" * 78)
    L.insert(2, f"结论：{verdict}")  # 报告头部也放一行，方便 tail/grep
    body = "\n".join(L)

    snapshot.update(verdict=verdict, warns=warns, fails=fails, fingerprint=fingerprint)
    return body, verdict, snapshot


# ---------------------------------------------------------------- main


def main(argv: list[str] | None = None) -> int:
    # 先修 stdout/stderr 编码：Windows 控制台默认 GBK，会把中文与 -h 帮助打成乱码。
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:  # noqa: BLE001
            pass

    ap = argparse.ArgumentParser(
        prog="collect-evidence.py",
        description="一键采证：曲库硬门 + 缓存目录 + 封面/歌词完成度 + 日志信号（W6/A12）",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-o", "--out", metavar="FILE", help="把报告写到文件（缺省打印到控制台）")
    ap.add_argument("--data", metavar="DIR", help=f"插件数据目录（缺省 {DEFAULT_DATA_DIR}）")
    ap.add_argument("--host-cache", metavar="DIR",
                    help=f"宿主 coil 缓存目录（缺省 {HOST_CACHE_DIR}）")
    ap.add_argument("--db", metavar="FILE", help=f"宿主库路径（缺省 {DEFAULT_DB}）")
    ap.add_argument("--expect-tracks", type=int, metavar="N", help="透传给 db-audit：曲目数必须等于 N")
    ap.add_argument("--copy", action="store_true", help="透传给 db-audit：在副本上审计（跳过 Album/Artist 检查）")
    ap.add_argument("--no-db", action="store_true", help="完全跳过曲库段（不跑 db-audit、不读库）")
    ap.add_argument("--days", type=int, default=2, metavar="N", help="收最近 N 天的日志文件（缺省 2）")
    ap.add_argument("--errors", type=int, default=30, metavar="N", help="最多列 N 条 ERROR/WARN 原文（缺省 30）")
    ap.add_argument("--strict", action="store_true", help="把警告升级为 FAIL（封面失败、日志 ERROR 等）")
    ap.add_argument("--require", metavar="DIR[,DIR…]",
                    help="把指定项的判据升级为硬门 FAIL（目录：如 --require audio-cover,audio；"
                         "或 host-cache = 宿主封面缓存不许有标记图残留）")
    ap.add_argument("--no-restore", action="store_true",
                    help="跳过 [7] 里 host-cache-restore.py 的 dry-run（只数宿主缓存，不调子进程）")
    ap.add_argument("--compare", metavar="PREV.json",
                    help="与上一波用 --json 落下的快照比差分（看封面涨了多少、audio-stream 有没有跟着涨）")
    ap.add_argument("--json", metavar="FILE", help="额外落一份机器可读 JSON 快照")
    ap.add_argument("-q", "--quiet", action="store_true", help="写文件时不再回显整份报告")
    args = ap.parse_args(argv)

    repo = Path(__file__).resolve().parent.parent.parent
    data_dir = Path(args.data) if args.data else DEFAULT_DATA_DIR
    db_path = Path(args.db) if args.db else DEFAULT_DB

    version = "?"
    try:
        pj = json.loads((repo / "project.json").read_text(encoding="utf-8"))
        version = f"{pj.get('version')}（{pj.get('name', '')}）"
    except Exception:  # noqa: BLE001
        pass

    out(f"采证中：{data_dir}")
    if not data_dir.is_dir():
        out(f"结论：FAIL   数据目录不存在：{data_dir}")
        out("提示：先由 Lead 跑一轮真机（装插件 + 起宿主），或显式 --data 指向别处。")
        return 2

    db_res = ({"ok": False, "code": -1, "text": "（--no-db）", "verdict": "SKIP", "cmd": "-"}
              if args.no_db else run_db_audit(repo / "tools" / "smoke" / "db-audit.py", args))

    dirs = {rel: dir_stat(data_dir / Path(rel)) for rel in COUNT_DIRS}
    try:
        cover = cover_stats(data_dir)
    except Exception as exc:  # noqa: BLE001
        cover = {"dir": "", "exists": False, "img_files": 0, "img_bytes": 0, "stub_files": 0,
                 "stub_bytes": 0, "index_entries": None, "ref_entries": None, "ready": 0,
                 "pending": 0, "failed": 0, "other": 0, "failed_samples": [], "pending_samples": [],
                 "error": str(exc)}
    lyric = lyric_stats(data_dir)
    ntracks = {"ok": False, "why": "（--no-db）"} if args.no_db else netease_tracks(db_path)
    logd = log_stats(data_dir, max(1, args.days), max(0, args.errors))
    hc = host_cache_stats(Path(args.host_cache) if args.host_cache else HOST_CACHE_DIR)
    rd = ({"ok": False, "skip": "--no-restore", "poisoned": None}
          if args.no_restore else host_cache_restore_dryrun(repo))

    prev = read_json(Path(args.compare)) if args.compare else None
    if args.compare and prev is None:
        out(f"提示：--compare 的基线读不出来（{args.compare}），本波不做差分。")

    body, verdict, snapshot = render(args, db_res, dirs, cover, lyric, ntracks, logd,
                                     data_dir, version, prev, str(args.compare or ""), hc, rd)

    if args.out:
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(body + "\n", encoding="utf-8")
        out(f"报告已写入：{out_path.resolve()}")
        if not args.quiet:
            out()
            out(body)
    else:
        out()
        out(body)

    if args.json:
        jp = Path(args.json)
        jp.parent.mkdir(parents=True, exist_ok=True)
        jp.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        out(f"JSON 快照已写入：{jp.resolve()}")

    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
