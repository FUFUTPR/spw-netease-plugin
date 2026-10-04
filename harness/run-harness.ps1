<#
.SYNOPSIS
    SPW 网易云插件 · 离线加载 harness 运行器。

.DESCRIPTION
    在**普通 JVM**（无宿主 GUI、无宿主内部类）里用
    `com.xuncorp.spw.workshop.api.WorkshopPluginManager`（extends org.pf4j.DefaultPluginManager）
    加载 `.spmod` 解压出的插件目录，走完整链路：

      解压产物 → 编译 harness → 创建 PluginManager → 注入桩 WorkshopApi
      → loadPlugins → descriptor 读 Plugin-Id/Version
      → getExtensions(PlaybackExtensionPoint) 非空
      → startPlugin → 宿主回调桩（onStateChanged / onIsPlayingChanged /
        onSeekTo / onPositionUpdated）→ stopPlugin
      → 输出 PASS/FAIL 清单，任一 FAIL 退出码非 0。

    依赖（全部在 tools\.cache\，只读，不得进插件包）：
      spw-workshop-api-host.jar  宿主 API（19 class）
      pf4j-3.12.0.jar            PF4J
      kotlin-stdlib-host.jar     从宿主 ffmpeg-x64.dll 抽出的 kotlin-stdlib（API 类引用 kotlin.Metadata）
      slf4j-host.jar             从宿主抽出的 slf4j（含 simple provider）

.PARAMETER Spmod
    .spmod 路径。缺省取 build\dist\ 下最新。

.PARAMETER KeepWork
    保留临时工作目录（排错用）。

.PARAMETER SkipVerify
    跳过前置的 verify-spmod.ps1 结构校验。

.PARAMETER TimeoutSec
    单个插件生命周期阶段的超时（秒），缺省 30。超时会报 FAIL 而不是挂死。

.PARAMETER DataRoot
    插件数据目录的**沙箱根**（充当 JVM 的 %APPDATA%），缺省 harness\tmp\appdata。
    必须先沙箱化：插件 DataPaths 直接读 System.getenv("APPDATA")，不沙箱就会写进
    真机宿主的数据目录（污染现场、取证不可信）。每次运行前清空。

.PARAMETER LogPath
    本次 harness 完整输出的落盘路径（证据留存），缺省 harness\logs\last-run.txt。

.EXAMPLE
    pwsh -File harness\run-harness.ps1
    pwsh -File harness\run-harness.ps1 -Spmod build\dist\plugin-com.example.netease-0.2.0.spmod -KeepWork
#>
[CmdletBinding()]
param(
    [string] $Spmod,
    [switch] $KeepWork,
    [switch] $SkipVerify,
    [int]    $TimeoutSec = 30,
    [string] $DataRoot,
    [string] $LogPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

# 让 pwsh 侧以 UTF-8 收发，避免插件中文日志乱码
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$RepoRoot   = Split-Path -Parent $PSScriptRoot
$HarnessDir = $PSScriptRoot
$CacheDir   = Join-Path $RepoRoot 'tools\.cache'
$DistDir    = Join-Path $RepoRoot 'build\dist'
$BuildDir   = Join-Path $HarnessDir 'build'          # harness 的 .class 输出
$SrcDir     = Join-Path $HarnessDir 'src'
$WorkRoot   = Join-Path $HarnessDir 'work'           # 临时插件目录（harness 独占）

$Javac = 'javac'
$Java  = 'java'

function Fail-Hard { param([string]$m) Write-Host ''; Write-Host "✗ $m" -ForegroundColor Red; Write-Host ''; exit 1 }

Write-Host ''
Write-Host '════════════════════════════════════════════════════════════════' -ForegroundColor Cyan
Write-Host ' SPW 插件 · 离线加载 harness（普通 JVM）' -ForegroundColor Cyan
Write-Host '════════════════════════════════════════════════════════════════' -ForegroundColor Cyan

# ---------------------------------------------------------------- 依赖检查
# 注意：不要用 slf4j-host.jar（从宿主 ffmpeg-x64.dll 抽出的 org/slf4j/**）——它缺
# META-INF/services/org.slf4j.spi.SLF4JServiceProvider，SLF4J 会退化成 NOP logger，
# 于是 PF4J 自己的 error 日志（"Cannot load class …" 之类）被静默吞掉，排查扩展点为空时会被误导。
# 这里用 Maven Central 的官方 slf4j-api + slf4j-simple，provider 是真的。
$needJars = @(
    'spw-workshop-api-host.jar',
    'pf4j-3.12.0.jar',
    'kotlin-stdlib-host.jar',
    'slf4j-api-2.0.13.jar',
    'slf4j-simple-2.0.13.jar'
)
$cpParts = @()
foreach ($j in $needJars) {
    $p = Join-Path $CacheDir $j
    if (-not (Test-Path -LiteralPath $p)) { Fail-Hard "缺少依赖 $p（tools\.cache\ 下的只读依赖，请勿删除）" }
    $cpParts += $p
}
$HarnessCp = ($cpParts -join ';')

foreach ($exe in @($Javac, $Java)) {
    if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) { Fail-Hard "PATH 里找不到 $exe" }
}

Write-Host ("  javac : " + (& $Javac -version 2>&1)) -ForegroundColor DarkGray
Write-Host ("  java  : " + (& $Java -version 2>&1 | Select-Object -First 1)) -ForegroundColor DarkGray

# ---------------------------------------------------------------- 定位产物
if (-not $Spmod) {
    if (-not (Test-Path -LiteralPath $DistDir)) { Fail-Hard "build\dist 不存在（先跑 python tools\build.py）" }
    $cand = @(Get-ChildItem -LiteralPath $DistDir -Filter '*.spmod' -File | Sort-Object LastWriteTime -Descending)
    if ($cand.Count -eq 0) { Fail-Hard "build\dist 下没有 *.spmod（先跑 python tools\build.py）" }
    $Spmod = $cand[0].FullName
}
if (-not (Test-Path -LiteralPath $Spmod)) { Fail-Hard "找不到 .spmod：$Spmod" }
$SpmodFull = (Resolve-Path -LiteralPath $Spmod).Path
$SpmodSha  = (Get-FileHash -LiteralPath $SpmodFull -Algorithm SHA256).Hash.ToLower()
$SpmodSize = (Get-Item -LiteralPath $SpmodFull).Length
Write-Host ''
Write-Host "  产物: $SpmodFull" -ForegroundColor White
Write-Host "  sha256: $SpmodSha" -ForegroundColor DarkGray
Write-Host "  大小  : $SpmodSize B" -ForegroundColor DarkGray

# ---------------------------------------------------------------- 前置结构校验
if (-not $SkipVerify) {
    $VerifyScript = Join-Path $RepoRoot 'tools\verify-spmod.ps1'
    if (Test-Path -LiteralPath $VerifyScript) {
        Write-Host '  前置：verify-spmod.ps1 …' -ForegroundColor DarkGray
        & pwsh -NoProfile -File $VerifyScript -Spmod $SpmodFull | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Host ''
            Write-Host "  ! 结构校验未通过（退出码 $LASTEXITCODE）；仍继续跑 harness 以便看到运行期错误。" -ForegroundColor Yellow
        } else {
            Write-Host '  前置：结构校验 PASS' -ForegroundColor DarkGray
        }
    }
}

# ---------------------------------------------------------------- 编译 harness
if (-not (Test-Path -LiteralPath $SrcDir)) { Fail-Hard "找不到 harness 源码目录 $SrcDir" }

Write-Host ''
Write-Host '▶ 编译 harness' -ForegroundColor Cyan
if (Test-Path -LiteralPath $BuildDir) { Remove-Item -LiteralPath $BuildDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

$srcs = @(Get-ChildItem -LiteralPath $SrcDir -Filter '*.java' -File -Recurse | ForEach-Object { $_.FullName })
if ($srcs.Count -eq 0) { Fail-Hard "$SrcDir 下没有 .java" }

$javacOut = & $Javac -encoding UTF-8 -g -nowarn -d $BuildDir -cp $HarnessCp @srcs 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host ($javacOut | Out-String) -ForegroundColor Red
    Fail-Hard "javac 编译失败（退出码 $LASTEXITCODE）"
}
Write-Host "  ✓ 编译通过（$($srcs.Count) 个源文件 → $BuildDir）" -ForegroundColor Green

# ---------------------------------------------------------------- 解压产物到临时插件目录
Write-Host ''
Write-Host '▶ 解压产物到临时插件目录' -ForegroundColor Cyan

# 读取 project.json 拿 pluginId（目录名要含 id-version）
$pj = Get-Content -LiteralPath (Join-Path $RepoRoot 'project.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$pluginId  = [string]$pj.pluginId
$pluginVer = [string]$pj.version

if (Test-Path -LiteralPath $WorkRoot) { Remove-Item -LiteralPath $WorkRoot -Recurse -Force }
$pluginDir = Join-Path $WorkRoot "plugin-$pluginId-$pluginVer"
New-Item -ItemType Directory -Force -Path $pluginDir | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($SpmodFull)
$n = 0
try {
    foreach ($e in $zip.Entries) {
        if ($e.FullName.EndsWith('/')) { continue }
        $rel = $e.FullName.Replace('\', '/')
        if ($rel.StartsWith('/') -or $rel.Split('/') -contains '..') { continue }
        $dest = Join-Path $pluginDir ($rel.Replace('/', [IO.Path]::DirectorySeparatorChar))
        $dd = Split-Path -Parent $dest
        if ($dd -and -not (Test-Path -LiteralPath $dd)) { New-Item -ItemType Directory -Force -Path $dd | Out-Null }
        $s = $e.Open()
        $fs = [System.IO.File]::Open($dest, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try { $s.CopyTo($fs) } finally { $fs.Dispose(); $s.Dispose() }
        $n++
    }
} finally { $zip.Dispose() }
Write-Host "  ✓ $n 个文件 → $pluginDir" -ForegroundColor Green

# ---------------------------------------------------------------- 数据目录沙箱
# 插件 DataPaths.init() 第一件事就是 System.getenv("APPDATA")；不换掉它，
# harness 会把配置/日志/凭据写进**真机宿主**的数据目录 —— 既污染现场，又让
# 「数据目录无明文」这类取证结论不可信。这里把 JVM 的 APPDATA 指向沙箱。
if (-not $DataRoot) { $DataRoot = Join-Path $HarnessDir 'tmp\appdata' }
elseif (-not [System.IO.Path]::IsPathRooted($DataRoot)) { $DataRoot = Join-Path $RepoRoot $DataRoot }
$DataRoot = [System.IO.Path]::GetFullPath($DataRoot)

if (-not $LogPath) { $LogPath = Join-Path $HarnessDir 'logs\last-run.txt' }
elseif (-not [System.IO.Path]::IsPathRooted($LogPath)) { $LogPath = Join-Path $RepoRoot $LogPath }
$LogPath = [System.IO.Path]::GetFullPath($LogPath)

if (Test-Path -LiteralPath $DataRoot) { Remove-Item -LiteralPath $DataRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $DataRoot | Out-Null
$logDir = Split-Path -Parent $LogPath
if ($logDir -and -not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
$SandboxDataDir = Join-Path $DataRoot "Salt Player for Windows\workshop\data\$pluginId"

Write-Host ''
Write-Host '▶ 数据目录沙箱' -ForegroundColor Cyan
Write-Host "  APPDATA(沙箱) : $DataRoot" -ForegroundColor DarkGray
Write-Host "  插件数据目录  : $SandboxDataDir" -ForegroundColor DarkGray
Write-Host "  输出落盘      : $LogPath" -ForegroundColor DarkGray

# ---------------------------------------------------------------- 运行 harness
Write-Host ''
Write-Host '▶ 运行 harness（JVM 内加载插件）' -ForegroundColor Cyan
Write-Host ('─' * 64) -ForegroundColor DarkGray

$runCp = "$BuildDir;$HarnessCp"
$javaArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    '-Dorg.slf4j.simpleLogger.defaultLogLevel=info',
    '-Dorg.slf4j.simpleLogger.showThreadName=true',
    "-Dharness.pluginDir=$pluginDir",
    "-Dharness.pluginId=$pluginId",
    "-Dharness.pluginVersion=$pluginVer",
    "-Dharness.workDir=$WorkRoot",
    "-Dharness.timeoutSec=$TimeoutSec",
    "-Dharness.appData=$DataRoot",
    "-Dharness.dataDir=$SandboxDataDir",
    "-Dharness.spmodSha256=$SpmodSha",
    '-cp', $runCp,
    'spw.harness.HarnessMain'
)

$oldAppData = $env:APPDATA
$env:APPDATA = $DataRoot
try {
    # Tee 到日志文件的同时把输出透传到控制台（不要 Out-Null，否则控制台什么都看不到）
    & $Java @javaArgs 2>&1 | ForEach-Object { $_ } | Tee-Object -FilePath $LogPath
} finally {
    $env:APPDATA = $oldAppData
}
$rc = $LASTEXITCODE

Write-Host ('─' * 64) -ForegroundColor DarkGray

if (-not $KeepWork) {
    # 保留失败现场更利于排错
    if ($rc -eq 0 -and (Test-Path -LiteralPath $WorkRoot)) {
        Remove-Item -LiteralPath $WorkRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
} else {
    Write-Host "  临时目录已保留：$WorkRoot" -ForegroundColor DarkGray
}

Write-Host ''
if ($rc -eq 0) {
    Write-Host '════════════════════════════════════════════════════════════════' -ForegroundColor Green
    Write-Host ' harness 全部 PASS' -ForegroundColor Green
    Write-Host '════════════════════════════════════════════════════════════════' -ForegroundColor Green
} else {
    Write-Host '════════════════════════════════════════════════════════════════' -ForegroundColor Red
    Write-Host " harness 存在 FAIL（退出码 $rc）" -ForegroundColor Red
    Write-Host '════════════════════════════════════════════════════════════════' -ForegroundColor Red
    Write-Host "  复跑：pwsh -File harness\run-harness.ps1 -KeepWork -SkipVerify" -ForegroundColor DarkGray
}
Write-Host ''
exit $rc
