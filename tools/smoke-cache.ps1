<#
.SYNOPSIS
  播放缓存离线回归：编译插件全量源码 + tools/smoke/StreamingCacheProbe.java，跑 svc.StreamingAudioCache。

.DESCRIPTION
  覆盖（全部无网络、只写系统临时目录，不碰真机数据目录）：
    * 乱序 Range（先尾后头）拼接；缺段绝不冒充完整缓存
    * 同一 songId 的 standard / lossless 路径与内容隔离（音质键隔离）
    * 0 GB 档位：切歌删除上一首；Windows 活动句柄延迟到 close 后删除
    * 正容量档位：极小容量下 LRU 删除最旧完整项；未完成 .part 同样计入上限并可淘汰
    * HTTP Range 解析：suffix range、非法 range、多段 range 的响应边界

  流程：
    1. 找 javac（JAVA_HOME → PATH）
    2. javac --release 21 编译 src/main/java 全部源码 + tools/smoke/StreamingCacheProbe.java → tools/smoke/out-cache
    3. java 运行 com.example.netease.svc.StreamingCacheProbe

  退出码：0 = 全部 PASS；1 = 有断言不满足或编译失败
  用法：
    pwsh -File tools\smoke-cache.ps1
    pwsh -File tools\smoke-cache.ps1 -SkipCompile
#>
[CmdletBinding()]
param(
  [switch]$SkipCompile
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'src\main\java'
$smoke = Join-Path $root 'tools\smoke'
$out = Join-Path $smoke 'out-cache'

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
Write-Host "[smoke-cache] javac = $javac"
& $javac -version 2>&1 | ForEach-Object { Write-Host "[smoke-cache] $_" }

if (-not $SkipCompile) {
  if (Test-Path $out) { Remove-Item -Recurse -Force $out }
  New-Item -ItemType Directory -Force -Path $out | Out-Null

  $sources = @(Get-ChildItem -Path $src -Recurse -Filter *.java -File | ForEach-Object { $_.FullName })
  $sources += (Join-Path $smoke 'StreamingCacheProbe.java')

  # 宿主 API / PF4J 只在编译期需要（探针只碰 svc/core，不打宿主 API）
  $apiJars = @(
    (Join-Path $root 'tools\.cache\spw-workshop-api-host.jar'),
    (Join-Path $root 'tools\.cache\pf4j-3.12.0.jar')
  ) | Where-Object { Test-Path $_ }
  $compileCp = (@($out) + $apiJars) -join ';'

  Write-Host "[smoke-cache] 编译 $($sources.Count) 个源文件 → $out"
  & $javac --release 21 -encoding UTF-8 -Xlint:all,-serial,-this-escape,-classfile `
      -cp $compileCp -d $out @sources 2>&1 | ForEach-Object { Write-Host "  $_" }
  if ($LASTEXITCODE -ne 0) {
    Write-Host "[smoke-cache] 编译失败（exit $LASTEXITCODE）" -ForegroundColor Red
    exit 1
  }
  Write-Host "[smoke-cache] 编译通过"
}

try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$javaArgs = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
              '-cp', $out, 'com.example.netease.svc.StreamingCacheProbe')
Write-Host "[smoke-cache] 运行：java $($javaArgs -join ' ')"
& $javaExe @javaArgs 2>&1 | Tee-Object -Variable javaOut
$code = $LASTEXITCODE

$pass = @($javaOut) | Select-String -Pattern '^PASS ' | Measure-Object | Select-Object -ExpandProperty Count
$fail = @($javaOut) | Select-String -Pattern '^(FAIL|Exception)' | Measure-Object | Select-Object -ExpandProperty Count
Write-Host "[smoke-cache] PASS 行 = $pass / FAIL 行 = $fail（exit $code）"
if ($code -eq 0) { Write-Host "[smoke-cache] 结果：全部通过 ✓" -ForegroundColor Green }
else { Write-Host "[smoke-cache] 结果：存在失败项" -ForegroundColor Red }
exit $code
