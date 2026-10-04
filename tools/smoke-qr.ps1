<#
.SYNOPSIS
  二维码自证烟测：编译 core 的 QrEncoder + 探针，生成 PNG，再用 OpenCV（cv2）真解码比对内容。

.DESCRIPTION
  背景：0.2.0 的 QrCode 产出纯白图（黑像素 0），UI 与日志都显示「二维码已生成」，只有真解码能发现。
  本烟测组成回归网：生成（Java）→ 解码（Python + cv2.QRCodeDetector）→ 逐字比对 expect 文件。
  0.11.30 起编码器统一到 `core\QrEncoder.java`（M 等级 / 版本 1–20），旧 `net\QrCode.java` 已退役。

  流程：
    1. javac --release 21 编译 core 子集（PluginLog/Json/Hashes/Levels/QrEncoder）+ net 包 + tools/smoke/QrProbe.java
    2. java 运行探针：离线用例 synthetic-url（ASCII URL）、cjk-bytes（UTF-8 中文，byte 模式）
    3. python tools/verify-qr.py 真解码并比对 → [PY] SUMMARY pass= fail= skip=
  退出码：
    0 = 全部通过；1 = 有失败；2 = 环境缺 cv2（优雅跳过，不算产品失败）

  用法：
    pwsh -File tools/smoke-qr.ps1            # 离线两例
    pwsh -File tools/smoke-qr.ps1 -Live      # 追加真实 unikey 用例（走 NeteaseApi.qrCreate，需联网）
    pwsh -File tools/smoke-qr.ps1 -SkipCompile
#>
[CmdletBinding()]
param(
  [switch]$Live,
  [switch]$SkipCompile
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'src\main\java'
$smoke = Join-Path $root 'tools\smoke'
$out = Join-Path $smoke 'out-qr'

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

function Find-Javac {
  if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    if (Test-Path $candidate) { return $candidate }
  }
  $cmd = Get-Command javac -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  throw 'javac 未找到（JAVA_HOME 与 PATH 都没有）'
}

$javac = Find-Javac
$javaExe = Join-Path (Split-Path -Parent $javac) 'java.exe'
Write-Host "[smoke-qr] javac = $javac"

if (-not $SkipCompile) {
  if (Test-Path $out) { Remove-Item -Recurse -Force $out }
  New-Item -ItemType Directory -Force -Path $out | Out-Null

  $coreDir = Join-Path $src 'com\example\netease\core'
  $netDir = Join-Path $src 'com\example\netease\net'
  $sources = @()
  foreach ($f in @('PluginLog.java', 'Json.java', 'Hashes.java', 'Levels.java', 'QrEncoder.java')) {
    $p = Join-Path $coreDir $f
    if (-not (Test-Path $p)) { throw "缺少 core 依赖：$p" }
    $sources += $p
  }
  $sources += Get-ChildItem -Path $netDir -Filter *.java -File | ForEach-Object { $_.FullName }
  $sources += (Join-Path $smoke 'QrProbe.java')

  $apiJars = @(
    (Join-Path $root 'tools\.cache\spw-workshop-api-host.jar'),
    (Join-Path $root 'tools\.cache\pf4j-3.12.0.jar')
  ) | Where-Object { Test-Path $_ }
  $compileCp = (@($out) + $apiJars) -join ';'

  Write-Host "[smoke-qr] 编译 $($sources.Count) 个源文件 → $out"
  & $javac --release 21 -encoding UTF-8 -Xlint:all,-serial,-this-escape,-classfile `
      -cp $compileCp -d $out @sources 2>&1 | ForEach-Object { Write-Host "  $_" }
  if ($LASTEXITCODE -ne 0) {
    Write-Host "[smoke-qr] 编译失败（exit $LASTEXITCODE）" -ForegroundColor Red
    exit 1
  }
  Write-Host "[smoke-qr] 编译通过"
}

Push-Location $root
try {
  $javaArgs = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                '-cp', $out, 'com.example.netease.net.QrProbe')
  if ($Live) { $javaArgs += '--live' }
  Write-Host "[smoke-qr] 生成：java $($javaArgs -join ' ')"
  & $javaExe @javaArgs 2>&1 | Tee-Object -Variable javaOut
  $genCode = $LASTEXITCODE
} finally {
  Pop-Location
}

$probeLines = @($javaOut) | Where-Object { $_ -match '^\[QR\]' }
$probeFail = @($probeLines | Select-String -Pattern 'state=FAIL|state=_?FAIL').Count

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
  Write-Host "[smoke-qr] 没找到 python —— 跳过真解码（不算产品失败）" -ForegroundColor Yellow
  if ($genCode -ne 0 -or $probeFail -gt 0) { exit 1 }
  exit 2
}

$decodeOut = & $python.Source (Join-Path $PSScriptRoot 'verify-qr.py') $out 2>&1
$decodeCode = $LASTEXITCODE
$decodeOut | ForEach-Object { Write-Host "  $_" }

$summary = @($decodeOut) | Select-String -Pattern '^\[PY\] SUMMARY' | Select-Object -Last 1
if ($summary) { Write-Host "[smoke-qr] 解码计数：$($summary.Line.Trim())" }

if ($decodeCode -eq 2) {
  Write-Host "[smoke-qr] 结果：环境缺 cv2，只做了生成端自检" -ForegroundColor Yellow
  exit 2
}
if ($decodeCode -ne 0 -or $genCode -ne 0 -or $probeFail -gt 0) {
  Write-Host "[smoke-qr] 结果：存在失败项（生成 exit=$genCode，生成端 FAIL=$probeFail，解码 exit=$decodeCode）" -ForegroundColor Red
  exit 1
}
Write-Host "[smoke-qr] 结果：二维码真解码与期望内容一致 ✓" -ForegroundColor Green
exit 0
