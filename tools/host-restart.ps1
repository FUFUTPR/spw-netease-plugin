<#
.SYNOPSIS
    重启宿主 Salt Player for Windows（插件开发 / 真机验收用），可选「冷轮」清封面缓存、可选装包。

.DESCRIPTION
    tools\install-plugin.ps1 要求宿主**完全退出**才肯安装（宿主占用插件目录会让新版本静默不
    生效），所以「装包 → 真机复测」的必经步骤是：停宿主 → 装包 → 启宿主。本脚本把这三步与
    「冷轮清缓存」合成一条命令，避免每次手敲（此前几轮都是临时手打，容易漏步骤）。

    冷轮（-ColdCover）：把 <插件数据>\cover 整目录**改名归档**为 cover.bak-<时间戳>，不删除
    （1 GB 级目录在同卷改名是瞬时的，且可回滚）。下一次启动 ⇒ cover-refs.json 也没了 ⇒ 封面
    清单只能等同步拉到歌单才出现，这就是 A11「封面首轮全量（约 2000 张）」的最坏场景。

    用户偏好（m05962，长期有效）：开发期启动宿主**一律开在副屏**，别占主屏（用户常在同机玩游戏）。

.PARAMETER ColdCover
    冷轮：归档 <插件数据>\cover（img / stub / placeholder / cover-index.json / cover-refs.json 全没）。

.PARAMETER Install
    停完宿主后跑 tools\install-plugin.ps1 -Force（自动归档同 Plugin-Id 的旧版本目录）。

.PARAMETER StartOnly
    只启动（不动缓存、不装包）。

.EXAMPLE
    pwsh -File tools\host-restart.ps1 -ColdCover -Install
#>
[CmdletBinding()]
param(
    [switch] $ColdCover,
    [switch] $Install,
    [switch] $StartOnly,
    [int]    $StopWaitSeconds = 25,
    [string] $HostExe = 'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\Salt Player for Windows.exe'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

$RepoRoot   = Split-Path -Parent $PSScriptRoot
$ProcName   = 'Salt Player for Windows'
$DataRoot   = Join-Path $env:APPDATA 'Salt Player for Windows'
$PluginId   = 'com.example.netease'
$Link       = Join-Path $RepoRoot 'tools\.cache\spwdata-link'

function Step { param([string] $m) Write-Host ''; Write-Host "▶ $m" -ForegroundColor Cyan }
function Ok   { param([string] $m) Write-Host "  ✓ $m" -ForegroundColor Green }
function Warn { param([string] $m) Write-Host "  ! $m" -ForegroundColor Yellow }

# 数据目录取法：优先仓库内 junction（本机实测：工具进程直接读 %APPDATA% 下的该路径会间歇性
# 「路径不存在」，经 junction 读则稳定 —— 见项目笔记「封面工作目录锚定 tools\.cache\spwdata-link」）。
$DataDir = Join-Path $DataRoot "workshop\data\$PluginId"
if (Test-Path -LiteralPath (Join-Path $Link 'logs')) {
    $DataDir = $Link
    Ok "数据目录（经 junction）：$DataDir"
} else {
    Warn "junction 不可用，退回直连：$DataDir"
}

# ---------------------------------------------------------------- 1. 停宿主
if (-not $StartOnly) {
    Step "1/4 停止 $ProcName（先请它自己退出，超时再强杀）"
    $procs = @(Get-Process -Name $ProcName -ErrorAction SilentlyContinue)
    if ($procs.Count -eq 0) {
        Ok '宿主本来就没在跑'
    } else {
        foreach ($p in $procs) { [void]$p.CloseMainWindow() }
        $deadline = (Get-Date).AddSeconds($StopWaitSeconds)
        do {
            Start-Sleep -Milliseconds 500
            $left = @(Get-Process -Name $ProcName -ErrorAction SilentlyContinue)
        } while ($left.Count -gt 0 -and (Get-Date) -lt $deadline)
        if ($left.Count -gt 0) {
            Warn "等 $StopWaitSeconds s 仍未退出 → Stop-Process -Force"
            $left | Stop-Process -Force
            Start-Sleep -Seconds 3
        }
        $left = @(Get-Process -Name $ProcName -ErrorAction SilentlyContinue)
        if ($left.Count -gt 0) { Write-Error "宿主仍未退出（$($left.Count) 个进程），中止"; exit 1 }
        Ok '宿主已退出'
    }

    # ------------------------------------------------------------ 2. 冷轮清封面缓存
    if ($ColdCover) {
        Step '2/4 冷轮：归档封面缓存'
        $cover = Join-Path $DataDir 'cover'
        if (Test-Path -LiteralPath $cover) {
            $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
            $dest  = Join-Path $DataDir "cover.bak-cold-$stamp"
            Move-Item -LiteralPath $cover -Destination $dest
            Ok "已归档：cover → $(Split-Path -Leaf $dest)"
        } else {
            Warn "封面缓存目录不存在（已经是冷的）：$cover"
        }
    }

    # ------------------------------------------------------------ 3. 装包
    if ($Install) {
        Step '3/4 安装插件包（-Force）'
        & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'install-plugin.ps1') -Force
        if ($LASTEXITCODE -ne 0) { Write-Error "install-plugin.ps1 退出码 $LASTEXITCODE"; exit $LASTEXITCODE }
        Ok '装包完成'
    }
}

# ---------------------------------------------------------------- 4. 启宿主（副屏）
Step '4/4 启动宿主并挪到副屏'
if (-not (Test-Path -LiteralPath $HostExe)) { Write-Error "找不到宿主：$HostExe"; exit 4 }
& pwsh -NoProfile -File (Join-Path $PSScriptRoot 'host-window.ps1') -Start
$rc = $LASTEXITCODE
Ok "host-window.ps1 退出码 $rc"

$log = Join-Path $DataDir 'logs'
Write-Host ''
Write-Host "插件日志目录：$log" -ForegroundColor DarkGray
exit 0
