#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""封面层专项审计（W3 / R9 / R11 / R8 / A13）——只读，可反复跑。

和 `collect-evidence.py` 的分工：那是**波次总采证**（曲库 + 四条管线 + 宿主缓存），
本脚本只钻封面这一层、把 A13/R9/Q1 的三条判据做成可复核的机器结论：

  [1] 目录计数        cover\\ 各子树（img / stub / thumb / 根散图）文件数与字节数
  [2] 尺寸分布(A13)   img\\ 每张图读**文件头**（PNG IHDR / JPEG SOFn）解真实像素
                      => 期望恰好一行 `1200×1200 ×N`；其它尺寸逐条列出
  [3] 内容去重(R9)    文件名=内容 sha256（我方命名规则）=> 文件名去重率；
                      另对全部图**真算 sha256** => 同内容多副本（`--no-hash` 可跳过，只数名字）
  [4] 索引三态(A8)    读 cover\\cover-index.json / cover-refs.json => 已就绪 / 待处理 / 失败
  [5] 失败分类(R11)   日志里 `[cover] 封面失败[<分类>，K/N]` 按分类计数 + `封面放弃` 计数
                      + 最后一条 `[cover] 封面缓存：…` 完成度行（这条才是运行时权威）
  [6] 判据            按 --require 逐条给 PASS/FAIL（默认 img-size,dedup,no-fail）
  结论               PASS / FAIL 汇总 + 一条可 diff 的指纹

只读纪律：不启动宿主、不装包、不写插件数据目录；唯一写盘 = `-o` / `--json` 指定的输出文件。

用法：
  python tools\\smoke\\cover-audit.py                          # 真人日志 + 真机数据目录，打印到控制台
  python tools\\smoke\\cover-audit.py -o build\\cover-audit.txt
  python tools\\smoke\\cover-audit.py --data DIR --logs DIR      # 指到离线实验室沙箱里去看
  python tools\\smoke\\cover-audit.py --expect-albums 2092 --max-fail 0
  python tools\\smoke\\cover-audit.py --require img-size,dedup,index,no-fail,stub
  python tools\\smoke\\cover-audit.py --no-hash --days 3 --json build\\cover.json

退出码：0 = 全部判据 PASS，1 = 有判据 FAIL，2 = 参数/环境错（目录不存在等）。
"""
from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import os
import re
import sys
from pathlib import Path

# Windows 控制台默认 GBK：先把三条流改成 UTF-8（与 db-audit / lyric-audit / collect-evidence 同规矩），
# 认不出的字符降级不崩。必须在任何 print 之前执行。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# ---------------------------------------------------------------- 常量

HOST_APPDATA_DIR = Path(os.environ.get("APPDATA", "")) / "Salt Player for Windows"
DEFAULT_DATA_DIR = HOST_APPDATA_DIR / "workshop" / "data" / "com.example.netease"
DEFAULT_LOGS_DIR = DEFAULT_DATA_DIR / "logs"

SIZE_EXPECT = 1200                  # Q1/A13：落盘图唯一尺寸
IMG_EXT = {".png", ".jpg", ".jpeg", ".webp"}
STUB_MARK = "cover-stub-"           # CoverArt.STUB_PREFIX

# 关贸词形（冻结，勿改）：`[cover] 封面失败[格式异常，1/5]：…`
RE_FAIL = re.compile(r"\[cover\]\s*封面失败\[([^，,\]]+)[，,]\s*(\d+)\s*/\s*(\d+)\]")
RE_GIVEUP = re.compile(r"\[cover\]\s*封面放弃")
RE_STATS = re.compile(r"\[cover\]\s*封面缓存：(.*)$")
RE_DONE = re.compile(r"\[cover\]\s*完成\s*(\d+)\s*张\s*/\s*去重省下\s*(\d+)\s*次")
RE_STUB = re.compile(r"\[cover\]\s*封面桩\s+(\S+)")
# `封面缓存：已就绪 12 / 待处理 3 / 失败 1（分类 格式异常 1） ｜ img 15 张 …`
RE_READY = re.compile(r"已就绪\s*(\d+)")
RE_PENDING = re.compile(r"待处理\s*(\d+)")
RE_FAILED = re.compile(r"失败\s*(\d+)")


def fmt_bytes(n: int) -> str:
    if n >= 1024 * 1024 * 1024:
        return f"{n / 1024 / 1024 / 1024:.2f} GB"
    if n >= 1024 * 1024:
        return f"{n / 1024 / 1024:.1f} MB"
    if n >= 1024:
        return f"{n / 1024:.0f} KB"
    return f"{n} B"


# ---------------------------------------------------------------- 文件头解尺寸（不依赖 PIL）

def _be32(b: bytes, at: int) -> int:
    return int.from_bytes(b[at:at + 4], "big")


def _be16(b: bytes, at: int) -> int:
    return int.from_bytes(b[at:at + 2], "big")


def head_size(path: Path) -> tuple[int, int] | None:
    """只读文件头拿真实像素：PNG IHDR / JPEG SOFn。认不出返回 None（绝不猜）。"""
    try:
        with path.open("rb") as f:
            b = f.read(64 * 1024)
    except OSError:
        return None
    if len(b) < 24:
        return None
    if b[0:4] == b"\x89PNG" and b[12:16] == b"IHDR":
        return _be32(b, 16), _be32(b, 20)
    if b[0] == 0xFF and b[1] == 0xD8:                      # JPEG：逐段找 SOFn
        i = 2
        while i + 9 < len(b):
            if b[i] != 0xFF:
                i += 1
                continue
            m = b[i + 1]
            if m == 0xFF or m == 0x01 or 0xD0 <= m <= 0xD7:  # 填充 / TEM / RSTn：无长度段
                i += 2
                continue
            if m in (0xD9, 0xDA):                           # EOI / 进扫描数据仍未见表头
                return None
            ln = _be16(b, i + 2)
            if ln < 2:
                return None
            if 0xC0 <= m <= 0xCF and m not in (0xC4, 0xC8, 0xCC):
                return _be16(b, i + 7), _be16(b, i + 5)     # 宽在前、高在后
            i += 2 + ln
    return None


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    try:
        with path.open("rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
    except OSError:
        return ""
    return h.hexdigest()


def walk(root: Path) -> list[Path]:
    out: list[Path] = []
    if not root.is_dir():
        return out
    for dirpath, _dirnames, filenames in os.walk(root):
        for fn in filenames:
            out.append(Path(dirpath) / fn)
    return out


# ---------------------------------------------------------------- 日志

def log_files(logs_dir: Path, days: int) -> list[Path]:
    if not logs_dir.is_dir():
        return []
    cut = (_dt.date.today() - _dt.timedelta(days=max(0, days - 1))).strftime("%Y%m%d")
    out = []
    for p in sorted(logs_dir.glob("plugin-*.log*")):
        m = re.search(r"plugin-(\d{8})", p.name)
        if m and m.group(1) >= cut:
            out.append(p)
    return out


def scan_logs(paths: list[Path]) -> dict:
    """只做正则统计：失败分类 / 放弃 / 完成度行 / 预热行。"""
    kinds: dict[str, int] = {}
    giveup = 0
    stub_lines: list[str] = []
    stats_last: str | None = None
    done_last: tuple[int, int] | None = None
    ready_last: str | None = None
    attempts_max = 0
    for p in paths:
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for line in text.splitlines():
            m = RE_FAIL.search(line)
            if m:
                kinds[m.group(1).strip()] = kinds.get(m.group(1).strip(), 0) + 1
                attempts_max = max(attempts_max, int(m.group(2)))
            if RE_GIVEUP.search(line):
                giveup += 1
            m = RE_STATS.search(line)
            if m:
                stats_last = m.group(1).strip()
            m = RE_DONE.search(line)
            if m:
                done_last = (int(m.group(1)), int(m.group(2)))
            if RE_STUB.search(line):
                stub_lines.append(line.strip())
            if "封面预热" in line or "封面投递" in line:
                ready_last = line.strip()
    parsed: dict[str, int | None] = {"已就绪": None, "待处理": None, "失败": None}
    if stats_last:
        for key, rx in (("已就绪", RE_READY), ("待处理", RE_PENDING), ("失败", RE_FAILED)):
            m = rx.search(stats_last)
            if m:
                parsed[key] = int(m.group(1))
    return {
        "files": [str(p) for p in paths],
        "fail_kinds": kinds,
        "giveup": giveup,
        "attempts_max": attempts_max,
        "stats_last": stats_last,
        "stats_parsed": parsed,
        "done_last": done_last,
        "stub_last": stub_lines[-1] if stub_lines else None,
        "stub_lines": len(stub_lines),
        "prime_last": ready_last,
    }


# ---------------------------------------------------------------- 主流程

def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(add_help=True, description="封面层专项只读审计（W3）")
    ap.add_argument("--data", default=str(DEFAULT_DATA_DIR), help="插件数据目录（含 cover\\）")
    ap.add_argument("--logs", default=None, help="日志目录（默认 <data>\\logs）")
    ap.add_argument("--days", type=int, default=1, help="扫最近几天的日志（默认 1）")
    ap.add_argument("--size", type=int, default=SIZE_EXPECT, help="期望的唯一尺寸（默认 1200）")
    ap.add_argument("--expect-albums", type=int, default=None, help="专辑数（去重判据的上界）")
    ap.add_argument("--max-fail", type=int, default=0, help="可接受的失败张数（默认 0）")
    ap.add_argument("--require", default="img-size,dedup,no-fail",
                    help="点名硬门：img-size,dedup,index,no-fail,stub（默认前三项里的 img-size,dedup,no-fail）")
    ap.add_argument("--no-hash", action="store_true", help="跳过全量 sha256（只按文件名判重，快）")
    ap.add_argument("--list-others", type=int, default=20, help="非期望尺寸最多列几条")
    ap.add_argument("-o", "--out", default=None, help="把报告另写一份到该文件")
    ap.add_argument("--json", default=None, help="额外落一份机器可读快照")
    args = ap.parse_args(argv)

    data = Path(args.data)
    logs = Path(args.logs) if args.logs else data / "logs"
    cover = data / "cover"
    if not cover.is_dir():
        print(f"[ERR] 封面目录不存在：{cover}")
        print("      真机请确认宿主装过插件并同步过；离线实验室请用 --data <沙箱>\\appdata\\...\\com.example.netease")
        return 2

    lines: list[str] = []
    push = lines.append

    def banner(title: str) -> None:
        push("")
        push(title)

    push(f"封面层审计 · {_dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    push(f"数据目录：{cover}")
    push(f"日志目录：{logs}（最近 {args.days} 天）")

    # ---- [1] 目录计数
    banner("[1] 目录计数")
    subs = {"img（原图，A13 只看这里）": cover / "img",
            "stub（宿主可读的 flac 桩）": cover / "stub",
            "thumb（面板缩略图）": cover / "thumb"}
    cover_files = walk(cover)
    counts: dict[str, dict] = {}
    for label, d in subs.items():
        fs = [p for p in cover_files if p.parent == d]
        counts[label.split("（")[0]] = {"dir": str(d), "files": len(fs),
                                      "bytes": sum(p.stat().st_size for p in fs if p.exists())}
        push(f"  {label:<24} {len(fs):>5} 文件 / {fmt_bytes(counts[label.split('（')[0]]['bytes'])}")
    roots = [p for p in cover_files if p.parent == cover]
    ids = [p for p in cover_files if p.name.startswith(("cover-index", "cover-refs"))]
    counts["root"] = {"files": len(roots), "bytes": sum(p.stat().st_size for p in roots if p.exists())}
    counts["index"] = {"files": len(ids), "bytes": sum(p.stat().st_size for p in ids if p.exists())}
    push(f"  {'cover\\ 根散图':<21} {len(roots):>5} 文件 / {fmt_bytes(counts['root']['bytes'])}"
         f"（0.11.7 前的旧面板 PNG => clearAll 会清）")
    push(f"  {'索引文件':<24} {len(ids):>5} 文件 / {fmt_bytes(counts['index']['bytes'])}")
    total_bytes = sum(p.stat().st_size for p in cover_files if p.exists())
    push(f"  合计：{len(cover_files)} 文件 / {fmt_bytes(total_bytes)}")

    # ---- [2] 尺寸分布（A13）
    banner(f"[2] 尺寸分布（A13：期望单一行 {args.size}×{args.size}）")
    imgs = sorted(p for p in (cover / "img").glob("*") if p.is_file() and p.suffix.lower() in IMG_EXT)
    dist: dict[str, int] = {}
    unknown: list[str] = []
    others: list[str] = []
    for p in imgs:
        wh = head_size(p)
        if wh is None:
            unknown.append(p.name)
            continue
        key = f"{wh[0]}×{wh[1]}"
        dist[key] = dist.get(key, 0) + 1
        if wh != (args.size, args.size):
            others.append(f"{key}  {p.name}  {fmt_bytes(p.stat().st_size)}")
    fmt: dict[str, int] = {}
    for p in imgs:
        k = p.suffix.lower().lstrip(".")
        fmt[k] = fmt.get(k, 0) + 1
    push(f"  img 张数：{len(imgs)}（{', '.join(f'{k} {v}' for k, v in sorted(fmt.items())) or '空'}）")
    for key, n in sorted(dist.items(), key=lambda kv: (-kv[1], kv[0])):
        mark = "[OK] 单一行" if key == f"{args.size}×{args.size}" else "[!!] 非期望"
        push(f"  {key:<12} ×{n:<5} {mark}")
    if unknown:
        push(f"  [!!] 读不出尺寸：{len(unknown)} 张 —— {', '.join(unknown[:5])}{' …' if len(unknown) > 5 else ''}")
    if others:
        push(f"  [!!] 非 {args.size} 尺寸清单（最多 {args.list_others} 条）：")
        for row in others[:args.list_others]:
            push(f"      {row}")
        if len(others) > args.list_others:
            push(f"      …另有 {len(others) - args.list_others} 条")
    size_ok = bool(imgs) and not others and not unknown
    push(f"  => {'PASS' if size_ok else 'FAIL'}："
         f"{'全部 ' + str(len(imgs)) + ' 张都是 ' + str(args.size) + '×' + str(args.size) if size_ok else '存在非期望尺寸/读不出的图'}")

    # ---- [3] 内容去重（R9）
    banner("[3] 内容去重（R9：文件名 = 内容 sha256）")
    names = [p.stem for p in imgs]
    named_dupes = len(names) - len(set(names))
    content_dupes = 0
    hashed = 0
    if not args.no_hash and imgs:
        seen: dict[str, str] = {}
        for p in imgs:
            h = sha256_file(p)
            hashed += 1
            if not h:
                continue
            if h in seen:
                content_dupes += 1
            else:
                seen[h] = p.name
        push(f"  实算 sha256：{hashed} 张（内容唯一 {len(seen)} 个 => 重复副本 {content_dupes} 张）")
    else:
        push("  未实算内容哈希（--no-hash）")
    push(f"  文件名层面重复：{named_dupes} 张")
    push(f"  索引清单键数：{len(unique_urls(data))} 个唯一图 URL（cover-refs.json）")

    # ---- [4] 索引三态
    banner("[4] 索引三态（A8：已就绪 / 待处理 / 失败）")
    idx = read_json(cover / "cover-index.json") or {}
    refs = read_json(cover / "cover-refs.json") or {}
    ready = pending = failed = 0
    missing_img = 0
    for _k, rec in idx.items():
        if not isinstance(rec, dict):
            continue
        n = int(rec.get("n") or 0)
        name = rec.get("i")
        exists = bool(name) and (cover / "img" / str(name)).is_file()
        if n > 0:
            failed += 1
        elif exists:
            ready += 1
        else:
            pending += 1
            if name:
                missing_img += 1
    push(f"  已就绪 {ready} / 待处理 {pending} / 失败 {failed}（索引记录 {len(idx)} 条，"
         f"其中 {missing_img} 条记了原图名但文件不在）")
    push(f"  专辑清单 cover-refs.json：{len(refs)} 键 / "
         f"{sum(1 for v in refs.values() if isinstance(v, dict) and v.get('u'))} 带图 URL")
    push(f"  桩文件数：{counts.get('stub', {}).get('files', 0)}"
         f"（img 与 stub 张数**不必相等**：Q1 去重后 img 按内容唯一、stub 按专辑）")

    # ---- [5] 失败分类（日志）
    banner("[5] 失败分类（日志原文计数，R11）")
    lf = log_files(logs, args.days)
    info = scan_logs(lf)
    push(f"  日志文件：{len(lf)} 个")
    if info["fail_kinds"]:
        for k, v in sorted(info["fail_kinds"].items(), key=lambda kv: (-kv[1], kv[0])):
            push(f"  封面失败[{k}] ×{v}（最高尝试次数 {info['attempts_max']}/5）")
    else:
        push("  封面失败[…] ：0 行")
    push(f"  封面放弃 ×{info['giveup']}；封面桩 ×{info['stub_lines']}")
    if info["stats_last"]:
        push(f"  最后一条完成度行：封面缓存：{info['stats_last']}")
        sp = info["stats_parsed"]
        push(f"  => 运行时权威三态：已就绪 {sp['已就绪']} / 待处理 {sp['待处理']} / 失败 {sp['失败']}")
    else:
        push("  [!] 没有 `[cover] 封面缓存：…` 行（管线没跑过？日志被轮转？）")
    if info["done_last"]:
        push(f"  最后一条 A9 行：完成 {info['done_last'][0]} 张 / 去重省下 {info['done_last'][1]} 次")
    if info["prime_last"]:
        push(f"  预热/投递最后一行：{info['prime_last'][:160]}")
    if info["stub_last"]:
        push(f"  最后一条成功词形：{info['stub_last'][:160]}")

    # ---- [6] 判据
    banner("[6] 判据")
    albums = args.expect_albums if args.expect_albums is not None else (len(refs) or None)
    album_note = "命令行" if args.expect_albums is not None else "cover-refs.json 键数"
    checks: dict[str, tuple[bool, str]] = {}
    checks["img-size"] = (size_ok,
                          f"img\\ {len(imgs)} 张全 {args.size}×{args.size}" if size_ok
                          else f"img\\ 尺寸不符（{', '.join(f'{k}×{v}' for k, v in dist.items()) or '空'}）")
    if albums:
        ok_dedup = len(imgs) <= albums
        checks["dedup"] = (ok_dedup, f"img 文件数 {len(imgs)} ≤ 专辑数 {albums}（{album_note}）")
        checks["stub"] = (counts.get("stub", {}).get("files", 0) <= albums,
                          f"stub 文件数 {counts.get('stub', {}).get('files', 0)} ≤ 专辑数 {albums}")
    else:
        checks["dedup"] = (False, "拿不到专辑数（无 --expect-albums 且 cover-refs.json 为空）")
    sp_failed = info["stats_parsed"]["失败"]
    fail_n = sp_failed if sp_failed is not None else info["giveup"] + sum(info["fail_kinds"].values())
    checks["no-fail"] = (fail_n <= args.max_fail,
                         f"失败 {fail_n} ≤ 容忍 {args.max_fail}"
                         f"（来源：{ '最后一条完成度行' if sp_failed is not None else '日志分类累加' }）")
    checks["index"] = (ready > 0 or not imgs,
                       f"索引已就绪 {ready}（img 有 {len(imgs)} 张）")

    wanted = [w.strip() for w in args.require.split(",") if w.strip()]
    failed_checks = []
    for name in wanted:
        if name not in checks:
            push(f"  ? {name}：未知判据（可选：{'/'.join(checks)}）")
            continue
        ok, why = checks[name]
        push(f"  {'[OK]' if ok else '[!!]'} {name}：{why}")
        if not ok:
            failed_checks.append(name)
    push("")
    push(f"结论：{'PASS' if not failed_checks else 'FAIL'}（{'全部判据通过' if not failed_checks else '未过：' + ', '.join(failed_checks)}）")

    payload = {
        "time": _dt.datetime.now().isoformat(timespec="seconds"),
        "data_dir": str(cover),
        "counts": counts,
        "img": {"files": len(imgs), "formats": fmt, "size_dist": dist,
                "unknown": len(unknown), "others": len(others)},
        "dedup": {"named_dupes": named_dupes, "content_dupes": content_dupes, "hashed": hashed,
                  "urls": len(unique_urls(data))},
        "index": {"records": len(idx), "refs": len(refs), "ready": ready,
                  "pending": pending, "failed": failed, "missing_img": missing_img},
        "log": info,
        "checks": {k: {"pass": v[0], "why": v[1]} for k, v in checks.items()},
        "verdict": "PASS" if not failed_checks else "FAIL",
    }
    report = "\n".join(lines) + "\n"
    print(report)
    if args.out:
        Path(args.out).write_text(report, encoding="utf-8")
        print(f"[封面审计] 报告已写：{Path(args.out).resolve()}")
    if args.json:
        Path(args.json).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[封面审计] 快照已写：{Path(args.json).resolve()}")
    return 0 if not failed_checks else 1


def read_json(path: Path):
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def unique_urls(data: Path) -> set[str]:
    refs = read_json(data / "cover" / "cover-refs.json")
    if not isinstance(refs, dict):
        return set()
    out = set()
    for v in refs.values():
        if isinstance(v, dict) and v.get("u"):
            out.add(str(v["u"]))
    return out


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        sys.exit(2)
