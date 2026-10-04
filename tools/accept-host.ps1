<#
.SYNOPSIS
    SPW 网易云插件 · 真机验收「机器侧证据」采集器（P0/P1 验收用）。

.DESCRIPTION
    离线全绿（verify-spmod / harness）只证明「桩环境里签名与生命周期没炸」，
    不等于「宿主里能跑」。本脚本把真机验收里**能自动判定**的部分固定成可复跑的两步：

      -Phase Pre   退出宿主 → 快照基线（spw.db 指纹、插件目录、日志长度）→ 调 install-plugin.ps1 安装
      -Phase Post  启动宿主并操作过之后 → 采集新增日志、判定 A1..A9、写报告

    判定表（PASS / FAIL / UNKNOWN；UNKNOWN 不算失败，表示「本轮没触发、需人工点」）：
      A1 插件目录已就位（文件数 >= 60）
      A2 插件日志出现「启动完成」
      A3 插件日志无 [ERROR] 且无致命异常（NoClassDefFoundError / NoSuchMethodError / ...）
      A4 出现「网易云插件已就绪」toast 记录（P0 机器侧证据；肉眼渲染仍需人看）
      A5 出现「已停止」或插件从未启动（干净退出）
      A6 spw.db 未被插件改写（与基线指纹一致）
      A7 能力矩阵（caps）已探测
      A10 宿主 logs.txt 无插件相关异常

    真机验收里必须人工的部分（不在此脚本覆盖内）：工坊里能否看到/启用插件、toast 真的弹出来了、
    配置页 7 组控件渲染与 on_click 反射（0.3.0 起含「在线播放（实验）」组）、搜索结果渲染与在线播放是否真的出声。
    说明：0.5.0 瘦身版已整体移除歌词/匹配/下载/打标链路，原先的 A8/A9 两项「歌词/下载链路痕迹」随之撤销。

.PARAMETER Phase
    Pre（默认）或 Post。

.PARAMETER DryRun
    透传给 install-plugin.ps1：只报告不写盘。

.PARAMETER SkipInstall
    Pre 阶段只做基线快照，不安装。

.PARAMETER Force
    透传给 install-plugin.ps1：目标目录已存在时先删再装。

.PARAMETER DataRoot
    用户数据根目录。缺省 $env:APPDATA\Salt Player for Windows（真实宿主）。
    **指定为其它目录 = 沙盒自测模式**：即使宿主正在运行也不拒绝，可离线验证本脚本自身；
    这种模式下采集到的当然不是真机证据，脚本会在结论里显式标注。

.EXAMPLE
    pwsh -File tools\accept-host.ps1 -Phase Pre
    pwsh -File tools\accept-host.ps1 -Phase Post
    pwsh -File tools\accept-host.ps1 -Phase Pre -DataRoot harness\host-accept\tmp-dataroot
#>
[CmdletBinding()]
param(
    [ValidateSet('Pre', 'Post')] [string] $Phase = 'Pre',
    [switch] $DryRun,
    [switch] $SkipInstall,
    [switch] $Force,
    [string] $DataRoot
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

$SpwProcessName = 'Salt Player for Windows'
$PluginId       = 'com.example.netease'
$PluginVersion  = '0.4.0'
$PluginDirName  = "plugin-$PluginId-$PluginVersion"
$MinFiles       = 60

$RepoRoot   = Split-Path -Parent $PSScriptRoot
$DistDir    = Join-Path $RepoRoot 'build\dist'
$InstallPs1 = Join-Path $PSScriptRoot 'install-plugin.ps1'
$OutDir     = Join-Path $RepoRoot 'harness\host-accept'
$BaselineJson = Join-Path $OutDir 'baseline.json'
$ReportTxt    = Join-Path $OutDir 'report-post.txt'

$HostDefaultRoot = Join-Path $env:APPDATA 'Salt Player for Windows'
if (-not $DataRoot) { $DataRoot = $HostDefaultRoot }
$DataRoot = [System.IO.Path]::GetFullPath($DataRoot)
$IsTempRoot = ($DataRoot.TrimEnd('\') -ne [System.IO.Path]::GetFullPath($HostDefaultRoot).TrimEnd('\'))

$PluginsDir = Join-Path $DataRoot 'workshop\plugins'
$PluginDir  = Join-Path $PluginsDir $PluginDirName
$PluginData = Join-Path $DataRoot "workshop\data\$PluginId"
$SpwDb      = Join-Path $DataRoot 'spw.db'
$HostLog    = Join-Path $DataRoot 'logs.txt'

$FatalPatterns = @('NoClassDefFoundError', 'NoSuchMethodError', 'NoSuchFieldError',
                   'ExceptionInInitializerError', 'UnsatisfiedLinkError', 'UnsupportedClassVersionError',
                   'ClassNotFoundException', 'LinkageError')

function Write-Step { param([string]$m) Write-Host ''; Write-Host "== $m ==" -ForegroundColor Cyan }
function Write-Ok   { param([string]$m) Write-Host "  [OK]   $m" -ForegroundColor Green }
function Write-Bad  { param([string]$m) Write-Host "  [FAIL] $m" -ForegroundColor Red }
function Write-Unk  { param([string]$m) Write-Host "  [UNK]  $m" -ForegroundColor Yellow }
function Write-Info { param([string]$m) Write-Host "  $m" }

function Get-SpwProcess {
    return @(Get-Process -Name $SpwProcessName -ErrorAction SilentlyContinue)
}

function Get-Fingerprint {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $i = Get-Item -LiteralPath $Path
    $hash = $null
    try { $hash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256 -ErrorAction Stop).Hash } catch { $hash = $null }
    return [pscustomobject]@{ path = $Path; length = $i.Length; mtimeUtc = $i.LastWriteTimeUtc.ToString('o'); sha256 = $hash }
}

function Get-LogFiles { param([string]$Dir) if (Test-Path -LiteralPath $Dir) { return @(Get-ChildItem -LiteralPath $Dir -Filter *.log -File -ErrorAction SilentlyContinue) } return @() }

# 读取「基线之后新增」的内容：基线记录每个日志文件的字节长度，这里从该偏移继续读
function Get-DeltaText {
    param([string]$File, [int]$Offset)
    try {
        $fs = [System.IO.File]::Open($File, 'Open', 'Read', 'ReadWrite')
        try {
            if ($Offset -gt $fs.Length) { $Offset = 0 }
            [void]$fs.Seek($Offset, 'Begin')
            $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
            try { return $sr.ReadToEnd() } finally { $sr.Dispose() }
        } finally { $fs.Dispose() }
    } catch { return '' }
}

function Save-Json { param($Object, [string]$Path) New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null; $Object | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $Path -Encoding UTF8 }

# ---------------------------------------------------------------- Phase Pre
function Invoke-Pre {
    Write-Step '真实宿主前置检查'
    if ($IsTempRoot) { Write-Unk "沙盒自测模式：DataRoot = $DataRoot（不是真实宿主目录；即使宿主在运行也不拒绝）" }
    $procs = @(Get-SpwProcess)
    if ($procs.Count -gt 0 -and -not $IsTempRoot) {
        Write-Bad "Salt Player for Windows 正在运行（PID: $(($procs | ForEach-Object Id) -join ', ')）"
        Write-Info '请完全退出宿主后再跑：关闭主窗口 **不够**，要右键托盘图标 → 退出（否则插件目录被占用，宿主会用 .oldPlugin 改名机制）。'
        exit 1
    }
    Write-Ok '宿主未运行，插件目录不会被占用'

    $artifact = Get-ChildItem -Path $DistDir -Filter *.spmod -File -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $artifact) { Write-Bad "build\dist 下没有 .spmod，请先跑 build.ps1（或 python tools\build.py）"; exit 1 }
    $finger = Get-Fingerprint $artifact.FullName
    Write-Ok ("产物 {0}  {1} B  sha256 {2}" -f $artifact.Name, $finger.length, ($finger.sha256.Substring(0, 16) + '…'))

    $logOffsets = @{}
    foreach ($lf in (Get-LogFiles (Join-Path $PluginData 'logs'))) { $logOffsets[$lf.FullName] = [int]$lf.Length }

    $dirFiles = 0
    if (Test-Path -LiteralPath $PluginDir) { $dirFiles = @(Get-ChildItem -LiteralPath $PluginDir -Recurse -File).Count }
    $dataFiles = 0
    if (Test-Path -LiteralPath $PluginData) { $dataFiles = @(Get-ChildItem -LiteralPath $PluginData -Recurse -File).Count }

    $base = [pscustomobject]@{
        takenAtUtc   = (Get-Date).ToUniversalTime().ToString('o')
        dataRoot     = $DataRoot
        isTempRoot   = $IsTempRoot
        artifact     = $finger
        spwDb        = (Get-Fingerprint $SpwDb)
        spwDbWal     = (Get-Fingerprint ($SpwDb + '-wal'))
        pluginsDir   = @(Get-ChildItem -LiteralPath $PluginsDir -ErrorAction SilentlyContinue | ForEach-Object Name)
        pluginDirFiles = $dirFiles
        pluginDataFiles = $dataFiles
        logOffsets   = $logOffsets
    }
    Save-Json -Object $base -Path $BaselineJson
    Write-Ok "基线已写入 $BaselineJson"

    if ($SkipInstall) { Write-Info '（-SkipInstall）跳过安装'; return }

    Write-Step '安装插件（install-plugin.ps1）'
    $installArgs = @('-NoProfile', '-File', $InstallPs1)
    if ($IsTempRoot) { $installArgs += @('-DataRoot', $DataRoot) }
    if ($DryRun) { $installArgs += '-DryRun' }
    if ($Force)  { $installArgs += '-Force' }
    & pwsh @installArgs
    $code = $LASTEXITCODE
    if ($code -ne 0) { Write-Bad "install-plugin.ps1 退出码 $code"; exit $code }
    Write-Ok '安装完成'

    Write-Step '下一步（人工）'
    Write-Info '1) 启动 Salt Player for Windows → 打开「工坊 / Workshop」→ 确认能看到「网易云音乐接入」并启用它。'
    Write-Info '2) 等 3 秒，看是否弹出「网易云插件已就绪 v0.4.0」的 toast。'
    Write-Info '3) 配置页 →「网易云客户端」→ 点「接入客户端（取登录态）」。日志（tag=client）应依次出现：'
    Write-Info '   「客户端接入：<ensureRunning 结果>」（已在跑 / 已由插件拉起 PID=xxx / 未安装→只给引导，不静默安装）→'
    Write-Info '   「客户端接入成功：凭据已加密落盘（明文长度 N，内容不落日志）」→ 账号信息行（昵称 / uid / VIP）。'
    Write-Info '   未登录（客户端里没有 MUSIC_U）时会自动把客户端窗口调出来，日志打「客户端里没有 MUSIC_U（未登录）」，登录后再点一次即可。'
    Write-Info '   判定：出现 tag=client 的接入成功行 + 配置页显示昵称 ⇒ 客户端接入通了（网页扫码登录已整块移除）。'
    Write-Info '   其它按钮：「显示客户端窗口」（登录/找歌用）、「接管客户端（会重启它）」（已有实例没开调试口时用）、「关闭我拉起的客户端」（用户自己开的实例一律不动）、「客户端状态」（只读自检）。'
    Write-Info '4) 配置页 →「在线播放（实验）」→ 先点「在线模式自检」（只读，不播放），再点「试播一首（我喜欢的音乐）」——'
    Write-Info '   出声即在线播放可用（0.3.19/0.3.20 已实测出声；链路行 ① 注册表 → ①′ 拦截器 → ①′⁺ 播放器属性清单 → ② 曲目项 → ③ 选曲播放 → ③″ 直驱播放器 →'
    Write-Info '   ③⁗ 直调装载器 → ③⁗′ 真拦截器 → ③⁗″ 播放器取流路径 → ④/④+4s/④+8s 读回 → ⑥+12s/18s/24s 播放进度）。'
    Write-Info '   分诊：④ 行「播放器=Ready」且宿主 %APPDATA%\Salt Player for Windows\logs.txt 里 `HttpRangeBassStream opened` 长时间不 closed ⇒ 在放；'
    Write-Info '   出现 `FileNotOpened` ⇒ 取流没过拦截器（检查 ①′ 行的「含此实例=true」）。'
    Write-Info '5) 无人值守路径：数据目录放 online-autotest.flag（内容随意），启动后 15 秒自动试播在线一次（本地对照不自动跑，要对照请手动点「试播本地文件（对照诊断）」）。'
    Write-Info '6) 然后跑：pwsh -File tools\accept-host.ps1 -Phase Post'
}

# --------------------------------------------------------------- Phase Post
function Invoke-Post {
    Write-Step '真机验收证据采集（Post）'
    if (-not (Test-Path -LiteralPath $BaselineJson)) { Write-Bad "缺少基线 $BaselineJson —— 请先跑 -Phase Pre"; exit 1 }
    $base = Get-Content -LiteralPath $BaselineJson -Raw | ConvertFrom-Json
    if ($base.isTempRoot) { Write-Unk "沙盒自测模式（DataRoot = $($base.dataRoot)）：下面结果**不是真机证据**，只用于验证本脚本自身" }

    $procs = @(Get-SpwProcess)
    $running = $procs.Count -gt 0
    Write-Info ("宿主进程：{0}" -f ($(if ($running) { "运行中（PID " + (($procs | ForEach-Object Id) -join ', ') + "）" } else { '未运行' })))

    # ---- 插件日志（只看基线之后的新增内容）
    $logText = ''
    foreach ($lf in (Get-LogFiles (Join-Path $PluginData 'logs'))) {
        $off = 0
        $prop = $base.logOffsets.PSObject.Properties[$lf.FullName]
        if ($prop) { $off = [int]$prop.Value }
        $logText += (Get-DeltaText -File $lf.FullName -Offset $off)
    }
    $hasStart = $logText -match '启动完成'
    $hasToast = $logText -match '网易云插件已就绪'
    $hasCaps  = $logText -match '能力矩阵'
    $hasStop  = $logText -match '已停止'
    $errCount = ([regex]::Matches($logText, '\[ERROR\]')).Count
    $warnCount = ([regex]::Matches($logText, '\[WARN')).Count
    $fatalHits = @()
    foreach ($p in $FatalPatterns) { if ($logText -match [regex]::Escape($p)) { $fatalHits += $p } }

    $fileCount = if (Test-Path -LiteralPath $PluginDir) { @(Get-ChildItem -LiteralPath $PluginDir -Recurse -File).Count } else { 0 }
    $dbNow = Get-Fingerprint $SpwDb
    # 注意：ConvertFrom-Json 会把 ISO 时间串自动转成 DateTime，直接比字符串会误判「已变」
    $dbSame = $false
    $dbHashSame = $false
    if ($null -ne $base.spwDb -and $null -ne $dbNow) {
        $baseMtime = if ($base.spwDb.mtimeUtc -is [datetime]) { $base.spwDb.mtimeUtc.ToUniversalTime().ToString('o') } else { [string]$base.spwDb.mtimeUtc }
        $dbSame = ($base.spwDb.length -eq $dbNow.length -and $baseMtime -eq $dbNow.mtimeUtc)
        $dbHashSame = ($null -ne $base.spwDb.sha256 -and $null -ne $dbNow.sha256 -and $base.spwDb.sha256 -eq $dbNow.sha256)
    }
    $hostLogTail = if (Test-Path -LiteralPath $HostLog) { (Get-Content -LiteralPath $HostLog -Tail 40) -join "`n" } else { '' }
    $hostLogFatal = @()
    foreach ($p in $FatalPatterns) { if ($hostLogTail -match [regex]::Escape($p)) { $hostLogFatal += $p } }

    # StrictMode 下 $null / 单元素会被解包：访问 .Count 会炸，这里统一强制成数组
    $fatalHits    = @($fatalHits)
    $hostLogFatal = @($hostLogFatal)

    Write-Step '插件日志新增内容（尾部 25 行）'
    if ([string]::IsNullOrWhiteSpace($logText)) { Write-Unk '基线之后没有新增插件日志（插件没启动？）' }
    else { ($logText -split "`n" | Select-Object -Last 25) | ForEach-Object { Write-Host "    $($_.TrimEnd())" -ForegroundColor DarkGray } }

    # A6 的判据：宿主**自身**在启动/退出时也会写 spw.db（WAL checkpoint），
    # 所以「mtime 变了」不能归因给插件。真正可判定的是：插件源码里**从不出现** spw.db。
    $srcRefs = @()
    $srcDocRefs = @()
    $srcRoot = Join-Path $RepoRoot 'src'
    if (Test-Path -LiteralPath $srcRoot) {
        $hits = @(Get-ChildItem -LiteralPath $srcRoot -Recurse -File -Include *.java,*.json |
                  Select-String -Pattern 'spw\.db' -ErrorAction SilentlyContinue)
        # 只有**代码**里出现才算违规；注释（* / // / /*）里写明「本插件从不写 spw.db」是反证不是违规
        $srcRefs    = @($hits | Where-Object { $_.Line.Trim() -notmatch '^(\*|//|/\*)' })
        $srcDocRefs = @($hits | Where-Object { $_.Line.Trim() -match '^(\*|//|/\*)' })
    }

    Write-Step '判定表'
    $rows = @()
    foreach ($r in @(
        [pscustomobject]@{ Id = 'A1';  Item = '插件目录已就位';            Verdict = $(if ($fileCount -ge $MinFiles) { 'PASS' } else { 'FAIL' });   Detail = "$fileCount 个文件（阈值 $MinFiles），路径 $PluginDir" },
        [pscustomobject]@{ Id = 'A2';  Item = '插件日志出现「启动完成」';   Verdict = $(if ($hasStart) { 'PASS' } else { 'FAIL' });                   Detail = 'start() 跑到底的机器侧证据' },
        [pscustomobject]@{ Id = 'A3';  Item = '无 ERROR / 无致命异常';      Verdict = $(if ($fatalHits.Count -gt 0) { 'FAIL' } elseif ($errCount -eq 0) { 'PASS' } else { 'FAIL' }); Detail = "ERROR=$errCount WARN=$warnCount 致命异常=$(if ($fatalHits.Count) { $fatalHits -join ',' } else { '无' })" },
        [pscustomobject]@{ Id = 'A4';  Item = '就绪 toast 已调度';          Verdict = $(if ($hasToast) { 'PASS' } else { 'UNKNOWN' });                Detail = '日志有「已就绪」即调度成功；肉眼是否真弹出仍需人确认' },
        [pscustomobject]@{ Id = 'A5';  Item = '干净退出（stop）';           Verdict = $(if ($hasStop) { 'PASS' } else { 'UNKNOWN' });                 Detail = "$(if ($hasStop) { '日志出现「已停止」' } else { '本轮日志无「已停止」——宿主进程级退出未必回调 stop()，请到工坊「停用插件」再跑一次本脚本' })" },
        [pscustomobject]@{ Id = 'A6';  Item = '插件从不碰宿主 spw.db';      Verdict = $(if ($srcRefs.Count -eq 0) { 'PASS' } else { 'FAIL' });       Detail = "插件源码引用 spw.db 共 $($srcRefs.Count) 处（注释里写明的 $($srcDocRefs.Count) 处不算）；spw.db $(if ($dbHashSame) { 'sha256 与基线逐字节一致' } elseif ($dbSame) { 'length/mtime 与基线一致' } elseif ($dbNow) { 'mtime 已变（宿主自身 WAL 写入，不能归因给插件）' } else { '读不到' })" },
        [pscustomobject]@{ Id = 'A7';  Item = '能力矩阵已探测';             Verdict = $(if ($hasCaps) { 'PASS' } else { 'FAIL' });                    Detail = 'caps 行 = 宿主 API 可用性实探' },
        [pscustomobject]@{ Id = 'A10'; Item = '宿主 logs.txt 无插件异常';   Verdict = $(if ($hostLogFatal.Count -gt 0) { 'FAIL' } else { 'PASS' });   Detail = "$(if ($hostLogFatal.Count) { $hostLogFatal -join ',' } else { '未命中致命异常模式' })" }
    )) { $rows += $r }

    $rows | Format-Table -AutoSize | Out-String -Width 200 | Write-Host

    $fail = @($rows | Where-Object Verdict -eq 'FAIL')
    $unk  = @($rows | Where-Object Verdict -eq 'UNKNOWN')
    Write-Step '结论'
    if ($fail.Count -eq 0) { Write-Ok "机器侧无 FAIL（PASS $((@($rows | Where-Object Verdict -eq 'PASS')).Count) 项，UNKNOWN $($unk.Count) 项）" }
    else { Write-Bad "有 $($fail.Count) 项 FAIL：$((($fail | ForEach-Object { "$($_.Id) $($_.Item)" }) -join '; '))" }
    if ($unk.Count -gt 0) { Write-Unk "另有 $($unk.Count) 项 UNKNOWN（未触发，需要人工点一遍）：$((($unk | ForEach-Object { $_.Id }) -join ', '))" }
    Write-Info '提醒：工坊可见/启用、toast 真实渲染、配置页控件与 on_click、在线播放是否真的出声 —— 这些仍是人工验收项，脚本不代替。'

    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $report = @()
    $report += "SPW 网易云插件 · 真机验收报告（Post）"
    $report += "采集时间：$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))  宿主运行：$running"
    $report += "基线时间：$($base.takenAtUtc)  产物：$($base.artifact.path) $($base.artifact.length) B sha256 $($base.artifact.sha256)"
    $report += ''
    $report += ($rows | Format-Table -AutoSize | Out-String -Width 220)
    $report += '--- 插件日志新增（尾部 40 行）---'
    $report += (($logText -split "`n" | Select-Object -Last 40) -join "`n")
    $report += ''
    $report += '--- 宿主 logs.txt 尾部 40 行 ---'
    $report += $hostLogTail
    $report | Set-Content -LiteralPath $ReportTxt -Encoding UTF8
    Write-Ok "报告已写入 $ReportTxt"

    if ($fail.Count -gt 0) { exit 1 }
}

switch ($Phase) {
    'Pre'  { Invoke-Pre }
    'Post' { Invoke-Post }
}
