<#
.SYNOPSIS
  真机联调探针：用本机插件已登录的凭据跑真实接口，钉死「歌单取全 / 最高音质 / 直链可否裸拉流」。

.DESCRIPTION
  背景（用户真机反馈）：歌单 2641 首只显示 1000 首；下载只有几 MB（默认档位 exhigh = 320k MP3）。
  本探针不依赖宿主，直接调 net 层：playlist() / songUrl() 各档位 + lastTrackPath() / lastUrlMeta()。

  安全边界：
    * 凭据**只读拷贝**——真盘 %APPDATA%\...\com.example.netease 下的 account.json/account.key
      先复制到 build\live-probe\vault\，探针只 init/load 那份拷贝，绝不写回真盘。
    * 输出只含摘要：凭据长度、歌单声明/实取、档位元信息、直链的 host/扩展名/query 参数名。
      **不打印 cookie、不打印完整直链、不打印验证码**。
    * -M3u 写出的试验文件含临时令牌，只落在本机 build\ 下，请勿外传。

  用法：
    pwsh -File tools\smoke-live.ps1
    pwsh -File tools\smoke-live.ps1 -Levels hires,lossless
    pwsh -File tools\smoke-live.ps1 -M3u build\live-probe\netease-test.m3u -M3uLimit 20
    pwsh -File tools\smoke-live.ps1 -SkipCompile
  退出码：0 = 取证无失败项；1 = 有失败项（或编译/运行失败）；2 = 没有可用凭据
#>
[CmdletBinding()]
param(
  [long]$Playlist = 8689913835,
  [string]$Levels = 'hires,lossless,exhigh',
  [string]$M3u,
  [int]$M3uLimit = 50,
  [switch]$SkipCompile
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'src\main\java'
$smoke = Join-Path $root 'tools\smoke'
$out = Join-Path $smoke 'out-live'

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
Write-Host "[smoke-live] javac = $javac"

if (-not $SkipCompile) {
  if (Test-Path $out) { Remove-Item -Recurse -Force $out }
  New-Item -ItemType Directory -Force -Path $out | Out-Null

  $coreDir = Join-Path $src 'com\example\netease\core'
  $netDir = Join-Path $src 'com\example\netease\net'
  $svcDir = Join-Path $src 'com\example\netease\svc'
  $sources = @()
  foreach ($f in @('PluginLog.java', 'Json.java', 'Hashes.java', 'Levels.java')) {
    $p = Join-Path $coreDir $f
    if (-not (Test-Path $p)) { throw "缺少 core 依赖：$p" }
    $sources += $p
  }
  $sources += Get-ChildItem -Path $netDir -Filter *.java -File | ForEach-Object { $_.FullName }
  $sources += (Join-Path $svcDir 'CookieVault.java')
  $sources += (Join-Path $smoke 'LiveProbe.java')

  $apiJars = @(
    (Join-Path $root 'tools\.cache\spw-workshop-api-host.jar'),
    (Join-Path $root 'tools\.cache\pf4j-3.12.0.jar')
  ) | Where-Object { Test-Path $_ }
  $compileCp = (@($out) + $apiJars) -join ';'

  Write-Host "[smoke-live] 编译 $($sources.Count) 个源文件 → $out"
  & $javac --release 21 -encoding UTF-8 -Xlint:all,-serial,-this-escape,-classfile `
      -cp $compileCp -d $out @sources 2>&1 | ForEach-Object { Write-Host "  $_" }
  if ($LASTEXITCODE -ne 0) {
    Write-Host "[smoke-live] 编译失败（exit $LASTEXITCODE）" -ForegroundColor Red
    exit 1
  }
  Write-Host "[smoke-live] 编译通过"
}

# ---- 凭据只读拷贝（绝不动真盘） ----
$realVault = Join-Path $env:APPDATA 'Salt Player for Windows\workshop\data\com.example.netease'
$copyVault = Join-Path $root 'build\live-probe\vault'
if (Test-Path $copyVault) { Remove-Item -Recurse -Force $copyVault }
New-Item -ItemType Directory -Force -Path $copyVault | Out-Null

$copied = 0
foreach ($name in @('account.json', 'account.key', 'account.hostconfig.bak', 'account.key.bak')) {
  $from = Join-Path $realVault $name
  if (Test-Path $from) {
    Copy-Item -Force $from (Join-Path $copyVault $name)
    $copied++
  }
}
Write-Host "[smoke-live] 凭据拷贝：真盘 $realVault → $copyVault（$copied 个文件，只读用）"
if (-not (Test-Path (Join-Path $copyVault 'account.json'))) {
  Write-Host "[smoke-live] 没有 account.json（还没在插件里登录成功？）" -ForegroundColor Yellow
  exit 2
}

$probeArgs = @("--vault=$copyVault", "--playlist=$Playlist", "--levels=$Levels")

$logDir = Join-Path $root 'build\live-probe\log'
if (Test-Path $logDir) { Remove-Item -Recurse -Force $logDir }
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$probeArgs += "--log=$logDir"

if ($M3u) {
  $m3uAbs = if ([System.IO.Path]::IsPathRooted($M3u)) { $M3u } else { Join-Path $root $M3u }
  $probeArgs += "--m3u=$m3uAbs"
  $probeArgs += "--m3u-limit=$M3uLimit"
}

Push-Location $root
try {
  # 注意：-D 开头的参数必须走数组 splat（直接字面写会被 PowerShell 拆成 /encoding=UTF-8）
  $javaArgs = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                '-cp', $out, 'com.example.netease.net.LiveProbe') + $probeArgs
  Write-Host "[smoke-live] 运行：java -cp $out com.example.netease.net.LiveProbe --playlist=$Playlist --levels=$Levels"
  & $javaExe @javaArgs 2>&1 | ForEach-Object { Write-Host $_ }
  $code = $LASTEXITCODE
} finally {
  Pop-Location
}

Write-Host "[smoke-live] 探针退出码=$code（0=取证无失败项）"
if ($code -ne 0) { exit 1 }
exit 0
