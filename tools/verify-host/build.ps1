# 椒盐插件 · 内嵌安全验证宿主 构建脚本（0.11.34）
# 用法：pwsh -NoProfile -File tools\verify-host\build.ps1
# 产物：src\main\resources\verify-host\ （随包进 classes\verify-host\）
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # 仓库根
$proj = Join-Path $root 'tools\verify-host'
$out  = Join-Path $root 'src\main\resources\verify-host'

Write-Host "▶ 编译 VerifyHost（WebView2 内嵌验证宿主）"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
& dotnet publish $proj -c Release -r win-x64 --self-contained false -o $out
if ($LASTEXITCODE -ne 0) { Write-Host "✗ dotnet publish 失败（exit $LASTEXITCODE）" -ForegroundColor Red; exit 1 }

$exe = Join-Path $out 'VerifyHost.exe'
if (-not (Test-Path $exe)) { Write-Host "✗ 没有产出 VerifyHost.exe" -ForegroundColor Red; exit 1 }

Write-Host "▶ 自检（--selftest，只探 WebView2 运行时，不开窗）"
& $exe --selftest
$code = $LASTEXITCODE
$files = Get-ChildItem $out -Recurse -File
$bytes = ($files | Measure-Object -Property Length -Sum).Sum
Write-Host ("  产物 {0} 个文件 / {1} 字节 → {2}" -f $files.Count, $bytes, $out)
Write-Host ("  自检退出码 {0}" -f $code)
exit $code
