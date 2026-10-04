<#
.SYNOPSIS
  svc 账号层离线冒烟：编译 CookieVault/AccountService + tools/smoke/SmokeAccount.java，跑加解密/状态机/脱敏断言。

.DESCRIPTION
  流程：
    1. 找 javac（JAVA_HOME → PATH）
    2. javac --release 21 编译所需子集 + tools/smoke/SmokeAccount.java 到 tools/smoke/out-account
    3. java 运行 netease.smoke.SmokeAccount
  退出码：
    0 = 全部通过；1 = 有断言不满足（含编译失败）
    SKIP（例如 -VaultOnly 下的 AccountService 用例）只计数，不影响退出码
  用法：
    pwsh -NoProfile -File tools\smoke-account.ps1                 # 全量源码（cfg/svc/ui 都要能编过）
    pwsh -NoProfile -File tools\smoke-account.ps1 -SvcOnly        # 只编 core 子集 + net + CookieVault/AccountService
    pwsh -NoProfile -File tools\smoke-account.ps1 -VaultOnly      # 只编 PluginLog + CookieVault（保险柜最小自证）
    pwsh -NoProfile -File tools\smoke-account.ps1 -Keep           # 保留 %TEMP% 工作目录（事后 grep 脱敏证据）
    pwsh -NoProfile -File tools\smoke-account.ps1 -SkipCompile    # 复用上次编译产物
#>
[CmdletBinding()]
param(
  [switch]$SkipCompile,
  # 只编 core 子集 + CookieVault + SmokeAccount（AccountService 相关用例全部 SKIP）
  [switch]$VaultOnly,
  # 只编 core 子集 + net + CookieVault/AccountService（绕开 ui/ cfg/ dl/ 的编译红灯，验证账号层真实代码）
  [switch]$SvcOnly,
  # 跑完保留 %TEMP%\spw-account-smoke-* 工作目录（密文/日志/key），供 Lead 事后 grep 复检
  [switch]$Keep
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'src\main\java'
$smoke = Join-Path $root 'tools\smoke'
$out = Join-Path $smoke 'out-account'

# CookieVault 只额外依赖 PluginLog（Json/Hashes 一并给上，便于将来扩用）
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
Write-Host "[smoke-account] javac = $javac"
& $javac -version 2>&1 | ForEach-Object { Write-Host "[smoke-account] $_" }

$javaExtra = @()
if ($Keep) { $javaExtra += '--keep' }

if (-not $SkipCompile) {
  if (Test-Path $out) { Remove-Item -Recurse -Force $out }
  New-Item -ItemType Directory -Force -Path $out | Out-Null

  $coreDir = Join-Path $src 'com\example\netease\core'
  $netDir = Join-Path $src 'com\example\netease\net'
  $svcDir = Join-Path $src 'com\example\netease\svc'
  $sources = @()

  if ($VaultOnly) {
    foreach ($f in $coreSubset) {
      $p = Join-Path $coreDir $f
      if (-not (Test-Path $p)) { throw "缺少 core 依赖：$p" }
      $sources += $p
    }
    $vault = Join-Path $svcDir 'CookieVault.java'
    if (-not (Test-Path $vault)) { throw "缺少 CookieVault：$vault" }
    $sources += $vault
    $javaExtra += '--vault-only'
    Write-Host "[smoke-account] 模式：VaultOnly（只编 PluginLog + CookieVault）"
  } elseif ($SvcOnly) {
    foreach ($f in $coreSubset) {
      $p = Join-Path $coreDir $f
      if (-not (Test-Path $p)) { throw "缺少 core 依赖：$p" }
      $sources += $p
    }
    $sources += Get-ChildItem -Path $netDir -Filter *.java -File | ForEach-Object { $_.FullName }
    # 只取账号层自己的两个类（svc 下其它类依赖 cfg/HostBridgeWorker/Timers 等，与本烟测无关）
    foreach ($f in @('CookieVault.java', 'AccountService.java')) {
      $p = Join-Path $svcDir $f
      if (-not (Test-Path $p)) { throw "缺少 svc 依赖：$p" }
      $sources += $p
    }
    Write-Host "[smoke-account] 模式：SvcOnly（core 子集 + net + CookieVault/AccountService）"
  } else {
    $sources += Get-ChildItem -Path $src -Recurse -Filter *.java -File | ForEach-Object { $_.FullName }
    Write-Host "[smoke-account] 模式：全量源码"
  }
  $sources += (Join-Path $smoke 'SmokeAccount.java')

  # 宿主 API / PF4J 只在编译期需要（本烟测自己只碰 svc/core）
  $apiJars = @(
    (Join-Path $root 'tools\.cache\spw-workshop-api-host.jar'),
    (Join-Path $root 'tools\.cache\pf4j-3.12.0.jar')
  ) | Where-Object { Test-Path $_ }
  $compileCp = (@($out) + $apiJars) -join ';'

  Write-Host "[smoke-account] 编译 $($sources.Count) 个源文件 → $out"
  & $javac --release 21 -encoding UTF-8 -Xlint:all,-serial,-this-escape,-classfile `
      -cp $compileCp -d $out @sources 2>&1 | ForEach-Object { Write-Host "  $_" }
  if ($LASTEXITCODE -ne 0) {
    Write-Host "[smoke-account] 编译失败（exit $LASTEXITCODE）" -ForegroundColor Red
    exit 1
  }
  Write-Host "[smoke-account] 编译通过"
}

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$javaArgs = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
              '-cp', $out, 'netease.smoke.SmokeAccount') + $javaExtra
Write-Host "[smoke-account] 运行：java $($javaArgs -join ' ')"
& $javaExe @javaArgs 2>&1 | Tee-Object -Variable javaOut
$code = $LASTEXITCODE

# 从 ASCII 哨兵行取计数：不受控制台编码影响，SKIP 不算失败
$summary = @($javaOut) | Select-String -Pattern '^\[SMOKE\] SUMMARY' | Select-Object -Last 1
if ($summary) {
  Write-Host "[smoke-account] 计数：$($summary.Line.Trim())"
} else {
  Write-Host "[smoke-account] 未取到 [SMOKE] SUMMARY 行（可能启动阶段就退出了）" -ForegroundColor Yellow
}

if ($code -eq 0) {
  Write-Host "[smoke-account] 结果：全部通过 ✓" -ForegroundColor Green
} else {
  Write-Host "[smoke-account] 结果：存在失败项（exit $code）" -ForegroundColor Red
}
exit $code
