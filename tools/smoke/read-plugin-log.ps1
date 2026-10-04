<#
.SYNOPSIS
  读插件运行日志（宿主在线时也稳）—— 取证专用小工具。

.DESCRIPTION
  宿主在写日志时会把 `logs\plugin-YYYYMMDD.log` 短暂占住：`Get-Content` / `Select-String` /
  `Get-ChildItem` 会间歇性报 “Cannot find path … because it does not exist”（实测 40 次探测里
  偶发丢几次，通常几秒后自愈）。本脚本用 `FileShare.ReadWrite` 打开 + 重试，避免取证时被这种
  假阴性打断。

  用法：
    pwsh -NoProfile -File tools\smoke\read-plugin-log.ps1                        # 全量输出
    pwsh -NoProfile -File tools\smoke\read-plugin-log.ps1 -Pattern '封面失败'    # 只输出匹配行
    pwsh -NoProfile -File tools\smoke\read-plugin-log.ps1 -Pattern '歌单' -Tail 5
    pwsh -NoProfile -File tools\smoke\read-plugin-log.ps1 -Dir <数据目录> -Pattern …
    pwsh -NoProfile -File tools\smoke\read-plugin-log.ps1 -OutFile build\probe\snap.log

  参数 -NoRetry 时只读一次（用于确认文件确实不存在，而不是被占住）。
#>
[CmdletBinding()]
param(
  [string]$Dir = "$env:APPDATA\Salt Player for Windows\workshop\data\com.example\netease",
  [string]$File = '',
  [string]$Pattern = '',
  [int]$Tail = 0,
  [string]$OutFile = '',
  [int]$Retries = 60,
  [switch]$NoRetry
)

$ErrorActionPreference = 'Stop'

function Resolve-LogPath {
  if ($File) { return $File }
  $logs = Join-Path $Dir 'logs'
  # 先按当天文件名直接试（目录枚举在宿主高频写日志时也会间歇性报「找不到路径」）
  $guess = Join-Path $logs ("plugin-" + (Get-Date -Format 'yyyyMMdd') + ".log")
  if (Test-Path -LiteralPath $guess) { return $guess }
  $f = Get-ChildItem $logs -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($f) { return $f.FullName }
  # 再退一步：直接枚举数据目录下所有 plugin-*.log
  $g = Get-ChildItem $Dir -Recurse -Filter 'plugin-*.log' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($g) { return $g.FullName }
  return $null
}

$path = $null
$text = $null
$tries = if ($NoRetry) { 1 } else { [Math]::Max(1, $Retries) }

for ($i = 0; $i -lt $tries -and $null -eq $text; $i++) {
  if (-not $path) { $path = Resolve-LogPath }
  if (-not $path) { Start-Sleep -Milliseconds 800; continue }
  try {
    $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
    $text = $sr.ReadToEnd()
    $sr.Close(); $fs.Close()
  } catch {
    Start-Sleep -Milliseconds 800
  }
}

if ($null -eq $text) {
  [Console]::Error.WriteLine("读不到日志（重试 $tries 次）：$path")
  exit 2
}

if ($OutFile) {
  $parent = Split-Path -Parent $OutFile
  if ($parent -and -not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
  [System.IO.File]::WriteAllText($OutFile, $text, (New-Object System.Text.UTF8Encoding($false)))
  Write-Output "已写出快照：$OutFile（$($text.Length) 字符）"
}

$lines = $text -split "`r?`n"
$sel = $lines
if ($Pattern) { $sel = @($lines | Select-String -Pattern $Pattern | ForEach-Object { $_.Line }) }
if ($Tail -gt 0 -and $sel.Count -gt $Tail) { $sel = $sel[($sel.Count - $Tail)..($sel.Count - 1)] }
"日志 = $path"
"总行 = $($lines.Count)  选中 = $(@($sel).Count)"
$sel
