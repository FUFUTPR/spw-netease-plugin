<#
.SYNOPSIS
  二维码登录端到端探针：离线环境里直接跑 net 层 API，对照 weapi 与匿名两个通道的轮询码。

.DESCRIPTION
  背景：0.2.2 真机实测「手机确认后插件仍停在已扫码，请在手机上确认」——日志里匿名 /api/ 通道
  每 1.5s 回一条 8821。本探针把两个通道放进同一轮轮询对照，回答一个问题：
  8821 是「通道选错了」还是「账号/IP 被风控」。
  **0.2.3 实测结论（docs/14 §9.7）：两通道同码同步（801 → 802 → 8821），8821 与通道无关，
  是伺服端风控拒绝**（原话「请切换其他登录方式或升级新版本再试」）；因此 0.2.3 起
  qrPoll 按契约恢复「匿名优先、weapi 兜底」；扫码受阻时的替代路径是**手机号 + 短信验证码**
  （0.11.9 起「手机号 + 密码」已按用户决定退役，见 docs/00 §6.23）。
    weapi= NeteaseApi.qrPoll 的兜底通道（weapi/login/qrcode/client/login，官网扫码页同通道）
    anon=  原始匿名接口 /api/login/qrcode/client/login?key=&type=1（qrPoll 的主路径）
  产出：tools\smoke\out-qrlogin\qr.png 与 qr-4x.png（4 倍最近邻放大，给手机扫屏幕用），并尝试自动打开。

  用法：
    pwsh -File tools\smoke-qrlogin.ps1              # 编译 + 运行（默认等 240 秒）
    pwsh -File tools\smoke-qrlogin.ps1 -Seconds 300
    pwsh -File tools\smoke-qrlogin.ps1 -SkipCompile  # 复用上次编译产物
  退出码：0 = 拿到 803 且账号信息完整；2 = 拿到 803 但账号信息缺失；1 = 其它（超时/过期/编译失败）
#>
[CmdletBinding()]
param(
  [int]$Seconds = 240,
  [switch]$SkipCompile
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'src\main\java'
$smoke = Join-Path $root 'tools\smoke'
$out = Join-Path $smoke 'out-qrlogin'

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
Write-Host "[smoke-qrlogin] javac = $javac"

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
  $sources += (Join-Path $smoke 'QrLoginProbe.java')

  $apiJars = @(
    (Join-Path $root 'tools\.cache\spw-workshop-api-host.jar'),
    (Join-Path $root 'tools\.cache\pf4j-3.12.0.jar')
  ) | Where-Object { Test-Path $_ }
  $compileCp = (@($out) + $apiJars) -join ';'

  Write-Host "[smoke-qrlogin] 编译 $($sources.Count) 个源文件 → $out"
  & $javac --release 21 -encoding UTF-8 -Xlint:all,-serial,-this-escape,-classfile `
      -cp $compileCp -d $out @sources 2>&1 | ForEach-Object { Write-Host "  $_" }
  if ($LASTEXITCODE -ne 0) {
    Write-Host "[smoke-qrlogin] 编译失败（exit $LASTEXITCODE）" -ForegroundColor Red
    exit 1
  }
  Write-Host "[smoke-qrlogin] 编译通过"
}

Push-Location $root
try {
  $javaArgs = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                '-cp', $out, 'com.example.netease.net.QrLoginProbe', $out, "$Seconds")
  Write-Host "[smoke-qrlogin] 运行：java -cp $out com.example.netease.net.QrLoginProbe $out $Seconds"
  & $javaExe @javaArgs 2>&1 | ForEach-Object { Write-Host $_ }
  $code = $LASTEXITCODE
} finally {
  Pop-Location
}

Write-Host "[smoke-qrlogin] 探针退出码=$code（0=拿到 803 且账号信息完整）"
if ($code -ne 0) { exit 1 }
exit 0
