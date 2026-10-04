<#
.SYNOPSIS
  net 层真实冒烟测试：编译 net 包 + core 包，跑 netease.smoke.SmokeNet（无第三方测试框架）。

.DESCRIPTION
  流程：
    1. 找 javac（JAVA_HOME → PATH）
    2. javac --release 21 编译 src/main/java 下全部源码 + tools/smoke/SmokeNet.java 到 tools/smoke/out
    3. java 运行 netease.smoke.SmokeNet
  退出码：
    0 = 全部通过；1 = 有断言不满足；2 = 网络不可用（脚本优雅打印，不抛栈）
    SKIP（需登录 / 需外部服务的用例）只计数，不影响退出码
  用法：
    pwsh -File tools/smoke-net.ps1
    pwsh -File tools/smoke-net.ps1 -Offline                  # 只跑离线自检（加密/QrCode/cookie 往返/P3 契约）
    pwsh -File tools/smoke-net.ps1 -CoreNetOnly              # 只编 core 子集 + net（绕开他人未落地的 cfg/svc/ui）
    pwsh -File tools/smoke-net.ps1 -CookieFile .\cookie.txt  # 注入登录态跑歌单/每日推荐/我喜欢（值不打印）
#>
[CmdletBinding()]
param(
  [switch]$Offline,
  [switch]$SkipCompile,
  # 只编译 core 子集 + net（当 cfg/ svc/ 正在被别人改动、整体编译不通过时用来自证 net 层）
  [switch]$CoreNetOnly,
  # 含 Cookie 头的文本文件（内容即 "k=v; k2=v2"），注入登录态跑 P3 在线断言；文件内容绝不打印
  [string]$CookieFile
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'src\main\java'
$smoke = Join-Path $root 'tools\smoke'
$out = Join-Path $smoke 'out'

# -CoreNetOnly 时的最小 core 子集（net 层实际只依赖这三个）
$coreSubset = @('Json.java', 'Hashes.java', 'PluginLog.java', 'Levels.java')

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
$javacDir = Split-Path -Parent $javac
$javaExe = Join-Path $javacDir 'java.exe'
Write-Host "[smoke-net] javac = $javac"
& $javac -version 2>&1 | ForEach-Object { Write-Host "[smoke-net] $_" }

if (-not $SkipCompile) {
  if (Test-Path $out) { Remove-Item -Recurse -Force $out }
  New-Item -ItemType Directory -Force -Path $out | Out-Null

  $sources = @()
  if ($CoreNetOnly) {
    $coreDir = Join-Path $src 'com\example\netease\core'
    $netDir = Join-Path $src 'com\example\netease\net'
    foreach ($f in $coreSubset) {
      $p = Join-Path $coreDir $f
      if (-not (Test-Path $p)) { throw "缺少 core 依赖：$p" }
      $sources += $p
    }
    $sources += Get-ChildItem -Path $netDir -Filter *.java -File | ForEach-Object { $_.FullName }
    Write-Host "[smoke-net] 模式：CoreNetOnly（不编译 cfg/ svc/）"
  } else {
    $sources += Get-ChildItem -Path $src -Recurse -Filter *.java -File | ForEach-Object { $_.FullName }
  }
  $sources += (Join-Path $smoke 'SmokeNet.java')

  # 宿主 API / PF4J 只在编译期需要（SmokeNet 自己只碰 net 层，不打宿主 API）
  $apiJars = @(
    (Join-Path $root 'tools\.cache\spw-workshop-api-host.jar'),
    (Join-Path $root 'tools\.cache\pf4j-3.12.0.jar')
  ) | Where-Object { Test-Path $_ }
  $compileCp = (@($out) + $apiJars) -join ';'

  Write-Host "[smoke-net] 编译 $($sources.Count) 个源文件 → $out"
  Write-Host "[smoke-net] 编译 classpath = $compileCp"
  & $javac --release 21 -encoding UTF-8 -Xlint:all,-serial,-this-escape,-classfile `
      -cp $compileCp -d $out @sources 2>&1 | ForEach-Object { Write-Host "  $_" }
  if ($LASTEXITCODE -ne 0) {
    Write-Host "[smoke-net] 编译失败（exit $LASTEXITCODE）" -ForegroundColor Red
    exit 1
  }
  Write-Host "[smoke-net] 编译通过"
}

if ($CookieFile) {
  $cookiePath = (Resolve-Path -LiteralPath $CookieFile).Path
  $env:NETEASE_SMOKE_COOKIE_FILE = $cookiePath
  Write-Host "[smoke-net] 已指定登录态来源：$cookiePath（文件内容不打印、不进日志）"
}

$javaArgs = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
              '-cp', $out, 'netease.smoke.SmokeNet')
if ($Offline) { $javaArgs += '--offline' }

# 让中文输出在 Windows 控制台不乱码（只影响本脚本进程）
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

Write-Host "[smoke-net] 运行：java $($javaArgs -join ' ')"
& $javaExe @javaArgs 2>&1 | Tee-Object -Variable javaOut
$code = $LASTEXITCODE

# 从 ASCII 哨兵行取计数：不受控制台编码影响，SKIP 不算失败
$summary = @($javaOut) | Select-String -Pattern '^\[SMOKE\] SUMMARY' | Select-Object -Last 1
if ($summary) {
  Write-Host "[smoke-net] 计数：$($summary.Line.Trim())"
} else {
  Write-Host "[smoke-net] 未取到 [SMOKE] SUMMARY 行（可能启动阶段就退出了）" -ForegroundColor Yellow
}

switch ($code) {
  0 { Write-Host "[smoke-net] 结果：全部通过 ✓" -ForegroundColor Green }
  2 { Write-Host "[smoke-net] 结果：网络不可用，离线自检结果见上（优雅降级）" -ForegroundColor Yellow }
  default { Write-Host "[smoke-net] 结果：存在失败项（exit $code）" -ForegroundColor Red }
}
exit $code
