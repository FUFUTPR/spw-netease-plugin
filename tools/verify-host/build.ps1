# 椒盐插件 · 内嵌安全验证宿主 构建脚本（0.12.0 批次 4：临时目录 → 自检 → 替换资源）
# 用法：pwsh -NoProfile -File tools\verify-host\build.ps1
# 产物：src\main\resources\verify-host\ （随包进 classes\verify-host\）
#
# 为什么要先临时目录：dotnet publish 失败时如果直接写资源目录，会把旧资源清空、留下半成品，
# 下一次打包就会带一个坏掉的 VerifyHost.exe。所以这里一律先发到 build\_tmp 下，自检通过
# 才整体替换资源目录，并校验哈希 + 时间戳确认发布的是这一版源码编出来的文件。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # 仓库根
$proj = Join-Path $root 'tools\verify-host'
$out  = Join-Path $root 'src\main\resources\verify-host'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$tmp  = Join-Path $root ("build\_tmp\verify-host-publish-" + $stamp)

Write-Host "▶ 编译 VerifyHost（WebView2 内嵌验证宿主）→ 临时目录"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
& dotnet publish $proj -c Release -r win-x64 --self-contained false -o $tmp
if ($LASTEXITCODE -ne 0) { Write-Host "✗ dotnet publish 失败（exit $LASTEXITCODE）" -ForegroundColor Red; exit 1 }

$exe = Join-Path $tmp 'VerifyHost.exe'
if (-not (Test-Path -LiteralPath $exe)) { Write-Host "✗ 没有产出 VerifyHost.exe" -ForegroundColor Red; exit 1 }
$exeInfo = Get-Item -LiteralPath $exe
if ($exeInfo.Length -lt 1024) { Write-Host "✗ VerifyHost.exe 体积异常（$($exeInfo.Length) 字节）" -ForegroundColor Red; exit 1 }

# 新版校验：exe 的写入时间必须不早于源文件（避免 dotnet 复用旧产物）
$newestSource = Get-ChildItem -Path $proj -Recurse -File -Include *.cs,*.csproj |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($newestSource -and $exeInfo.LastWriteTimeUtc -lt $newestSource.LastWriteTimeUtc.AddSeconds(-2)) {
    Write-Host ("✗ 发布出来的 VerifyHost.exe 比源码旧（exe {0:o} < 源 {1:o}），拒绝替换资源" -f $exeInfo.LastWriteTimeUtc, $newestSource.LastWriteTimeUtc) -ForegroundColor Red
    exit 1
}

Write-Host "▶ 自检（--selftest，只探 WebView2 运行时，不开窗）"
& $exe --selftest
$code = $LASTEXITCODE
if ($code -ne 0) {
    Write-Host "✗ 自检失败（exit $code）；资源目录保持原样，本次临时产物保留在 $tmp" -ForegroundColor Red
    exit $code
}

Write-Host "▶ 替换资源目录 $out"
if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null
Copy-Item -Path (Join-Path $tmp '*') -Destination $out -Recurse -Force

$newExe = Join-Path $out 'VerifyHost.exe'
if (-not (Test-Path -LiteralPath $newExe)) { Write-Host "✗ 资源目录缺少 VerifyHost.exe" -ForegroundColor Red; exit 1 }
$h1 = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash
$h2 = (Get-FileHash -LiteralPath $newExe -Algorithm SHA256).Hash
if ($h1 -ne $h2) {
    Write-Host "✗ 资源目录的 exe 与临时产物哈希不一致（$h1 vs $h2）" -ForegroundColor Red
    exit 1
}
# VerifyHost.exe 只是 apphost，托管代码在 VerifyHost.dll —— 必须两个都比，才能证明打包的是新代码
$d1 = (Get-FileHash -LiteralPath (Join-Path $tmp 'VerifyHost.dll') -Algorithm SHA256).Hash
$d2 = (Get-FileHash -LiteralPath (Join-Path $out 'VerifyHost.dll') -Algorithm SHA256).Hash
if ($d1 -ne $d2) {
    Write-Host "✗ 资源目录的 VerifyHost.dll 与临时产物哈希不一致（$d1 vs $d2）" -ForegroundColor Red
    exit 1
}

$files = Get-ChildItem $out -Recurse -File
$bytes = ($files | Measure-Object -Property Length -Sum).Sum
Write-Host ("  产物 {0} 个文件 / {1} 字节 → {2}" -f $files.Count, $bytes, $out)
Write-Host ("  VerifyHost.exe sha256 {0} / VerifyHost.dll sha256 {1}（均与临时产物一致）" -f $h2, $d2)
Write-Host ("  自检退出码 {0}" -f $code)
exit $code
