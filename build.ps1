<#
.SYNOPSIS
    一键构建：抽取宿主 API → javac 编译 → 打 .spmod 包。

.EXAMPLE
    pwsh -File build.ps1
    pwsh -File build.ps1 -SkipApi          # 已抽过 API，跳过抽取
    pwsh -File build.ps1 -ForceApi         # 强制重新抽取宿主 API
#>
[CmdletBinding()]
param(
    [switch] $SkipApi,
    [switch] $ForceApi,
    [string] $HostJar
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (-not $root) { $root = (Get-Location).Path }

$started = Get-Date

# ---------------------------------------------------------------- 0. 统一 JDK
# 宿主 1.19.0 的 API class 是 Java 25（class 69）；JDK 21 的 javac 读不了。
# 这里显式解析并把 JAVA_HOME / PATH 固定成同一个 JDK，构建与 harness 共用。
. (Join-Path $root 'tools\jdk.ps1')
$jdk = Resolve-RepoJdk
$env:JAVA_HOME = $jdk.Home
$env:PATH = (Join-Path $jdk.Home 'bin') + ';' + $env:PATH
Write-Host ("== 0/2 JDK {0} ({1}) ==" -f $jdk.Version, $jdk.Home) -ForegroundColor DarkGray

# ---------------------------------------------------------------- 1. 抽取宿主 API
if (-not $SkipApi) {
    Write-Host '== 1/2 抽取宿主 API ==' -ForegroundColor Cyan
    $psArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $root 'tools\extract-api.ps1'))
    if ($HostJar)  { $psArgs += @('-HostJar', $HostJar) }
    if ($ForceApi) { $psArgs += '-Force' }
    & pwsh @psArgs
    if ($LASTEXITCODE -ne 0) { throw "extract-api.ps1 失败（exit $LASTEXITCODE）" }
}
else {
    Write-Host '== 1/2 跳过宿主 API 抽取（-SkipApi） ==' -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- 2. 编译 + 打包
Write-Host '== 2/2 编译并打包 .spmod ==' -ForegroundColor Cyan

$python = $null
foreach ($cand in @('python', 'python3', 'py')) {
    $cmd = Get-Command $cand -ErrorAction SilentlyContinue
    if ($cmd) { $python = $cmd.Source; $pyVer = $cand; break }
}
if (-not $python) { throw '找不到 python（需要 Python 3.8+）。' }

$buildArgs = @((Join-Path $root 'tools\build.py'))
if ($pyVer -eq 'py') { $buildArgs = @('-3') + $buildArgs }

& $python @buildArgs
if ($LASTEXITCODE -ne 0) { throw "build.py 失败（exit $LASTEXITCODE）" }

$elapsed = (Get-Date) - $started
Write-Host ("构建完成，用时 {0:n1}s" -f $elapsed.TotalSeconds) -ForegroundColor Green
