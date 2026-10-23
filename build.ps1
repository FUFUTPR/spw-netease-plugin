<#
.SYNOPSIS
    一键构建：抽取宿主 API → Gradle 编译（Java+Kotlin+Compose）→ 打 .spmod 包。

.EXAMPLE
    pwsh -File build.ps1
    pwsh -File build.ps1 -SkipApi          # 已抽过 API，跳过抽取
    pwsh -File build.ps1 -ForceApi         # 强制重新抽取宿主 API
#>
[CmdletBinding()]
param(
    [switch] $SkipApi,
    [switch] $ForceApi,
    [string] $HostJar,
    [string] $DistDir
)

$ErrorActionPreference = 'Stop'
# 证据文件按 UTF-8 落盘（Windows PowerShell 控制台默认 GBK 会把中文写花）
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
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
Write-Host ("== 0/3 JDK {0} ({1}) ==" -f $jdk.Version, $jdk.Home) -ForegroundColor DarkGray

# ---------------------------------------------------------------- 1. 抽取宿主 API
if (-not $SkipApi) {
    Write-Host '== 1/3 抽取宿主 API ==' -ForegroundColor Cyan
    $psArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $root 'tools\extract-api.ps1'))
    if ($HostJar)  { $psArgs += @('-HostJar', $HostJar) }
    if ($ForceApi) { $psArgs += '-Force' }
    & pwsh @psArgs
    if ($LASTEXITCODE -ne 0) { throw "extract-api.ps1 失败（exit $LASTEXITCODE）" }
}
else {
    Write-Host '== 1/3 跳过宿主 API 抽取（-SkipApi） ==' -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- 2. Gradle 编译
# 0.12.0 批次 1 起 Java + Kotlin + Compose 统一由 Gradle 编译；Python 只负责打包。
Write-Host '== 2/3 Gradle 编译（Java + Kotlin + Compose） ==' -ForegroundColor Cyan
$gradlew = Join-Path $root 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew)) { throw "缺少 Gradle Wrapper：$gradlew" }
# 只清本次 Gradle 输出；build\baseline、build\evidence 等证据目录必须保留。
$pluginClasses = Join-Path $root 'build\plugin-classes'
$pluginClasses = [IO.Path]::GetFullPath($pluginClasses)
if ($pluginClasses -ne [IO.Path]::GetFullPath((Join-Path $root 'build\plugin-classes'))) { throw 'Unsafe classes directory' }
if (Test-Path -LiteralPath $pluginClasses) { Remove-Item -LiteralPath $pluginClasses -Recurse -Force }
& $gradlew --no-daemon --console=plain -q compilePluginClasses
if ($LASTEXITCODE -ne 0) { throw "Gradle compilePluginClasses 失败（exit $LASTEXITCODE）" }

# ---------------------------------------------------------------- 3. 打包
Write-Host '== 3/3 打包 .spmod（--package-only） ==' -ForegroundColor Cyan

$python = $null
foreach ($cand in @('python', 'python3', 'py')) {
    $cmd = Get-Command $cand -ErrorAction SilentlyContinue
    if ($cmd) { $python = $cmd.Source; $pyVer = $cand; break }
}
if (-not $python) { throw '找不到 python（需要 Python 3.8+）。' }

$buildArgs = @((Join-Path $root 'tools\build.py'), '--package-only', '--classes-dir', (Join-Path $root 'build\plugin-classes'))
if ($DistDir) { $buildArgs += @('--dist-dir', $DistDir) }
if ($pyVer -eq 'py') { $buildArgs = @('-3') + $buildArgs }

& $python @buildArgs
if ($LASTEXITCODE -ne 0) { throw "build.py 失败（exit $LASTEXITCODE）" }

$elapsed = (Get-Date) - $started
Write-Host ("构建完成，用时 {0:n1}s" -f $elapsed.TotalSeconds) -ForegroundColor Green
