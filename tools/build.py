#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
零 Gradle 构建链：javac 编译 + 打 .spmod 包。

本机没有 gradle / 7z / jar，所以整条链路只用 javac + Python 标准库：

    1. 读取仓库根 project.json（唯一事实来源：插件 ID / 版本 / 打包参数）
    2. javac --release 21 -cp "宿主抽取的 API jar;pf4j.jar" 编译 src/main/java
    3. 把 src/main/resources 平铺进 classes/ 根
    4. 组装 .spmod 目录结构（这个结构就是宿主解压后的插件目录）：
         META-INF/MANIFEST.MF                     <- 根 manifest（PF4J 用 ManifPluginDescriptorFinder 读它）
         classes/**                               <- 编译产物 + 资源 + META-INF/{extensions.idx,services/*,MANIFEST.MF}
         lib/*.jar                                <- 运行期第三方依赖（compileOnly 的宿主 API 不进包）
    5. 打成 ZIP（.spmod 就是 ZIP），文件名 plugin-<id>-<version>.spmod
    6. 自检：重新打开产物，解折行校验 manifest 键、extensions.idx、services 注册、主类 class 存在

⚠️ MANIFEST 折行：jar 规范规定每行（UTF-8 字节计）不得超过 72 字节，续行以单个空格开头。
   本插件的 Plugin-Name / Plugin-Description 含中文，单行必然超限，
   所以打包脚本必须自己实现折行，否则宿主读不到插件元数据（工坊里看不到插件）。
   见 tools/build.py 的 _fold_line / _take。

用法：
    python tools/build.py
    python tools/build.py --no-clean
"""

from __future__ import annotations

import argparse
import hashlib
import json
import locale
import os
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

# Windows 控制台默认 GBK：中文路径/中文 manifest 一定会炸，先统一切到 UTF-8（不支持的字符替换而非崩溃）
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"
CACHE = TOOLS / ".cache"
API_JAR = CACHE / "spw-workshop-api-host.jar"
PF4J_JAR = CACHE / "pf4j-3.12.0.jar"

BUILD = ROOT / "build"
CLASSES = BUILD / "classes"
STAGE = BUILD / "stage"

MANIFEST_LINE_LIMIT = 72  # jar 规范：每物理行 UTF-8 字节数上限

# --------------------------------------------------------------------------- 工具


def log(msg: str = "") -> None:
    print(msg, flush=True)


def _decode(raw: bytes) -> str:
    """子进程输出解码：优先 UTF-8，退回 GBK / 系统 ANSI，最后替换式解码（绝不因乱码崩溃）。"""
    for enc in ("utf-8", "gbk", locale.getpreferredencoding(False), "mbcs"):
        if not enc:
            continue
        try:
            return raw.decode(enc)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("utf-8", errors="replace")


def die(msg: str) -> "NoReturn":  # type: ignore[valid-type]
    print(f"\n[构建失败] {msg}\n", file=sys.stderr, flush=True)
    raise SystemExit(1)


def load_project() -> dict:
    pj = ROOT / "project.json"
    if not pj.exists():
        die(f"缺少 {pj}")
    data = json.loads(pj.read_text(encoding="utf-8"))
    data.pop("$comment", None)
    for key in ("pluginId", "version", "name", "mainClass", "extensionPoint", "extensionClasses"):
        if not data.get(key):
            die(f"project.json 缺少必填字段：{key}")
    if not isinstance(data["extensionClasses"], list) or not data["extensionClasses"]:
        die("project.json 的 extensionClasses 必须是非空数组")
    return data


def find_javac() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        cand = Path(java_home) / "bin" / ("javac.exe" if os.name == "nt" else "javac")
        if cand.exists():
            return str(cand)
    found = shutil.which("javac")
    if found:
        return found
    die("找不到 javac。请安装 JDK 21+ 并把 bin 加进 PATH，或设置 JAVA_HOME。")
    raise AssertionError  # unreachable


def ensure_api_jars() -> None:
    missing = [p for p in (API_JAR, PF4J_JAR) if not p.exists()]
    if missing:
        names = "、".join(str(p) for p in missing)
        die(
            f"缺少编译期 classpath：{names}\n"
            "请先运行：pwsh -File tools/extract-api.ps1\n"
            "（它从宿主 app\\ffmpeg-x64.dll 里抽取 API 与 PF4J）"
        )


# --------------------------------------------------------------------------- MANIFEST


def _take(raw: bytes, limit: int) -> tuple[bytes, bytes]:
    """从 raw 头部切出 <= limit 字节，且不切断 UTF-8 多字节序列。"""
    if len(raw) <= limit:
        return raw, b""
    cut = limit
    while cut > 0 and (raw[cut] & 0xC0) == 0x80:
        cut -= 1
    if cut == 0:  # 理论上不会发生（UTF-8 单字符 <= 4 字节）
        cut = limit
    return raw[:cut], raw[cut:]


def _fold_line(line: str) -> list[bytes]:
    """
    把一行 'Key: Value' 折成若干 <=72 字节的物理行（续行前置一个空格）。

    ⚠️ 0.11.18 修补：折点若正好落在描述文本自身的空格前，续行就会以「折行空格 + 文本空格」
    两个空格开头 —— PF4J 的 Manifest 解析与 tools/verify-spmod.ps1 的 P1.3 都判这种物理行非法
    （0.11.18 的 Plugin-Description 变长后正好踩上）。修法：把该段最后一个字符下移到下一段开头，
    让那个文本空格留在上一行行尾 —— 解折行拼接后内容完全不变。
    """
    raw = line.encode("utf-8")
    out: list[bytes] = []
    limit = MANIFEST_LINE_LIMIT
    while raw:
        chunk, rest = _take(raw, limit)
        if rest.startswith(b" "):
            i = len(chunk) - 1                       # 回退到最后一个 UTF-8 字符的起点
            while i > 0 and (chunk[i] & 0xC0) == 0x80:
                i -= 1
            if i > 0:
                chunk, rest = chunk[:i], chunk[i:] + rest
        out.append((b"" if not out else b" ") + chunk)
        raw = rest
        limit = MANIFEST_LINE_LIMIT - 1              # 续行要留一个字节给折行空格
    return out


def build_manifest(project: dict) -> bytes:
    entries: list[tuple[str, str]] = [
        ("Manifest-Version", str(project.get("manifestVersion", "1.0"))),
        ("Plugin-Class", project["mainClass"]),
        ("Plugin-Id", project["pluginId"]),
        ("Plugin-Version", str(project["version"])),
        ("Plugin-Name", project["name"]),
        ("Plugin-Description", project.get("description", "")),
        ("Plugin-Provider", project.get("provider", "")),
        ("Plugin-Open-Source-Url", project.get("openSourceUrl", "")),
        ("Plugin-Has-Config", "true" if project.get("hasConfig") else "false"),
    ]
    # 1.18.5 不支持 Plugin-Permissions（写了也无害）；只在确实声明权限时写入
    perms = project.get("permissions") or []
    if perms:
        entries.append(("Plugin-Permissions", ",".join(perms)))
    entries.append(("Created-By", "spw-netease-plugin/tools/build.py"))

    out = bytearray()
    for key, value in entries:
        for physical in _fold_line(f"{key}: {value}"):
            out += physical + b"\r\n"
    out += b"\r\n"  # 主 section 结束
    return bytes(out)


def parse_manifest(data: bytes) -> dict[str, str]:
    """解折行解析 manifest，用于构建自检。"""
    text = data.replace(b"\r\n", b"\n").decode("utf-8", errors="replace")
    logical: list[str] = []
    for line in text.split("\n"):
        if line.startswith(" ") and logical:
            logical[-1] += line[1:]
        elif line.strip():
            logical.append(line)
    result: dict[str, str] = {}
    for line in logical:
        if ": " in line:
            key, value = line.split(": ", 1)
            result[key.strip()] = value
    return result


# --------------------------------------------------------------------------- 编译


def collect_sources(src_dir: Path) -> list[Path]:
    return sorted(p for p in src_dir.rglob("*.java") if p.is_file())


def copy_tree(src: Path, dst: Path) -> int:
    count = 0
    for f in sorted(src.rglob("*")):
        if not f.is_file():
            continue
        target = dst / f.relative_to(src)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, target)
        count += 1
    return count


def compile_java(project: dict, javac: str, sources: list[Path], extra_cp: list[Path]) -> None:
    classpath = os.pathsep.join([str(API_JAR), str(PF4J_JAR)] + [str(p) for p in extra_cp])
    rel_sources = [str(s.relative_to(ROOT)) for s in sources]  # 相对路径不含空格，规避命令行引号问题
    cmd = [
        javac,
        # 让 javac 自己也用 UTF-8 输出诊断信息（JDK 18+ 支持 stdout/stderr.encoding）
        "-J-Dfile.encoding=UTF-8",
        "-J-Dstdout.encoding=UTF-8",
        "-J-Dstderr.encoding=UTF-8",
        "--release", str(project.get("javaRelease", "21")),
        "-encoding", "UTF-8",
        "-g:source,lines,vars",
        "-Xlint:all,-serial,-this-escape,-classfile",
        "-cp", classpath,
        "-d", str(CLASSES.relative_to(ROOT)),
    ] + rel_sources

    log(f"  javac {len(rel_sources)} 个源文件 …")
    proc = subprocess.run(cmd, cwd=str(ROOT), capture_output=True)
    if proc.stdout.strip():
        log(_decode(proc.stdout).rstrip())
    if proc.stderr.strip():
        log(_decode(proc.stderr).rstrip())
    if proc.returncode != 0:
        die(f"javac 失败（exit {proc.returncode}）")


# --------------------------------------------------------------------------- 组装 / 打包


def stage_plugin(project: dict, manifest: bytes) -> None:
    if STAGE.exists():
        shutil.rmtree(STAGE)
    (STAGE / "META-INF").mkdir(parents=True, exist_ok=True)
    (STAGE / "classes").mkdir(parents=True, exist_ok=True)
    (STAGE / "lib").mkdir(parents=True, exist_ok=True)

    # --- classes/：编译产物 + 资源
    copy_tree(CLASSES, STAGE / "classes")

    # --- manifest：根目录一份（PF4J 读）+ classes/META-INF 一份（插件自己按 /META-INF/MANIFEST.MF 读版本号）
    (STAGE / "META-INF" / "MANIFEST.MF").write_bytes(manifest)
    classes_meta = STAGE / "classes" / "META-INF"
    classes_meta.mkdir(parents=True, exist_ok=True)
    (classes_meta / "MANIFEST.MF").write_bytes(manifest)

    # --- PF4J 扩展注册：两个文件都必须写
    idx = "# Generated by PF4J\n" + "".join(f"{c}\n" for c in project["extensionClasses"])
    (classes_meta / "extensions.idx").write_text(idx, encoding="utf-8", newline="\n")

    services = classes_meta / "services" / project["extensionPoint"]
    services.parent.mkdir(parents=True, exist_ok=True)
    services.write_text("".join(f"{c}\n" for c in project["extensionClasses"]), encoding="utf-8", newline="\n")

    # --- lib/：运行期依赖（compileOnly 的宿主 API 绝不进包）
    libdir = ROOT / project.get("libDir", "libs")
    lib_count = 0
    if libdir.exists():
        for jar in sorted(libdir.glob("*.jar")):
            shutil.copy2(jar, STAGE / "lib" / jar.name)
            lib_count += 1


def make_spmod(project: dict) -> Path:
    dist = ROOT / project.get("distDir", "build/dist")
    dist.mkdir(parents=True, exist_ok=True)
    out = dist / f"plugin-{project['pluginId']}-{project['version']}.spmod"
    if out.exists():
        out.unlink()

    files = [f for f in sorted(STAGE.rglob("*")) if f.is_file()]
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for f in files:
            arc = f.relative_to(STAGE).as_posix()
            info = zipfile.ZipInfo(arc, date_time=(2020, 1, 1, 0, 0, 0))  # 确定性构建
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, f.read_bytes())
    return out


def verify_spmod(spmod: Path, project: dict) -> list[str]:
    """重新打开产物做结构自检；返回问题清单（空 = 通过）。"""
    problems: list[str] = []
    want_main = "classes/" + project["mainClass"].replace(".", "/") + ".class"
    want_exts = ["classes/" + c.replace(".", "/") + ".class" for c in project["extensionClasses"]]

    with zipfile.ZipFile(spmod) as z:
        names = set(z.namelist())
        for required in [
            "META-INF/MANIFEST.MF",
            want_main,
            "classes/META-INF/MANIFEST.MF",
            "classes/META-INF/extensions.idx",
            "classes/META-INF/services/" + project["extensionPoint"],
        ] + want_exts:
            if required not in names:
                problems.append(f"缺少条目：{required}")

        if "META-INF/MANIFEST.MF" in names:
            root_manifest = parse_manifest(z.read("META-INF/MANIFEST.MF"))
            checks = {
                "Plugin-Class": project["mainClass"],
                "Plugin-Id": project["pluginId"],
                "Plugin-Version": str(project["version"]),
                "Plugin-Name": project["name"],
            }
            for key, expected in checks.items():
                actual = root_manifest.get(key)
                if actual != expected:
                    problems.append(f"manifest {key} = {actual!r}，期望 {expected!r}（折行可能出错）")
            if "classes/META-INF/MANIFEST.MF" in names:
                if z.read("META-INF/MANIFEST.MF") != z.read("classes/META-INF/MANIFEST.MF"):
                    problems.append("根 manifest 与 classes/META-INF/MANIFEST.MF 内容不一致")

        if "classes/META-INF/extensions.idx" in names:
            idx_lines = z.read("classes/META-INF/extensions.idx").decode("utf-8").splitlines()
            if not idx_lines or idx_lines[0] != "# Generated by PF4J":
                problems.append("extensions.idx 首行必须是 '# Generated by PF4J'")
            for cls in project["extensionClasses"]:
                if cls not in idx_lines:
                    problems.append(f"extensions.idx 未注册扩展类：{cls}")

        if "classes/META-INF/services/" + project["extensionPoint"] in names:
            svc = z.read("classes/META-INF/services/" + project["extensionPoint"]).decode("utf-8").split()
            for cls in project["extensionClasses"]:
                if cls not in svc:
                    problems.append(f"services 未注册扩展类：{cls}")

        if project.get("hasConfig") and "classes/preference_config.json" not in names:
            problems.append("Plugin-Has-Config=true 但包内没有 classes/preference_config.json（配置页会空白）")
        if "classes/preference_config.json" in names:
            try:
                json.loads(z.read("classes/preference_config.json").decode("utf-8"))
            except Exception as exc:  # noqa: BLE001
                problems.append(f"preference_config.json 不是合法 JSON：{exc}")

        # 打包污染检查：宿主 API / PF4J 绝不能进包
        for name in names:
            if name.startswith("classes/com/xuncorp/") or name.startswith("classes/org/pf4j/"):
                problems.append(f"包内混入宿主/PF4J 类：{name}")
    return problems


# --------------------------------------------------------------------------- main


BUILD_LOCK = BUILD / ".build.lock"
LOCK_STALE_SECONDS = 300.0


def _acquire_build_lock(timeout_s: float = 300.0):
    """构建互斥锁。

    本仓库可能被多个 agent / 终端同时构建：两个 build 会互相 `rmtree(build/classes)`、
    并发写 `build/stage` 与覆盖同一个 `.spmod`，产物可能损坏且错误信息会互相污染。
    用「独占创建 build/.build.lock」实现；超过 5 分钟的陈旧锁（多半是上次构建被强杀）
    自动接管，避免永久卡死。
    """
    BUILD.mkdir(parents=True, exist_ok=True)
    deadline = time.time() + timeout_s
    waited = False
    while True:
        try:
            fd = os.open(str(BUILD_LOCK), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            try:
                os.write(fd, f"pid={os.getpid()} at={time.time()}\n".encode("utf-8"))
            finally:
                os.close(fd)
            if waited:
                log("  构建锁已获得，继续构建。")
            return True
        except FileExistsError:
            try:
                age = time.time() - BUILD_LOCK.stat().st_mtime
            except OSError:
                age = 0.0
            if age > LOCK_STALE_SECONDS:
                log(f"  发现陈旧构建锁（{age:.0f}s 前），强制接管。")
                try:
                    BUILD_LOCK.unlink()
                except OSError:
                    pass
                continue
            if time.time() >= deadline:
                die("等待构建锁超时（另一处构建仍在进行？确认无构建后删除 build/.build.lock）")
                return False
            if not waited:
                log("  另一处构建正在进行，等待构建锁…")
                waited = True
            time.sleep(0.5)


def _release_build_lock(_flag) -> None:
    try:
        BUILD_LOCK.unlink()
    except OSError:
        pass


def main() -> int:
    parser = argparse.ArgumentParser(description="零 Gradle 构建：javac + 打 .spmod")
    parser.add_argument("--no-clean", action="store_true", help="不清理 build/classes 与 build/stage")
    args = parser.parse_args()
    _flag = _acquire_build_lock()
    try:
        return _run_build(args)
    finally:
        _release_build_lock(_flag)


def _run_build(args) -> int:
    t0 = time.time()
    project = load_project()
    log(f"== 构建 {project['pluginId']} v{project['version']} ({project['name']}) ==")

    ensure_api_jars()
    javac = find_javac()

    if not args.no_clean:
        for d in (CLASSES, STAGE):
            if d.exists():
                shutil.rmtree(d)
    CLASSES.mkdir(parents=True, exist_ok=True)
    log(f"  javac = {javac}")
    log(f"  API   = {API_JAR.name}  PF4J = {PF4J_JAR.name}")

    src_dir = ROOT / project["sourceDir"]
    if not src_dir.exists():
        die(f"源码目录不存在：{src_dir}")
    sources = collect_sources(src_dir)
    if not sources:
        die(f"{src_dir} 下没有 .java 文件")

    extra_cp = sorted((ROOT / project.get("libDir", "libs")).glob("*.jar")) if (ROOT / project.get("libDir", "libs")).exists() else []
    compile_java(project, javac, sources, extra_cp)

    res_dir = ROOT / project["resourceDir"]
    res_count = copy_tree(res_dir, CLASSES) if res_dir.exists() else 0
    log(f"  资源 {res_count} 个")

    manifest = build_manifest(project)
    stage_plugin(project, manifest)
    spmod = make_spmod(project)

    problems = verify_spmod(spmod, project)
    size = spmod.stat().st_size
    digest = hashlib.sha256(spmod.read_bytes()).hexdigest()

    log("")
    if problems:
        for p in problems:
            log(f"  ✗ {p}")
        die("产物自检未通过（见上）")
    log("  产物自检：全部通过 ✓")
    log(f"  .spmod  : {spmod}")
    log(f"  size    : {size} B")
    log(f"  sha256  : {digest}")
    log(f"  耗时    : {time.time() - t0:.1f}s")
    log("")
    log("  手动安装：把 .spmod 解压到")
    log("    %APPDATA%\\Salt Player for Windows\\workshop\\plugins\\plugin-{}-{}".format(project["pluginId"], project["version"]))
    log("  然后重启 Salt Player for Windows。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
