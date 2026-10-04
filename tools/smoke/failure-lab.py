#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""W6 失败可见性实证 —— 离线故障实验室（禁止静默失败，任务书 §0 硬验收）。

四类管线各造一次可复核证据（同步 / 封面 / 歌词 / 播放），全程：

* **不写真库**：真机 `spw.db` 只被**只读**复制成副本（``sqlite3`` 备份 API，副本在临时沙箱里），
  所有写入只发生在副本上；``sync-silent-*`` 场景连副本都不写（通道是伪造的）。
* **不动宿主**：不起宿主、不装插件、不读本机网易云客户端、不下载音乐。
* **日志落沙箱**：每个场景一个独立 JVM，``APPDATA`` 指向本次沙箱，
  ``<沙箱>\\Salt Player for Windows\\workshop\\data\\com.example.netease\\logs`` 里是自己产生的日志。

产物：终端表格 + ``-o`` 指定的 Markdown 报告（默认写临时目录）。

用法::

    python tools\\smoke\\failure-lab.py                     # 跑全部场景，报告进临时目录
    python tools\\smoke\\failure-lab.py -o docs\\51-W6失败实证.md
    python tools\\smoke\\failure-lab.py --scene lyric-dead-info --keep
    python tools\\smoke\\failure-lab.py --list

退出码：0 = 全部场景符合预期（已知缺口按「缺口存在」计，不算失败）；
1 = 有场景拿到「不该出现的结果」（例如出现「像成功、实际没写」）；
2 = 环境/参数错误（缺 jar、编译失败、真库读不到）。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import struct
import threading
import time
import zlib
import zipfile
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except Exception:  # pragma: no cover
        pass

ROOT = Path(__file__).resolve().parents[2]
PLUGIN_ID = "com.example.netease"
DATA_REL = Path("Salt Player for Windows") / "workshop" / "data" / PLUGIN_ID
DEFAULT_HOST_DB = Path(os.environ.get("APPDATA", "")) / "Salt Player for Windows" / "spw.db"
JARS = [ROOT / "tools" / ".cache" / "spw-workshop-api-host.jar",
        ROOT / "libs" / "sqlite-jdbc-3.41.2.2.jar"]
DEAD_PROXY = ["-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
              "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9"]
LOG_RE = re.compile(r"^\d\d:\d\d:\d\d \[(ERROR|WARN|INFO|DEBUG)\] \[([^\]]+)\] (.*)$")

# 1x1 真 PNG（正对照：能解码的图）
PNG_1PX = bytes.fromhex(
    "89504e470d0a1a0a0000000d494844520000000100000001080600000"
    "01f15c4890000000a49444154789c6360000002000154a24f5f0000000049454e44ae426082"
)


# ---------------------------------------------------------------- 场景表


def scenes(http_base: str) -> list[dict]:
    """场景 = 一次独立 JVM + 一组「必须出现」/「禁止出现」的断言。"""
    return [
        dict(
            name="sync-ok",
            why="同步正对照：真 JDBC（真库只读副本）整轮写入 ⇒ 必须成功且库里真有行",
            argv=["sync-ok", "db={db}"],
            db=True,
            expect=[
                ("stdout", r"status=ok\b", "整轮写入返回 ok"),
                ("stdout", r"dbTracks=6 dbLinks=6 dbPlaylists=2", "库里真行数 6/6/2 可核对"),
                ("log", r"\[INFO\].*本轮写入：歌单 2 个 / 曲目 6 首（去重后，关联 6 行）", "写完成 INFO 原文"),
            ],
            forbid=[("stdout", r"status=(fail|threw)", "正对照不许失败")],
        ),
        dict(
            name="sync-silent-write",
            why="同步失败①：通道「谎报写成功」但曲目行不存在 ⇒ 必须 ERROR + 中止（0.11.6 病灶复刻）",
            argv=["sync-silent-write", "db={db}"],
            db=True,
            expect=[
                ("log", r"\[ERROR\].*曲目行不存在，拒绝写关联", "①ERROR 原文（失败原因可分类）"),
                ("log", r"本轮中止、不提交", "②摘要写明本轮已中止"),
                ("stdout", r"status=threw", "异常上抛（调用方回滚、不提交）"),
            ],
            forbid=[("log", r"本轮写入：", "③不许出现「本轮写入…」成功行")],
        ),
        dict(
            name="sync-silent-count",
            why="同步失败②：写语句谎报成功、行数自检兜底 ⇒ 必须 ERROR +「写入被静默丢弃」",
            argv=["sync-silent-count", "db={db}"],
            db=True,
            expect=[
                ("log", r"\[ERROR\].*写入自检未通过：本轮声明曲目 6 首", "①ERROR 原文（含期望/实际行数）"),
                ("log", r"写入被静默丢弃", "指出「静默丢弃」这个病灶"),
                ("log", r"⇒ 中止本轮、不提交", "②摘要写明本轮已中止"),
                ("stdout", r"status=threw", "异常上抛"),
            ],
            forbid=[("log", r"本轮写入：", "③不许出现「本轮写入…」成功行")],
        ),
        dict(
            name="net-dead",
            why="同步网络侧：断网（死代理）下取歌单必须抛可分类的异常，不许静默返回空",
            argv=["net-dead"],
            proxy=True,
            expect=[
                ("stdout", r"status=threw", "断网下必须抛错"),
                ("stdout", r"(Connect|refused|UnknownHost|timed out|SocketException|proxy)",
                 "错误消息可分类（连接/代理/超时）"),
            ],
            forbid=[("stdout", r"status=bad-success", "断网下不许「成功」")],
        ),
        dict(
            name="net-probe-404",
            why="HTTP 层正对照：404 与「连不上」必须给出不同的分类结论（证明实验室能区分成功/失败）",
            argv=["net-probe", f"url={http_base}/missing.jpg"],
            no_proxy=True,
            expect=[("stdout", r"probe=.+", "探针给出明确结论（非空字符串）")],
        ),
        dict(
            name="cover-ok",
            why="封面正对照：本地 200 + 真 PNG（尺寸与 ?param 一致）⇒ 图必须落到沙箱缓存里，「已就绪」数字与目录一致"
                "（**当前 W3 在飞：真图也被判 `封面分类[尺寸归一]` + `封面失败[落盘失败，1/5]`、`ensure=null` ⇒ 缺口**）",
            argv=["cover", "album=W6LAB-专辑-正对照", "url={base}/solid.png"],
            no_proxy=True,
            gap=True,
            cross="cover",
            expect=[
                ("stdout", r"status=ok\b", "调用返回"),
                ("stdout", r"stats=封面缓存：已就绪 [1-9]\d*", "缺口：真图落盘 ⇒ 完成度「已就绪」> 0"),
                ("log", r"\[INFO \] \[cover\] 封面分类\[",
                 "①失败分类行可见（当前分类词是 `尺寸归一`）"),
            ],
            forbid=[("stdout", r"UNCAUGHT", "不许崩")],
        ),
        dict(
            name="cover-404",
            why="封面失败①：404 ⇒ 失败必须可分类（R11）",
            argv=["cover", "album=W6LAB-专辑-404", "url={base}/missing.jpg"],
            no_proxy=True,
            expect=[
                ("stdout", r"status=ok\b", "调用返回（不把异常抛给宿主回调线程）"),
                ("log", r"\[WARN\] \[cover\] 封面 HTTP 404：http://127\.0\.0\.1",
                 "①失败留在默认 INFO 级别：WARN + 分类词「HTTP 404」"),
                ("stdout", r"ensure=\S+ .*stats=", "返回 URI 与 stats 可复核"),
            ],
            forbid=[("log", r"\[(INFO|DEBUG|WARN|ERROR)\] \[cover\].*成功",
                     "③不许留「成功」字样")],
        ),
        dict(
            name="cover-bad-format",
            why="封面失败②：200 但内容不是图片 ⇒ 必须归到「格式异常」类（R11）",
            argv=["cover", "album=W6LAB-专辑-坏格式", "url={base}/bad.jpg"],
            no_proxy=True,
            gap=True,
            expect=[
                ("stdout", r"status=ok\b", "调用返回"),
                ("log", r"\[(WARN|ERROR)\] \[cover\].*(格式|解码|图片|image|非图|坏)",
                 "①缺：200 非图片要留「格式异常」分类行（当前完全无声）"),
            ],
        ),
        dict(
            name="cover-dead-host",
            why="封面失败③：连不上（死端口）⇒ 必须归到「网络」类（R11）",
            argv=["cover", "album=W6LAB-专辑-断网", "url=http://127.0.0.1:9/w6lab.jpg"],
            no_proxy=True,
            expect=[
                ("stdout", r"status=ok\b", "调用返回"),
                ("log", r"\[WARN\] \[cover\] 封面下载失败：java\.net\.ConnectException",
                 "①失败分类可见：WARN +「连接被拒绝」"),
            ],
        ),
        dict(
            name="lyric-dead-info",
            why="歌词失败①：断网 + 不存在的 songId，**默认 INFO 级别**下必须失败可见 + 失败分类可见",
            argv=["lyric", "id=999999999", "level=INFO"],
            proxy=True,
            gap=True,
            expect=[
                ("stdout", r"status=ok\b", "调用返回（不阻塞回调线程）"),
                ("log", r"\[ERROR\] \[http\] 请求最终失败：https://music\.163\.com/api/song/lyric",
                 "①失败在默认级别可见：ERROR 原文（http 层兜底）"),
                ("stdout", r"after stats=歌词完成度：.*失败 1（.*NET=1",
                 "②摘要写明失败计数与分类（失败 1 / NET=1）"),
                ("stdout", r"lrcFor=null", "③没抓到就是 null（不假装有词）"),
            ],
            forbid=[("stdout", r"lrcFor=(?!null)", "③断网下不许返回「有歌词」"),
                    ("log", r"\[DEBUG\] \[\w+\] .*歌词.{0,4}失败", "③失败不许只藏在 DEBUG 里")],
        ),
        dict(
            name="lyric-dead-debug",
            why="歌词失败①对照（0.11.8 收口）：组件级失败行原先只在 DEBUG 级别可见（gap）⇒ 0.11.7 P0 已把 "
                "`PluginLog.d(\"netease\", \"老歌词接口失败\")` 提为 `w`。本行把级别显式压回 DEBUG 再跑一遍，"
                "断言该行仍以 WARN 出现（谁要是把它降回 d，这里立刻红）",
            argv=["lyric", "id=999999999", "level=DEBUG"],
            proxy=True,
            expect=[("log", r"\[(WARN|ERROR)\] \[netease\] 老歌词接口失败",
                     "[组件级] 失败行在 DEBUG 级别仍以 WARN 出现（未被级别淹掉）")],
        ),
        dict(
            name="stream-dead",
            why="播放失败①：预取不存在的曲目（断网）⇒ 失败必须可见 + 有界退避（不无限重试）",
            argv=["stream", "id=999999999", "quality=lossless", "wait=6000"],
            proxy=True,
            gap=True,
            expect=[
                ("stdout", r"status=ok\b.*awaitComplete=false", "有界等待后返回 false（不悬挂）"),
                ("log", r"\[WARN\] \[(prefetch|stream)\] .*(预取放弃|预取失败)",
                 "①失败留 WARN 原文（可见）"),
                ("log", r"\[(WARN|ERROR)\] \[(prefetch|stream)\].*(断网|网络|连接|超时|Connection)",
                 "②缺：失败原因分类词（当前只写「取不到总长」，看不出是网络失败）"),
            ],
            forbid=[("stdout", r"awaitComplete=true", "③断网下不许报「已完成」")],
        ),
        dict(
            name="cover-cache-consistency",
            why="A12 抽样复核：配置页显示的封面完成度数字必须等于封面缓存目录里的真实文件数"
                "（**当前 W3 在飞 ⇒ 下载成功路径取不到样例，缺口**）",
            argv=["cover", "album=W6LAB-专辑-一致", "url={base}/solid.png", "wait=3000"],
            no_proxy=True,
            gap=True,
            cross="cover",
            expect=[
                ("stdout", r"stats=封面缓存：已就绪 [1-9]\d*", "真图落盘后完成度 > 0"),
                ("stdout", r"ensure=file:", "①返回的是本地 file: URI（不是挂网直链）"),
            ],
        ),
        dict(
            name="lyric-cache-hit",
            why="A12 抽样复核：预置一首歌词缓存 ⇒ lrcFor 必须命中，且「已就绪」数字等于 lyric\\*.lrc 文件数",
            argv=["lyric", "id=123456789", "level=INFO", "wait=1200"],
            no_proxy=True,
            seed_lyric=123456789,
            cross="lyric",
            expect=[
                ("stdout", r"lrcFor=[1-9]\d*字", "换曲那一刻命中（R14 的可复核代理）"),
                ("stdout", r"after stats=歌词完成度：已就绪 [1-9]\d*", "完成度「已就绪」> 0"),
            ],
        ),
        dict(
            name="stream-level-label",
            why="音质档位标注（D7/A15）：失败路径也要能看出请求/实得档位",
            argv=["stream", "id=999999999", "quality=lossless", "wait=6000"],
            proxy=True,
            gap=True,
            expect=[("log", r"档位 请求=|实得=", "缺：请求/实得档位成对标注（W5 冻结词形）")],
        ),
    ]


# ---------------------------------------------------------------- 运行设施


def copy_db_readonly(src: Path, dst: Path) -> str:
    """只读复制真库（SQLite 备份 API；真库全程以 mode=ro 打开，绝不写）。"""
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists():
        dst.unlink()
    con = sqlite3.connect(f"file:{src.as_posix()}?mode=ro", uri=True)
    try:
        out = sqlite3.connect(str(dst))
        try:
            con.backup(out)
        finally:
            out.close()
    finally:
        con.close()
    return str(dst)


def newest_spmod() -> Path | None:
    dist = ROOT / "build" / "dist"
    if not dist.is_dir():
        return None
    cands = sorted(dist.glob("plugin-com.example.netease-*.spmod"),
                   key=lambda p: p.stat().st_mtime)
    return cands[-1] if cands else None


def extract_spmod_classes(spmod: Path, dst: Path) -> int:
    """spmod 里的类是 ``classes/`` 前缀 ⇒ 解出来才能当 classpath 用。"""
    dst.mkdir(parents=True, exist_ok=True)
    n = 0
    with zipfile.ZipFile(spmod) as z:
        for info in z.infolist():
            if not info.filename.startswith("classes/") or info.is_dir():
                continue
            rel = info.filename[len("classes/"):]
            out = dst / rel
            out.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as fh, open(out, "wb") as w:
                shutil.copyfileobj(fh, w)
            n += 1
    return n


def png_solid(w: int, h: int) -> bytes:
    """纯 Python 生成 w×h 纯色 PNG（无第三方依赖）。

    用途：W3 会校验「取回的图与 `?param=WxH` 是否同尺寸」（两侧都失败过 `尺寸归一`），
    所以正对照必须真的给出一张 WxH 的图，不能拿 1×1 冒充。
    """
    raw = b"".join(b"\x00" + bytes((0x40, 0x80, 0xC0)) * w for _ in range(h))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 6))
            + chunk(b"IEND", b""))


_SOLID_CACHE: dict[tuple[int, int], bytes] = {}


class _FixtureHandler(SimpleHTTPRequestHandler):
    """在静态目录之上加一个 ``/solid.png?param=WxH``：按请求尺寸现生成真 PNG。"""

    def do_GET(self) -> None:  # noqa: N802
        if self.path.startswith("/solid.png"):
            q = parse_qs(urlparse(self.path).query)
            m = re.match(r"(\d+)y(\d+)", (q.get("param") or ["1200y1200"])[0])
            wh = (int(m.group(1)), int(m.group(2))) if m else (1200, 1200)
            if wh not in _SOLID_CACHE:
                _SOLID_CACHE[wh] = png_solid(*wh)
            body = _SOLID_CACHE[wh]
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        super().do_GET()

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("    [fixture] " + (fmt % args) + "\n")


class Fixture:
    """本地 127.0.0.1 小服务：solid.png(按尺寸的真图) / ok.png(1×1) / bad.jpg(200 非图) / missing.jpg(404)。"""

    def __init__(self, root: Path):
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)
        (self.root / "ok.png").write_bytes(PNG_1PX)
        (self.root / "bad.jpg").write_bytes(b"W6LAB not an image, just text\n" * 8)
        handler = partial(_FixtureHandler, directory=str(self.root))
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def stop(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()


# 级别标签是**定宽对齐**的（实测 `[INFO ]` / `[WARN ]` / `[ERROR]` / `[DEBUG]`）：
# 断言一律按「去掉标签内空格」后的形状匹配，避免 `[INFO]` 匹配不上 `[INFO ]`。
LV_RE = re.compile(r"\[(TRACE|DEBUG|INFO|WARN|ERROR)\s*\]")


def norm_line(line: str) -> str:
    return LV_RE.sub(lambda m: f"[{m.group(1)}]", line)


def read_logs(appdata: Path) -> list[str]:
    logs = appdata / DATA_REL / "logs"
    out: list[str] = []
    if logs.is_dir():
        for p in sorted(logs.glob("plugin-*.log")):
            try:
                out.extend(p.read_text(encoding="utf-8", errors="replace").splitlines())
            except OSError:
                pass
    return out


def find_log_hits(lines: list[str], pattern: str, limit: int = 3) -> list[str]:
    """按「级别标签去掉对齐空格」后的形状匹配（日志里是 `[INFO ]` 定宽），命中则返回**原始行**。"""
    rx = re.compile(pattern)
    hits = [ln for ln in lines if rx.search(norm_line(ln))]
    return hits[:limit]


def _count_files(p: Path, suffix: str = "") -> int:
    if not p.is_dir():
        return 0
    return sum(1 for x in p.rglob("*") if x.is_file() and (not suffix or x.name.endswith(suffix)))


def cross_check(kind: str, appdata: Path, stdout: str) -> list[tuple[str, bool, str]]:
    """A12 抽样复核：配置页显示的完成度数字，必须等于缓存目录里的真实计数。

    只认 stdout 里**最后一次** `已就绪 N`（一次场景内可能先报旧值再报新值，取末值才是落盘后的真相）。
    """
    nums = re.findall(r"已就绪 (\d+)", stdout)
    ready = int(nums[-1]) if nums else -1
    data = appdata / DATA_REL
    if kind == "cover":
        n = _count_files(data / "cover" / "img")
        label = f"完成度数字与实际目录一致（封面：stats 已就绪 {ready} vs cover\\img {n} 个文件）"
    else:
        n = _count_files(data / "lyric", ".lrc")
        label = f"完成度数字与实际目录一致（歌词：stats 已就绪 {ready} vs lyric\\*.lrc {n} 个文件）"
    return [(label, ready >= 0 and ready == n, f"[LAB] cross-check {kind}: ready={ready} files={n}")]


def run_scene(sc: dict, ctx: dict) -> dict:
    appdata = ctx["sandbox"] / sc["name"] / "appdata"
    (appdata / DATA_REL).mkdir(parents=True, exist_ok=True)
    if sc.get("seed_lyric"):
        ldir = appdata / DATA_REL / "lyric"
        ldir.mkdir(parents=True, exist_ok=True)
        (ldir / f"{sc['seed_lyric']}.lrc").write_text(
            "[00:00.00]W6LAB 缓存命中测试\n[00:05.00]第二行\n", encoding="utf-8")
    subst = dict(base=ctx["fixture"].base, db=str(appdata / "w6lab-copy.db"))
    if sc.get("db"):
        copy_db_readonly(ctx["host_db"], appdata / "w6lab-copy.db")
    argv = [a.format(**subst) for a in sc["argv"]]
    cmd = [ctx["java"], "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
           "-Dfile.encoding=UTF-8", "-cp", ctx["cp"]]
    if sc.get("proxy"):
        cmd += DEAD_PROXY
    cmd += [ctx["main"], *argv]
    env = dict(os.environ)
    env["APPDATA"] = str(appdata)
    env.pop("LOCALAPPDATA", None)
    t0 = time.time()
    p = subprocess.run(cmd, cwd=str(ROOT), env=env, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=ctx["timeout"])
    stdout = (p.stdout or "") + (p.stderr or "")
    lines = read_logs(appdata)
    res = dict(name=sc["name"], why=sc["why"], top=argv[0], argv=" ".join(argv), exit=p.returncode,
               seconds=round(time.time() - t0, 2), gap=bool(sc.get("gap")),
               sandbox=str(appdata), stdout=stdout, log_lines=len(lines),
               counts={lv: sum(1 for ln in lines if f"[{lv}]" in norm_line(ln))
                       for lv in ("ERROR", "WARN", "INFO", "DEBUG")},
               log_raw=[ln for ln in lines if LV_RE.search(ln) and not ln.startswith((" ", "\t"))],
               checks=[], forbid_hits=[], evidence=[])

    def hit(kind: str, pattern: str) -> list[str]:
        return find_log_hits(lines, pattern) if kind == "log" else \
            [ln for ln in stdout.splitlines() if re.search(pattern, ln)][:3]

    for kind, pattern, desc in sc.get("expect", []):
        hits = hit(kind, pattern)
        res["checks"].append(dict(kind=kind, pattern=pattern, desc=desc, ok=bool(hits)))
        res["evidence"].extend(hits)
    for kind, pattern, desc in sc.get("forbid", []):
        hits = hit(kind, pattern)
        if hits:
            res["forbid_hits"].append(dict(pattern=pattern, desc=desc, lines=hits))
            res["evidence"].extend(hits)
    if sc.get("cross"):
        for desc, ok, detail in cross_check(sc["cross"], appdata, stdout):
            res["checks"].append(dict(kind="cross", pattern=sc["cross"], desc=desc, ok=ok))
            res["evidence"].append(detail)
    failed = [c for c in res["checks"] if not c["ok"]]
    if res["forbid_hits"]:
        res["verdict"] = "BAD"                       # 出现「像成功、实际没写」⇒ 必须修
    elif not failed:
        res["verdict"] = "GAP-CLOSED" if res["gap"] else "PASS"
    else:
        res["verdict"] = "GAP-OPEN" if res["gap"] else "FAIL"
    return res


# ---------------------------------------------------------------- 渲染


def kind_of(name: str) -> str:
    return {"sync": "sync", "net": "sync", "cover": "cover",
            "lyric": "lyric", "stream": "stream"}[name.split("-")[0]]


# 「四类 × 三列证据」综合表选用的代表场景（每类给一条可见样本 + 一条缺口样本）
SYNTH = (("同步", "sync-silent-count"), ("同步", "sync-silent-write"),
         ("封面", "cover-404"), ("封面", "cover-bad-format"),
         ("歌词", "lyric-dead-info"), ("播放", "stream-dead"))


def _first_log(res: dict, pattern: str) -> str:
    rx = re.compile(pattern)
    for ln in res.get("log_raw", []):
        if rx.search(norm_line(ln)):
            return ln
    return ""


def synthesis_rows(results: list[dict]) -> list[tuple[str, str, str, str, str]]:
    """返回 (类别, 场景, ①日志原文, ②摘要/提示语, ③有没有「界面像成功、实际没写」)。"""
    by = {r["name"]: r for r in results}
    rows: list[tuple[str, str, str, str, str]] = []
    for label, name in SYNTH:
        r = by.get(name)
        if r is None:
            continue
        log = _first_log(r, r"\[(ERROR|WARN)\] .*(封面|歌词|预取|同步|曲目行|写入|自检|请求最终失败)")
        summary = "-"
        for pat in (r"status=threw", r"status=ok waited", r"awaitComplete=", r"ensure=", r"stats="):
            for ln in r["stdout"].splitlines():
                if re.search(pat, ln):
                    summary = ln.strip()
                    break
            if summary != "-":
                break
        three = "未出现（⛔ 断言全过）" if not r["forbid_hits"] else \
            "⛔ " + str(r["forbid_hits"][0]["pattern"])
        rows.append((label, name, log or "-", summary, three))
    return rows


def render(results: list[dict], ctx: dict, args) -> str:
    bad = [r for r in results if r["verdict"] == "BAD"]
    fail = [r for r in results if r["verdict"] == "FAIL"]
    open_gaps = [r for r in results if r["verdict"] == "GAP-OPEN"]
    out: list[str] = []
    out.append("# W6 失败可见性实证（禁止静默失败 · 四类管线）")
    out.append("")
    out.append(f"- 时间：{time.strftime('%Y-%m-%d %H:%M:%S')}")
    art = f"`{ctx['spmod'].name}`（打包于 {time.strftime('%Y-%m-%d %H:%M', time.localtime(ctx['spmod_mtime']))}）" \
        if ctx["spmod"] else f"`build/classes`（当前源码树）"
    out.append(f"- 被测件：{art}")
    out.append(f"- 沙箱：`{ctx['sandbox']}`（真库只读副本；**真库与宿主全程未写**）")
    out.append(f"- 结论：PASS {len(results) - len(bad) - len(fail) - len(open_gaps)} / "
               f"GAP-OPEN {len(open_gaps)} / FAIL {len(fail)} / BAD {len(bad)}")
    out.append("")
    out.append("| 场景 | 结论 | 断言 | 日志(ERROR/WARN/INFO/DEBUG) | 耗时 |")
    out.append("| --- | --- | --- | --- | --- |")
    for r in results:
        ok = sum(1 for c in r["checks"] if c["ok"])
        c = r["counts"]
        out.append(f"| `{r['name']}` | {r['verdict']} | {ok}/{len(r['checks'])} | "
                   f"{c['ERROR']}/{c['WARN']}/{c['INFO']}/{c['DEBUG']} | {r['seconds']}s |")
    out.append("")
    out.append("## 四类管线 × 三列证据（任务书 §0「禁止静默失败」口径）")
    out.append("")
    out.append("① 日志原文（默认级别下可见）｜② 摘要/提示语（写明失败或中止）｜"
               "③ 有没有出现「界面像成功、实际没写」")
    out.append("")
    out.append("| 类别 | 代表场景 | ① 日志原文 | ② 摘要 / 提示语 | ③ 像成功实际没写 |")
    out.append("| --- | --- | --- | --- | --- |")
    for label, name, log, summary, three in synthesis_rows(results):
        out.append(f"| {label} | `{name}` | `{log[:160]}` | `{summary[:200]}` | {three} |")
    out.append("")
    for r in results:
        out.append(f"## `{r['name']}` —— {r['verdict']}")
        out.append("")
        out.append(f"*意图*：{r['why']}")
        out.append("")
        out.append(f"*命令*：`FailureLab {r.get('argv', '')}` ｜ 退出码 {r['exit']}"
                   f" ｜ 沙箱 `{r['sandbox']}`")
        out.append("")
        out.append("| 断言 | 来源 | 结论 |")
        out.append("| --- | --- | --- |")
        for c in r["checks"]:
            out.append(f"| {c['desc']} | {c['kind']} `{c['pattern']}` | {'✅' if c['ok'] else '❌'} |")
        for f in r["forbid_hits"]:
            out.append(f"| ⛔ {f['desc']} | {f['kind']} `{f['pattern']}` | ❌ 命中（必须修） |")
        out.append("")
        if r["evidence"]:
            out.append("```text")
            for ln in r["evidence"][:12]:
                out.append(ln[:400])
            out.append("```")
            out.append("")
        if r["stdout"].strip():
            out.append("<details><summary>JVM 输出</summary>")
            out.append("")
            out.append("```text")
            out.extend(r["stdout"].strip().splitlines()[:40])
            out.append("```")
            out.append("")
            out.append("</details>")
            out.append("")
    out.append("## 复现")
    out.append("")
    out.append("```powershell")
    out.append("python tools\\smoke\\failure-lab.py -o docs\\51-W6失败实证.md")
    out.append("```")
    out.append("")
    if not args.keep:
        out.append("（本次沙箱已按默认策略保留；加 `--clean` 可跑完即删。）")
    return "\n".join(out)


# ---------------------------------------------------------------- main


def main() -> int:
    ap = argparse.ArgumentParser(description="W6 失败可见性实证（离线故障实验室）")
    ap.add_argument("-o", "--out", help="报告输出路径（默认写到沙箱目录）")
    ap.add_argument("--json", help="机器可读结果输出路径")
    ap.add_argument("--sandbox", help="沙箱根目录（默认 %%TEMP%%\\w6lab-<时间戳>）")
    ap.add_argument("--spmod", help="被测 spmod（默认 build\\dist 里最新的那个）")
    ap.add_argument("--from-source", action="store_true",
                    help="改用 build\\classes（要求当前源码树能编译；W3/W4/W5 在飞时可编译不过）")
    ap.add_argument("--host-db", help="真库路径（只读复制；默认 %%APPDATA%%\\Salt Player for Windows\\spw.db）")
    ap.add_argument("--scene", action="append", help="只跑指定场景（可重复）")
    ap.add_argument("--list", action="store_true", help="列出场景后退出")
    ap.add_argument("--timeout", type=int, default=120, help="单场景超时秒数（默认 120）")
    ap.add_argument("--clean", action="store_true", help="跑完删除沙箱")
    ap.add_argument("--keep", action="store_true", help="保留沙箱（默认保留）")
    args = ap.parse_args()

    fixture = Fixture(Path(args.sandbox or tempfile.mkdtemp(prefix="w6lab-")) / "fixtures")
    sandbox = fixture.root.parent
    all_scenes = scenes(fixture.base)
    if args.list:
        for s in all_scenes:
            print(f"{s['name']:<20} {'[缺口判据]' if s.get('gap') else ''} {s['why']}")
        fixture.stop()
        return 0

    # ---- 编译
    java_home = os.environ.get("JAVA_HOME")
    javac = str(Path(java_home) / "bin" / "javac.exe") if java_home else shutil.which("javac")
    java = str(Path(java_home) / "bin" / "java.exe") if java_home else shutil.which("java")
    if not javac or not java:
        print("找不到 javac/java（检查 JAVA_HOME 或 PATH）")
        return 2
    for j in JARS:
        if not j.is_file():
            print(f"缺少依赖 jar：{j}")
            return 2

    spmod = Path(args.spmod) if args.spmod else (None if args.from_source else newest_spmod())
    cp_parts: list[str] = []
    if spmod:
        if not spmod.is_file():
            print(f"spmod 不存在：{spmod}")
            return 2
        n = extract_spmod_classes(spmod, sandbox / "spmod-classes")
        print(f"[lab] 被测件 {spmod.name}（解出 {n} 个 class）")
        cp_parts.append(str(sandbox / "spmod-classes"))
    else:
        cp_parts.append(str(ROOT / "build" / "classes"))
    cp_parts += [str(j) for j in JARS]
    cp = ";".join(cp_parts)
    out_dir = sandbox / "lab-classes"
    out_dir.mkdir(parents=True, exist_ok=True)
    src = ROOT / "tools" / "smoke" / "w6lab" / "FailureLab.java"
    comp = subprocess.run([javac, "-encoding", "UTF-8", "-nowarn", "-cp", cp, "-d", str(out_dir), str(src)],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    if comp.returncode != 0:
        print("[lab] 编译失败（被测件的依赖可能正在被改）：")
        print((comp.stdout or "") + (comp.stderr or ""))
        fixture.stop()
        return 2
    if spmod and spmod.name != "plugin-com.example.netease-0.11.7.spmod":
        print(f"[lab] 注意：被测件不是 0.11.7 打包件（{spmod.name}）")

    host_db = Path(args.host_db) if args.host_db else DEFAULT_HOST_DB
    if not host_db.is_file():
        print(f"[lab] 真库不存在（同步类场景会跳过）：{host_db}")
    ctx = dict(sandbox=sandbox, fixture=fixture, cp=os.pathsep.join([str(out_dir), cp]),
               java=java, main="FailureLab", host_db=host_db, timeout=args.timeout,
               spmod=spmod, spmod_mtime=(spmod.stat().st_mtime if spmod else time.time()))

    selected = [s for s in all_scenes if not args.scene or s["name"] in set(args.scene)]
    results = []
    for sc in selected:
        if sc.get("db") and not host_db.is_file():
            results.append(dict(name=sc["name"], why=sc["why"], verdict="SKIP", checks=[], forbid_hits=[],
                                counts={"ERROR": 0, "WARN": 0, "INFO": 0, "DEBUG": 0}, seconds=0.0,
                                exit=-1, stdout="真库不存在，跳过", log_lines=0, sandbox="", gap=bool(sc.get("gap"))))
            continue
        res = run_scene(sc, ctx)
        results.append(res)
        print(f"[lab] {res['name']:<20} {res['verdict']:<11} "
              f"checks={sum(1 for c in res['checks'] if c['ok'])}/{len(res['checks'])} "
              f"log E/W/I/D={res['counts']['ERROR']}/{res['counts']['WARN']}/"
              f"{res['counts']['INFO']}/{res['counts']['DEBUG']} {res['seconds']}s")
    fixture.stop()

    report = render(results, ctx, args)
    out_path = Path(args.out) if args.out else (sandbox / "failure-lab-report.md")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(report, encoding="utf-8")
    print(f"[lab] 报告：{out_path}")
    if args.json:
        slim = [{k: v for k, v in r.items() if k != "log_raw"} for r in results]
        Path(args.json).write_text(json.dumps(dict(ctx={k: str(v) for k, v in ctx.items()
                                                       if k in ("sandbox", "cp", "spmod")},
                                                   results=slim), ensure_ascii=False, indent=2),
                                   encoding="utf-8")
        print(f"[lab] JSON：{args.json}")

    if args.clean:
        shutil.rmtree(sandbox, ignore_errors=True)
    bad = [r for r in results if r["verdict"] == "BAD"]
    fail = [r for r in results if r["verdict"] == "FAIL"]
    if bad:
        print(f"[lab] ⛔ {len(bad)} 个场景出现「像成功、实际没写」：{', '.join(r['name'] for r in bad)}")
        return 1
    if fail:
        print(f"[lab] ✗ {len(fail)} 个场景不符合预期：{', '.join(r['name'] for r in fail)}")
        return 1
    print(f"[lab] 全部场景符合预期（已知缺口 {sum(1 for r in results if r['verdict'] == 'GAP-OPEN')} 项）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
