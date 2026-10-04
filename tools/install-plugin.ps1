<#
.SYNOPSIS
    SPW 网易云插件 · 安装到本机 Salt Player for Windows（手动安装路径）。

.DESCRIPTION
    流程（依据 docs/00 §3、docs/01 §3/§5、docs/06 §3）：
      1. 检查 SPW 是否在运行 —— 在运行则**拒绝安装**（宿主占用插件目录，会用 .oldPlugin 改名待删机制）。
      2. 定位 .spmod（缺省取 build\dist\ 下最新）。
      3. 目标目录 = <DataRoot>\workshop\plugins\plugin-<pluginId>-<version>\
      4. **同 Plugin-Id 的旧版本目录检查**（PF4J 以 Plugin-Id 为键，同 ID 只加载**先扫描到**的
         那个目录 → 旧目录留在原地会让新版本**静默不生效**，现象是 UI 与日志里的版本号仍是旧的）：
           · 存在旧版本且未加 -Force → **拒绝安装**（exit 6）并打印归档命令；
           · 存在旧版本且加了 -Force → 先把旧目录**归档**到 <DataRoot>\_archive\<目录名>-<时间戳>\
             （归档而非删除，便于回滚），再继续安装。
      5. 目标目录已存在 → 报告并提示先移除。用 -Force 同样先**归档**该目录再重装。
      6. 解压（逐个条目录入，防止 zip-slip 路径穿越）。
      7. 打印安装路径 + 「请重启 SPW 并到工坊启用」。

    本脚本**绝不**修改 enabled.txt（由宿主维护）。

.PARAMETER Spmod
    .spmod 路径。缺省取 build\dist\ 下最新。

.PARAMETER DataRoot
    用户数据根目录。缺省 $env:APPDATA\Salt Player for Windows。

.PARAMETER Force
    目标目录或同 Plugin-Id 的旧版本目录已存在时，先把它们**归档**到 <DataRoot>\_archive\<目录名>-<时间戳>\
    （归档而非删除，便于回滚），再解压安装。不加 -Force 时遇到此类残留会**拒绝安装**（exit 6）。

.PARAMETER DryRun
    只报告将要做什么，不实际写盘。

.PARAMETER SkipVerify
    跳过安装前的 verify-spmod.ps1 结构校验（默认会跑，FAIL 则拒绝安装）。

.EXAMPLE
    pwsh -File tools\install-plugin.ps1
    pwsh -File tools\install-plugin.ps1 -Spmod build\dist\plugin-com.example.netease-0.1.0.spmod -Force
#>
[CmdletBinding()]
param(
    [string] $Spmod,
    [string] $DataRoot,
    [switch] $Force,
    [switch] $DryRun,
    [switch] $SkipVerify
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

# 宿主进程名（任务要求精确匹配；另附标题兜底匹配）
$SpwProcessName = 'Salt Player for Windows'

$RepoRoot    = Split-Path -Parent $PSScriptRoot
$ProjectJson = Join-Path $RepoRoot 'project.json'
$DistDir     = Join-Path $RepoRoot 'build\dist'
$VerifyScript = Join-Path $PSScriptRoot 'verify-spmod.ps1'

function Write-Step  { param([string]$m) Write-Host ''; Write-Host "▶ $m" -ForegroundColor Cyan }
function Write-Ok    { param([string]$m) Write-Host "  ✓ $m" -ForegroundColor Green }
function Write-Warn2 { param([string]$m) Write-Host "  ! $m" -ForegroundColor Yellow }
function Write-Err   { param([string]$m) Write-Host "  ✗ $m" -ForegroundColor Red }

Write-Host ''
Write-Host '════════════════════════════════════════════════════════════════' -ForegroundColor Cyan
Write-Host ' 安装 SPW 网易云插件（手动安装路径）' -ForegroundColor Cyan
Write-Host '════════════════════════════════════════════════════════════════' -ForegroundColor Cyan

# ---------------------------------------------------------------- 0. 先定 DataRoot
# 必须早于进程检查：只有当【宿主确实占用这个目录】时才该拒绝安装。
# 显式指定了别的 -DataRoot（测试用临时根、多环境）时，正在运行的宿主根本不碰那个目录，
# 一律拒绝会让脚本在宿主常驻时彻底不可测、不可用。
$defaultDataRoot = Join-Path $env:APPDATA 'Salt Player for Windows'
if (-not $DataRoot) { $DataRoot = $defaultDataRoot }
$DataRoot = [System.IO.Path]::GetFullPath($DataRoot)
$sameAsHostRoot = ($DataRoot.TrimEnd('\') -ieq ([System.IO.Path]::GetFullPath($defaultDataRoot)).TrimEnd('\'))

# ---------------------------------------------------------------- 1. 宿主进程检查
Write-Step '1/6 检查 Salt Player for Windows 是否在运行'

$procs = @(Get-Process -Name $SpwProcessName -ErrorAction SilentlyContinue)
if ($procs.Count -eq 0) {
    # 兜底：按 MainWindowTitle / Path 匹配（不同发行渠道进程名可能带后缀）
    $procs = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
        ($_.MainWindowTitle -like '*Salt Player*') -or
        ($_.Path -and $_.Path -like '*Salt Player for Windows*')
    })
}

if ($procs.Count -gt 0) {
    Write-Err "检测到 Salt Player for Windows 正在运行（$($procs.Count) 个进程）："
    foreach ($p in $procs) {
        Write-Host ("      PID {0,-7} {1}" -f $p.Id, $p.ProcessName) -ForegroundColor DarkGray
    }
    if ($DryRun) {
        # -DryRun 一个字节都不写，没必要拒绝：宿主常驻时若不放过 DryRun，
        # 这个「只看一眼会装到哪」的开关就永远用不了。
        Write-Warn2 '-DryRun：宿主在运行，但本次不写盘 → 继续只读预演（真正安装仍会被拒绝）'
    } elseif (-not $sameAsHostRoot) {
        # 宿主占用的是它自己的默认 DataRoot；装到别处与运行中的宿主无关。
        Write-Warn2 '宿主在运行，但目标 DataRoot 不是宿主使用的默认目录：'
        Write-Host  "      目标     : $DataRoot" -ForegroundColor DarkGray
        Write-Host  "      宿主默认 : $defaultDataRoot" -ForegroundColor DarkGray
        Write-Warn2 '宿主不会占用该目录 → 继续安装'
    } else {
        Write-Host ''
        Write-Host '  拒绝安装：宿主占用插件目录，写入会被 .oldPlugin 改名待删机制接管，导致' -ForegroundColor Red
        Write-Host '  新旧目录共存 → PF4J 只加载先扫描到的那个 → 新版本静默不生效。' -ForegroundColor Red
        Write-Host ''
        Write-Host '  请先**完全退出** Salt Player for Windows（含托盘图标 → 右键退出），再重跑本脚本。' -ForegroundColor Yellow
        Write-Host ''
        exit 1
    }
} else {
    Write-Ok '宿主未运行'
}

# ---------------------------------------------------------------- 2. 定位产物 + 元数据
Write-Step '2/6 定位产物与读取 project.json'

if (-not (Test-Path -LiteralPath $ProjectJson)) {
    Write-Err "找不到 project.json：$ProjectJson"
    exit 2
}
$pj = Get-Content -LiteralPath $ProjectJson -Raw -Encoding UTF8 | ConvertFrom-Json
$pluginId = [string]$pj.pluginId
$pluginVer = [string]$pj.version
Write-Ok "project.json: id=$pluginId  version=$pluginVer"

if (-not $Spmod) {
    if (-not (Test-Path -LiteralPath $DistDir)) {
        Write-Err "build\dist 不存在（先跑 python tools\build.py）"
        exit 2
    }
    $cand = @(Get-ChildItem -LiteralPath $DistDir -Filter '*.spmod' -File | Sort-Object LastWriteTime -Descending)
    if ($cand.Count -eq 0) {
        Write-Err "build\dist 下没有 *.spmod（先跑 python tools\build.py）"
        exit 2
    }
    $Spmod = $cand[0].FullName
}
if (-not (Test-Path -LiteralPath $Spmod)) {
    Write-Err "找不到 .spmod：$Spmod"
    exit 2
}
$SpmodFull = (Resolve-Path -LiteralPath $Spmod).Path
Write-Ok "产物: $SpmodFull"
Write-Host ("      {0:N0} bytes, 修改时间 {1:yyyy-MM-dd HH:mm:ss}" -f (Get-Item -LiteralPath $SpmodFull).Length, (Get-Item -LiteralPath $SpmodFull).LastWriteTime) -ForegroundColor DarkGray

# ---------------------------------------------------------------- 3. 结构校验
Write-Step '3/6 安装前结构校验（tools\verify-spmod.ps1）'

if ($SkipVerify) {
    Write-Warn2 '-SkipVerify：跳过结构校验（不推荐）'
} elseif (Test-Path -LiteralPath $VerifyScript) {
    & pwsh -NoProfile -File $VerifyScript -Spmod $SpmodFull
    $vrc = $LASTEXITCODE
    if ($vrc -ne 0) {
        Write-Host ''
        Write-Err "结构校验未通过（verify-spmod.ps1 退出码 $vrc）→ 拒绝安装。"
        Write-Host '      修好产物后重试；确要强行安装请加 -SkipVerify。' -ForegroundColor Yellow
        exit 3
    }
    Write-Ok '结构校验全部 PASS'
} else {
    Write-Warn2 "找不到 $VerifyScript，跳过校验"
}

# ---------------------------------------------------------------- 4. 目标目录
Write-Step '4/6 计算目标目录'

$pluginsRoot = Join-Path $DataRoot 'workshop\plugins'
$dirName     = "plugin-$pluginId-$pluginVer"
$targetDir   = Join-Path $pluginsRoot $dirName

Write-Ok "DataRoot   : $DataRoot"
Write-Host "      插件根目录 : $pluginsRoot" -ForegroundColor DarkGray
Write-Host "      目标目录   : $targetDir" -ForegroundColor DarkGray

if (-not (Test-Path -LiteralPath $pluginsRoot)) {
    Write-Warn2 "插件根目录不存在，将创建：$pluginsRoot"
}

# 同 ID 目录扫描（PF4J 以 Plugin-Id 为键，只加载**先被扫描到**的那个目录）
$siblings = @()
if (Test-Path -LiteralPath $pluginsRoot) {
    $siblings = @(Get-ChildItem -LiteralPath $pluginsRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "plugin-$pluginId-*" })
}
$staleSiblings = @($siblings | Where-Object { $_.FullName -ne $targetDir })
$archiveRoot   = Join-Path $DataRoot '_archive'
if ($siblings.Count -gt 0) {
    Write-Host ''
    Write-Host '  扫描到同 Plugin-Id 的目录：' -ForegroundColor Yellow
    foreach ($s in $siblings) {
        $mark = if ($s.FullName -eq $targetDir) { '← 本次目标' } else { '← 旧版本，会让新版静默不生效' }
        Write-Host ("      {0}  {1}" -f $s.Name, $mark) -ForegroundColor Yellow
    }
}

# 旧版本目录处理：不加 -Force 直接拒绝（这是「装完还是旧版本」的真根因，不能只警告了事）
if ($staleSiblings.Count -gt 0) {
    Write-Host ''
    if ($DryRun) {
        Write-Warn2 "-DryRun：检测到 $($staleSiblings.Count) 个同 Plugin-Id 的旧版本目录（正式安装需 -Force 归档）"
        Write-Host "      归档目录：$archiveRoot" -ForegroundColor DarkGray
    } elseif ($Force) {
        Write-Step '4b/6 归档同 Plugin-Id 的旧版本目录（-Force）'
        if (-not (Test-Path -LiteralPath $archiveRoot)) {
            New-Item -ItemType Directory -Force -Path $archiveRoot | Out-Null
        }
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        foreach ($s in $staleSiblings) {
            $dest = Join-Path $archiveRoot ("{0}-{1}" -f $s.Name, $stamp)
            Move-Item -LiteralPath $s.FullName -Destination $dest
            Write-Ok "已归档：$($s.Name) → $dest"
        }
    } else {
        Write-Err "检测到 $($staleSiblings.Count) 个同 Plugin-Id 的旧版本目录，新版本会被宿主忽略："
        foreach ($s in $staleSiblings) { Write-Host "      $($s.Name)" -ForegroundColor Yellow }
        Write-Host ''
        Write-Host '  为什么必须处理：PF4J 以 Plugin-Id 为键，同 ID 只加载**先被扫描到**的那个目录。' -ForegroundColor Yellow
        Write-Host '  旧目录留在原地时，宿主会继续加载旧代码 —— 新版本静默不生效（现象：UI 与日志里仍是旧版本号）。' -ForegroundColor Yellow
        Write-Host ''
        Write-Host '  请二选一：' -ForegroundColor Yellow
        Write-Host "    a) 手动归档旧目录：Move-Item '$($staleSiblings[0].FullName)' '$archiveRoot'" -ForegroundColor Yellow
        Write-Host '    b) 重跑本脚本并加 -Force（本脚本会把旧目录归档到上面那个 _archive 目录）' -ForegroundColor Yellow
        Write-Host ''
        exit 6
    }
}

# ---------------------------------------------------------------- 5. 已存在的目标目录
Write-Step '5/6 处理已存在的目标目录'

$targetExists = Test-Path -LiteralPath $targetDir
if ($targetExists) {
    if ($Force) {
        $dest = Join-Path $archiveRoot ("{0}-replace-{1}" -f $dirName, (Get-Date -Format 'yyyyMMdd-HHmmss'))
        Write-Warn2 "-Force：归档已存在的目标目录 → $dest"
        if (-not $DryRun) {
            if (-not (Test-Path -LiteralPath $archiveRoot)) {
                New-Item -ItemType Directory -Force -Path $archiveRoot | Out-Null
            }
            Move-Item -LiteralPath $targetDir -Destination $dest
        }
    } else {
        Write-Host ''
        Write-Err "目标目录已存在：$targetDir"
        Write-Host ''
        Write-Host '  为什么必须删掉：PF4J 以 Plugin-Id 为键，同 ID 只加载**先被扫描到**的那个目录。' -ForegroundColor Yellow
        Write-Host '  如果留下旧目录（或解压覆盖产生半新半旧），宿主可能继续加载旧代码 → 新版本静默不生效。' -ForegroundColor Yellow
        Write-Host ''
        Write-Host '  请二选一：' -ForegroundColor Yellow
        Write-Host "    a) 手动移除旧目录：Remove-Item -Recurse -Force '$targetDir'" -ForegroundColor Yellow
        Write-Host '    b) 重跑本脚本并加 -Force（本脚本会先删后装）' -ForegroundColor Yellow
        Write-Host ''
        exit 4
    }
} else {
    Write-Ok '目标目录不存在（干净安装）'
}

if ($DryRun) {
    Write-Host ''
    Write-Host '  -DryRun：到此为止，未写盘。' -ForegroundColor Cyan
    exit 0
}

# ---------------------------------------------------------------- 6. 解压
Write-Step '6/6 解压安装'

Add-Type -AssemblyName System.IO.Compression.FileSystem

New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

$zip = [System.IO.Compression.ZipFile]::OpenRead($SpmodFull)
$fileCount = 0
$totalBytes = 0L
$skipped = 0
try {
    foreach ($e in $zip.Entries) {
        if ($e.FullName.EndsWith('/')) { continue }   # 目录条目
        $rel = $e.FullName.Replace('\', '/')

        # zip-slip 防护
        if ($rel.StartsWith('/') -or $rel.Split('/') -contains '..') {
            Write-Warn2 "跳过可疑条目：$rel"
            $skipped++
            continue
        }

        $dest = Join-Path $targetDir ($rel.Replace('/', [IO.Path]::DirectorySeparatorChar))
        $destDir = Split-Path -Parent $dest
        if ($destDir -and -not (Test-Path -LiteralPath $destDir)) {
            New-Item -ItemType Directory -Force -Path $destDir | Out-Null
        }

        $s = $e.Open()
        $fs = [System.IO.File]::Open($dest, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try { $s.CopyTo($fs); $totalBytes += $e.Length } finally { $fs.Dispose(); $s.Dispose() }
        $fileCount++
    }
} finally {
    $zip.Dispose()
}

Write-Ok "解压完成：$fileCount 个文件，$('{0:N0}' -f $totalBytes) bytes"
if ($skipped -gt 0) { Write-Warn2 "跳过 $skipped 个可疑条目" }

# 安装后静默复校：关键结构真的落地了吗
$sanity = @(
    (Join-Path $targetDir 'META-INF\MANIFEST.MF'),
    (Join-Path $targetDir 'classes\META-INF\MANIFEST.MF'),
    (Join-Path $targetDir 'classes\META-INF\extensions.idx')
)
$sanityOk = $true
foreach ($f in $sanity) {
    if (-not (Test-Path -LiteralPath $f)) {
        Write-Err "安装后缺失关键文件：$f"
        $sanityOk = $false
    }
}
if (-not $sanityOk) { exit 5 }
Write-Ok '安装后关键文件复校通过（两份 manifest + extensions.idx）'

# ---------------------------------------------------------------- 收尾
Write-Host ''
Write-Host '════════════════════════════════════════════════════════════════' -ForegroundColor Green
Write-Host ' 安装完成' -ForegroundColor Green
Write-Host '════════════════════════════════════════════════════════════════' -ForegroundColor Green
Write-Host ''
Write-Host '  安装路径：' -NoNewline; Write-Host $targetDir -ForegroundColor White
Write-Host ''
Write-Host '  下一步：' -ForegroundColor Cyan
Write-Host '    1) 启动 Salt Player for Windows'
Write-Host '    2) 打开「工坊 / Mod 管理」，启用「' -NoNewline
Write-Host ([string]$pj.name) -ForegroundColor White -NoNewline
Write-Host '」'
Write-Host '    3) 若界面提示「需要重启应用以加载插件」，按提示再重启一次'
Write-Host '    4) 确认 toast 弹出、Mod 配置页可打开'
Write-Host ''
Write-Host "  插件日志：$(Join-Path $DataRoot "workshop\data\$pluginId\logs\")" -ForegroundColor DarkGray
Write-Host "  宿主日志：$(Join-Path $DataRoot 'logs.txt')（只读，用于确认加载失败原因）" -ForegroundColor DarkGray
Write-Host ''
Write-Host '  注意：enabled.txt 由宿主维护，本脚本未改动它。' -ForegroundColor DarkGray
Write-Host ''

exit 0
