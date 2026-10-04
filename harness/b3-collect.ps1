<#
b3-collect.ps1 —— B3 轮采证（Lead 所有）

用途：为 W1「去客户端化 + 原生登录」验收（A4/A5/R2）采集**可文本复核**的证据。
不装包、不起停宿主、不点界面——只读进程/日志/文件，输出一份报告文本。

用法：
  pwsh -NoProfile -File harness\b3-collect.ps1                 # 自动时间戳文件名
  pwsh -NoProfile -File harness\b3-collect.ps1 -Out x.txt      # 指定输出
  pwsh -NoProfile -File harness\b3-collect.ps1 -SinceMinutes 30  # 只 grep 最近 N 分钟的日志（缺省 120）

输出：harness\logs\b3-<yyyyMMdd-HHmmss>.txt（UTF-8）
判据：
  [A4] §1 cloudmusic 进程数 = 0；§3 客户端相关 grep 命中 = 0
  [A5] §4 出现「凭据已加密落盘并回读校验通过」与（重启后）vault 恢复路径；§5 account.json 以 v1: 开头
  [R2] §4 出现短信登录路径行（或标注「未跑」）
#>
[CmdletBinding()]
param(
    [string]$Out,
    [int]$SinceMinutes = 120,
    [string]$PluginId = 'com.example.netease',
    [switch]$AllLogs
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$dataRoot = Join-Path $env:APPDATA 'Salt Player for Windows'
$pluginData = Join-Path $dataRoot "workshop\data\$PluginId"
$logDir = Join-Path $pluginData 'logs'

if (-not $Out) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $Out = Join-Path $PSScriptRoot "logs\b3-$stamp.txt"
}
$outDir = Split-Path -Parent $Out
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

$sb = New-Object System.Text.StringBuilder
function W([string]$s = '') { [void]$sb.AppendLine($s) }
function Section([string]$t) { W ''; W ("=" * 78); W "== $t"; W ("=" * 78) }
function Run([string]$label, [scriptblock]$body) {
    W "-- $label"
    try { & $body | ForEach-Object { W "   $_" } } catch { W "   [采集失败] $($_.Exception.Message)" }
}

W "b3-collect.ps1  $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))"
W "workspace   = $root"
W "dataRoot    = $dataRoot"
W "pluginData  = $pluginData"
W "logDir      = $logDir"
W "sinceMin    = $SinceMinutes"

# ---------------------------------------------------------------- 1. 客户端进程（A4）
Section '1. A4 —— 本机网易云客户端进程（期望：0）'
Run 'Get-Process cloudmusic' {
    $p = @(Get-Process -Name 'cloudmusic' -ErrorAction SilentlyContinue)
    "cloudmusic 进程数 = $($p.Count)"
    foreach ($x in $p) { "   pid=$($x.Id) start=$($x.StartTime) path=$($x.Path)" }
    if ($p.Count -gt 0) { '✗ 红线 1 疑似违规：本机客户端在跑（可能是用户自己开的，需人工确认）' }
    else { '✓ 无客户端进程' }
}
Run 'Get-Process CloudMusic (大小写兜底)' {
    $p = @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -like '*cloudmusic*' })
    "模糊匹配进程数 = $($p.Count)"
    foreach ($x in $p) { "   $($x.ProcessName) pid=$($x.Id)" }
}

# ---------------------------------------------------------------- 2. 宿主与其子进程（A4）
Section '2. A4 —— 宿主进程与子进程树（判断插件是否拉起外部进程）'
$hosts = @(Get-Process -Name 'Salt Player for Windows' -ErrorAction SilentlyContinue)
Run '宿主进程' {
    "宿主进程数 = $($hosts.Count)"
    foreach ($h in $hosts) { "   pid=$($h.Id) start=$($h.StartTime)" }
}
Run '内核启动以来由宿主拉起的子进程（CIM ParentProcessId）' {
    $ids = @($hosts | ForEach-Object { $_.Id })
    if ($ids.Count -eq 0) { '宿主未运行 → 跳过'; return }
    $kids = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
              Where-Object { $ids -contains $_.ParentProcessId })
    "子进程数 = $($kids.Count)"
    foreach ($k in $kids) { "   pid=$($k.ProcessId) name=$($k.Name) cmd=$($k.CommandLine)" }
}
Run 'java.exe / powershell.exe 残留（插件若用脚本拉客户端会暴露）' {
    $j = @(Get-Process -Name 'java', 'javaw', 'powershell', 'pwsh' -ErrorAction SilentlyContinue)
    "java/powershell 进程数 = $($j.Count)"
    foreach ($x in $j) { "   $($x.ProcessName) pid=$($x.Id)" }
}

# ---------------------------------------------------------------- 3. 日志里的客户端痕迹（A4）
Section '3. A4 —— 插件日志中的客户端/调试口痕迹（期望：0 命中）'
$logs = @(Get-ChildItem -Path $logDir -Filter 'plugin-*.log' -ErrorAction SilentlyContinue | Sort-Object Name)
Run '日志文件清单' {
    if ($logs.Count -eq 0) { "无日志（$logDir 不存在或为空）" }
    foreach ($l in $logs) { "   $($l.Name)  $($l.Length) B  mtime=$($l.LastWriteTime)" }
}
# 默认只 grep **最新一份**日志（历史日志里 0.11.6 时代的 [client] 行会污染 A4 判定）。
# 需要看全量时加 -AllLogs。
$since = (Get-Date).AddMinutes(-1 * $SinceMinutes)
if ($AllLogs) {
    $recent = @($logs | Where-Object { $_.LastWriteTime -ge $since })
} else {
    $recent = @($logs | Select-Object -Last 1)
}
if ($recent.Count -eq 0 -and $logs.Count -gt 0) { $recent = @($logs[-1]) }
Run "客户端痕迹 grep（文件：$(($recent | ForEach-Object { $_.Name }) -join ', ')）" {
    $pat = '\[client\]|cloudmusic|CloudMusic|CDP|9222|remote-debugging|client-window|ShowWindow'
    $hits = @(Select-String -Path ($recent | ForEach-Object { $_.FullName }) -Pattern $pat -ErrorAction SilentlyContinue)
    "命中行数 = $($hits.Count)"
    foreach ($h in ($hits | Select-Object -First 40)) { "   $($h.Filename):$($h.LineNumber) $($h.Line.Trim())" }
    if ($hits.Count -eq 0) { '✓ 无客户端痕迹（A4 正面证据）' }
}

# ---------------------------------------------------------------- 4. 账号/登录链日志（A5/R2）
Section '4. A5/R2 —— 账号层日志（登录、凭据落盘、vault 恢复、短信）'
Run 'account / vault / login grep' {
    $pat = '\[account\]|\[vault\]|凭据|登录|auto_login|phone=|短信|验证码|扫码'
    $hits = @(Select-String -Path ($recent | ForEach-Object { $_.FullName }) -Pattern $pat -ErrorAction SilentlyContinue)
    "命中行数 = $($hits.Count)"
    foreach ($h in ($hits | Select-Object -Last 80)) { "   $($h.LineNumber) $($h.Line.Trim())" }
}
Run '关键判据行（逐条命中数）' {
    $need = @(
        '凭据已加密落盘并回读校验通过',
        '托管凭据',
        'vault',
        '登录成功',
        '手机号',
        '短信',
        '登录失败',
        '未登录'
    )
    foreach ($n in $need) {
        $c = @(Select-String -Path ($recent | ForEach-Object { $_.FullName }) -SimpleMatch -Pattern $n -ErrorAction SilentlyContinue).Count
        "   [$('{0,3}' -f $c)] $n"
    }
}
Run 'ERROR/WARN 行（禁止静默失败的对照）' {
    $hits = @(Select-String -Path ($recent | ForEach-Object { $_.FullName }) -Pattern '\[ERROR|\[WARN' -ErrorAction SilentlyContinue)
    "ERROR/WARN 行数 = $($hits.Count)"
    foreach ($h in ($hits | Select-Object -First 30)) { "   $($h.LineNumber) $($h.Line.Trim())" }
}

# ---------------------------------------------------------------- 5. 凭据文件（A5）
Section '5. A5 —— account.json / account.key（只报头 12 字符，不打印密文）'
Run 'account.json' {
    $f = Join-Path $pluginData 'account.json'
    if (-not (Test-Path $f)) { "不存在：$f"; return }
    $txt = (Get-Content -Raw -LiteralPath $f).Trim()
    $i = [Math]::Min(12, $txt.Length)
    "大小 = $((Get-Item $f).Length) B  mtime = $((Get-Item $f).LastWriteTime)"
    "头 12 字符 = $($txt.Substring(0, $i))"
    "是否 v1: 前缀 = $($txt.StartsWith('v1:'))"
    "含明文手机号 = $($txt -match '\d{11}')"
}
Run 'account.key' {
    $f = Join-Path $pluginData 'account.key'
    if (-not (Test-Path $f)) { "不存在：$f"; return }
    "大小 = $((Get-Item $f).Length) B  mtime = $((Get-Item $f).LastWriteTime)"
}
# 0.11.22：账号组新增两个**用户输入行**（手机号 phone / 验证码 sms_code，宿主明文写盘）与一个展示行
#  （current_account）⇒ 取证一律先打码，凭据值绝不进证据文件：
#    手机号 → 前 3 后 4（够复核「填了哪一号」又不可复原）；验证码 → 只留位数；当前账号 → 只留「有值/空」。
# 0.11.23：登录收成一行 login_input（它按值形态分步：手机号 / 验证码 / 账号串都可能出现），
#  手机号与验证码退到内部键（phone / sms_code 仍在文件里，只是界面上不再有对应控件）⇒ 四个键全打码：
#  这一行的内容按形态分类 —— 11 位号 → 前 3 后 4；4~6 位纯数字 → 只留位数；更长的数字串 → 只留位数；
#  其它（账号串这类文本）→ 只留字符数。任何情况下都不输出原始值。
function Mask-AccountCfg([string] $txt) {
    $txt = [regex]::Replace($txt, '("login_input"\s*:\s*")([^"]*)(")', {
            param($m)
            $v = $m.Groups[2].Value
            $body =
            if ($v.Length -eq 0) { "" }
            elseif ($v -match '^1\d{10}$') { $v.Substring(0, 3) + '****' + $v.Substring($v.Length - 4) }
            elseif ($v -match '^\d+$') { "（$($v.Length) 位数字，已屏蔽）" }
            else { "（$($v.Length) 字符文本，已屏蔽）" }
            $m.Groups[1].Value + $body + $m.Groups[3].Value
        })
    $txt = [regex]::Replace($txt, '("phone"\s*:\s*")([^"]*)(")', {
            param($m)
            $v = $m.Groups[2].Value
            $head = if ($v.Length -ge 7) { $v.Substring(0, 3) + '****' + $v.Substring($v.Length - 4) }
            else { "（$($v.Length) 位，已屏蔽）" }
            $m.Groups[1].Value + $head + $m.Groups[3].Value
        })
    $txt = [regex]::Replace($txt, '("sms_code"\s*:\s*")([^"]*)(")', {
            param($m)
            $v = $m.Groups[2].Value
            $m.Groups[1].Value + "（$($v.Length) 位，已屏蔽）" + $m.Groups[3].Value
        })
    $txt = [regex]::Replace($txt, '("current_account"\s*:\s*")([^"]*)(")', {
            param($m)
            $v = $m.Groups[2].Value
            $body = if ($v.Length -eq 0) { "" } else { "（$($v.Length) 字符，已屏蔽）" }
            $m.Groups[1].Value + $body + $m.Groups[3].Value
        })
    return $txt
}

Run '配置组文件内容（凭据已屏蔽）' {
    foreach ($n in @('account_cfg.json', 'config.json', 'online.json', 'cache_cfg.json', 'client_cfg.json')) {
        $f = Join-Path $pluginData $n
        if (Test-Path $f) { "   $n = $(Mask-AccountCfg (Get-Content -Raw -LiteralPath $f).Trim())" } else { "   $n = （不存在）" }
    }
}

# ---------------------------------------------------------------- 6. 目录计数（A16 基线）
Section '6. 目录计数（预热/缓存解耦基线）'
Run '插件数据目录' {
    foreach ($d in @('audio-stream', 'audio-cover', 'cover', 'lyric', 'playlist-cover', 'db-backup', 'probe')) {
        $p = Join-Path $pluginData $d
        if (-not (Test-Path $p)) { "   $d = （不存在）"; continue }
        $files = @(Get-ChildItem -Path $p -Recurse -File -ErrorAction SilentlyContinue)
        $bytes = ($files | Measure-Object -Property Length -Sum).Sum
        if (-not $bytes) { $bytes = 0 }
        "   $d = $($files.Count) 文件 / $([Math]::Round($bytes / 1MB, 1)) MB"
    }
}
Run '宿主封面缓存 shared_cover' {
    $p = Join-Path $dataRoot 'cache\shared_cover'
    if (-not (Test-Path $p)) { "   （不存在）$p"; return }
    $files = @(Get-ChildItem -Path $p -Recurse -File -ErrorAction SilentlyContinue)
    $small = @($files | Where-Object { $_.Length -eq 2074 })
    "   文件数 = $($files.Count)（其中 2074 B 标记图残留 = $($small.Count)）"
}
Run '宿主进程数（收尾对照）' {
    "Salt Player for Windows = $(@(Get-Process -Name 'Salt Player for Windows' -ErrorAction SilentlyContinue).Count)"
}

Section '采证完成'
W ("输出文件 = " + $Out)
W ("行数 = " + $sb.ToString().Split("`n").Count)

[System.IO.File]::WriteAllText($Out, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "已写入 $Out"
