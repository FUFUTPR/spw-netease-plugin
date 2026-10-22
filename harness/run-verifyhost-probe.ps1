# 椒盐插件 · VerifyHost 协议探针（0.12.0 批次 4）
# 用法：pwsh -NoProfile -File harness\run-verifyhost-probe.ps1 [-Out <日志路径>]
#
# 直接拿真实的 src\main\resources\verify-host\VerifyHost.exe 与真实 AWT 父窗跑进程协议：
# 父窗唯一识别 / 候选歧义拒绝 / 指定 HWND 核对 / 超时 / rect-show-hide-info-close /
# 父窗销毁自杀 / stdin EOF 自杀 / 旧模式（SunAwtDialog + --rect）兼容。
# 需要交互式桌面（会真的建 AWT 窗口），不适合无头环境。
param(
    [string]$Out = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $root 'tools\jdk.ps1')
$jdk = Resolve-RepoJdk
$exe = Join-Path $root 'src\main\resources\verify-host\VerifyHost.exe'
if (-not (Test-Path -LiteralPath $exe)) {
    Write-Host "✗ 没有 $exe；先跑 tools\verify-host\build.ps1" -ForegroundColor Red
    exit 1
}
$classes = Join-Path $root 'build\_tmp\verifyhost-probe-classes'
$work = Join-Path $root 'build\_tmp\verifyhost-probe-work'
if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes | Out-Null
New-Item -ItemType Directory -Force -Path $work | Out-Null

Write-Host "▶ 编译协议探针（JDK $($jdk.Major)）"
& $jdk.Javac --release $jdk.Major -encoding UTF-8 -d $classes (Join-Path $root 'tools\smoke\VerifyHostProtocolProbe.java')
if ($LASTEXITCODE -ne 0) { Write-Host "✗ 探针编译失败（exit $LASTEXITCODE）" -ForegroundColor Red; exit 1 }

Write-Host "▶ 运行协议探针（真实 WebView2 + 真实 AWT 父窗）"
$lines = & $jdk.Java '-Dfile.encoding=UTF-8' -cp $classes com.example.netease.smoke.VerifyHostProtocolProbe $exe $work 2>&1
$code = $LASTEXITCODE
$lines | ForEach-Object { Write-Host $_ }
if ($Out -ne '') {
    $lines | Set-Content -LiteralPath $Out -Encoding UTF8
}
if ($code -ne 0) { Write-Host "✗ 协议探针失败（exit $code）" -ForegroundColor Red; exit $code }
Write-Host "✓ 协议探针全部通过"
exit 0
