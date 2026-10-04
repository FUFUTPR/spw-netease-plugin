<#
.SYNOPSIS
  短信验证码登录探针：离线环境里直接跑 net 层的 smsSend / loginCellphoneCaptcha，取业务码硬证据。

.DESCRIPTION
  背景：0.2.3 真机实测确认扫码登录被网易云风控（8821）拦下，手机号 + 密码是兜底路径；
  0.2.4 再加一条「短信验证码登录」。发码接口不是布尔语义 —— 200 已发送 / 503 太频繁 /
  8821 风控 / 502 账号问题，本探针先把这些码的真实取值钉死，再让 UI 按码给文案。

  **默认只打占位号 13800000000**（示例号，非真人号码），只拿业务码、不会打扰任何人。
  要验证「短信真的能收到」，必须显式传 -Phone 1xxxxxxxxxx —— 那会真的发出一条短信。

  用法：
    pwsh -File tools\smoke-sms.ps1                        # 只用占位号取业务码
    pwsh -File tools\smoke-sms.ps1 -Phone 1xxxxxxxxxx     # 真号发码（会真的发短信）
    pwsh -File tools\smoke-sms.ps1 -Phone 1xxxxxxxxxx -Code 123456   # 真号 + 真码 → 走通登录
    pwsh -File tools\smoke-sms.ps1 -SkipCompile
  退出码：0 = 全部用例符合预期；1 = 有用例不符合预期（或编译/运行失败）
#>
[CmdletBinding()]
param(
  [string]$Phone,
  [string]$Code,
  [switch]$NoSend,
  [switch]$SkipCompile
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'src\main\java'
$smoke = Join-Path $root 'tools\smoke'
$out = Join-Path $smoke 'out-sms'

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
Write-Host "[smoke-sms] javac = $javac"

if (-not $SkipCompile) {
  if (Test-Path $out) { Remove-Item -Recurse -Force $out }
  New-Item -ItemType Directory -Force -Path $out | Out-Null

  $coreDir = Join-Path $src 'com\example\netease\core'
  $netDir = Join-Path $src 'com\example\netease\net'
  $sources = @()
  foreach ($f in @('PluginLog.java', 'Json.java', 'Hashes.java', 'Levels.java')) {
    $p = Join-Path $coreDir $f
    if (-not (Test-Path $p)) { throw "缺少 core 依赖：$p" }
    $sources += $p
  }
  $sources += Get-ChildItem -Path $netDir -Filter *.java -File | ForEach-Object { $_.FullName }
  $sources += (Join-Path $smoke 'SmsLoginProbe.java')

  $apiJars = @(
    (Join-Path $root 'tools\.cache\spw-workshop-api-host.jar'),
    (Join-Path $root 'tools\.cache\pf4j-3.12.0.jar')
  ) | Where-Object { Test-Path $_ }
  $compileCp = (@($out) + $apiJars) -join ';'

  Write-Host "[smoke-sms] 编译 $($sources.Count) 个源文件 → $out"
  & $javac --release 21 -encoding UTF-8 -Xlint:all,-serial,-this-escape,-classfile `
      -cp $compileCp -d $out @sources 2>&1 | ForEach-Object { Write-Host "  $_" }
  if ($LASTEXITCODE -ne 0) {
    Write-Host "[smoke-sms] 编译失败（exit $LASTEXITCODE）" -ForegroundColor Red
    exit 1
  }
  Write-Host "[smoke-sms] 编译通过"
}

$probeArgs = @()
if ($Phone) { $probeArgs += "--phone=$Phone" }
if ($Code) { $probeArgs += "--code=$Code" }
if ($NoSend) { $probeArgs += "--no-send" }

Push-Location $root
try {
  # 注意：-D 开头的参数必须走数组 splat（直接字面写会被 PowerShell 拆成 /encoding=UTF-8）
  $javaArgs = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                '-cp', $out, 'com.example.netease.net.SmsLoginProbe') + $probeArgs
  Write-Host "[smoke-sms] 运行：java -cp $out com.example.netease.net.SmsLoginProbe $($probeArgs -join ' ')"
  & $javaExe @javaArgs 2>&1 | ForEach-Object { Write-Host $_ }
  $code = $LASTEXITCODE
} finally {
  Pop-Location
}

Write-Host "[smoke-sms] 探针退出码=$code（0=用例全部符合预期）"
if ($code -ne 0) { exit 1 }
exit 0
