<#
.SYNOPSIS
    Current-version host acceptance. Pre captures hash-only local-record baseline; Post collects evidence.
.DESCRIPTION
    project.json is the only package identity source. Exit 0 complete, 1 failure, 2 unverified.
    Offline/sandbox results never count as device evidence. No raw logs or credentials enter reports.
    Pre requires a stopped host; default installs the exact project package, -SkipInstall captures only.
    Post checks local metadata/playlist membership preservation and newly introduced plugin corruption.
    User edits between phases can change baseline records; use a quiet host for this check.
#>
[CmdletBinding()]
param(
    [ValidateSet('Pre', 'Post')] [string] $Phase = 'Pre',
    [switch] $DryRun,
    [switch] $SkipInstall,
    [switch] $Force,
    [string] $DataRoot,
    [string] $EvidenceDir,
    [ValidateSet('Off','On')] [string] $DevMode = 'Off'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

$SpwProcessName = 'Salt Player for Windows'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$project = Get-Content (Join-Path $RepoRoot 'project.json') -Raw | ConvertFrom-Json
$PluginId = $project.pluginId
$PluginVersion = $project.version
$PluginDirName  = "plugin-$PluginId-$PluginVersion"
$MinFiles       = 60

$RepoRoot   = Split-Path -Parent $PSScriptRoot
$DistDir    = Join-Path $RepoRoot 'build\dist'
$InstallPs1 = Join-Path $PSScriptRoot 'install-plugin.ps1'
$OutDir = if ($EvidenceDir) { [System.IO.Path]::GetFullPath($EvidenceDir) } else { Join-Path $RepoRoot 'harness/host-accept' }
$BaselineJson = Join-Path $OutDir 'baseline.json'
$ReportTxt = Join-Path $OutDir 'report-post.txt'
$DbBaseline = Join-Path $OutDir 'db-baseline.json'
$DbGate = Join-Path $RepoRoot 'tools/db-preservation.py'

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

    $artifactPath = Join-Path $DistDir "plugin-$PluginId-$PluginVersion.spmod"
    $artifact = Get-Item -LiteralPath $artifactPath -ErrorAction SilentlyContinue
    if (-not $artifact) { Write-Bad "build\dist 下没有 .spmod，请先跑 build.ps1（或 python tools\build.py）"; exit 1 }
    $finger = Get-Fingerprint $artifact.FullName
    Write-Ok ("产物 {0}  {1} B  sha256 {2}" -f $artifact.Name, $finger.length, ($finger.sha256.Substring(0, 16) + '…'))

    $logOffsets = @{}
    foreach ($lf in (Get-LogFiles (Join-Path $PluginData 'logs'))) { $logOffsets[$lf.FullName] = [int]$lf.Length }

    $dirFiles = 0
    if (Test-Path -LiteralPath $PluginDir) { $dirFiles = @(Get-ChildItem -LiteralPath $PluginDir -Recurse -File).Count }
    $dataFiles = 0
    if (Test-Path -LiteralPath $PluginData) { $dataFiles = @(Get-ChildItem -LiteralPath $PluginData -Recurse -File).Count }

    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
    # Capture only hashes; never copy user song metadata into the report.
    if (Test-Path -LiteralPath $DbBaseline) { Remove-Item -LiteralPath $DbBaseline }
    & python $DbGate snapshot $SpwDb $DbBaseline
    $dbCapture = $LASTEXITCODE
    $base = [pscustomobject]@{
        pluginId = $PluginId
        pluginVersion = $PluginVersion
        devMode = $DevMode
        hostLogOffset = $(if (Test-Path -LiteralPath $HostLog) { (Get-Item -LiteralPath $HostLog).Length } else { 0 })
        dbCapture = $dbCapture
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
    $installArgs = @('-NoProfile', '-File', $InstallPs1, '-Spmod', $artifact.FullName)
    if ($IsTempRoot) { $installArgs += @('-DataRoot', $DataRoot) }
    if ($DryRun) { $installArgs += '-DryRun' }
    if ($Force)  { $installArgs += '-Force' }
    & pwsh @installArgs
    $code = $LASTEXITCODE
    if ($code -ne 0) { Write-Bad "install-plugin.ps1 退出码 $code"; exit $code }
    Write-Ok '安装完成'

    Write-Step '下一步（人工）'
    Write-Info '1) 启动 Salt Player for Windows → 打开「工坊 / Workshop」→ 确认能看到「网易云音乐接入」并启用它。'
    Write-Info "2) 确认加载版本为 $PluginVersion；开发者模式 Off 时不得出现插件 toast。"
    Write-Info '3) 检查登录、搜索起播、切歌、拖动进度、歌单同步与合并开关。'
    Write-Info '4) 分别核对列表、播放条、播放页封面和歌词，记录画面与实际声音。'
    Write-Info '5) 停用插件检查退出；随后以相同 DataRoot、EvidenceDir、DevMode 运行 -Phase Post。'

}

# --------------------------------------------------------------- Phase Post
function Invoke-Post {
    Write-Step '真机验收证据采集（Post）'
    if (-not (Test-Path -LiteralPath $BaselineJson)) { Write-Bad "缺少基线 $BaselineJson —— 请先跑 -Phase Pre"; exit 1 }
    $base = Get-Content -LiteralPath $BaselineJson -Raw | ConvertFrom-Json
    if ($base.dataRoot -ne $DataRoot -or $base.pluginId -ne $PluginId -or $base.devMode -ne $DevMode) {
        throw 'Baseline identity/version/root/dev mode mismatch; rerun Pre for this target.'
    }
    # Post is bound to the exact package captured by Pre, even after source version advances.
    $PluginVersion = $base.pluginVersion
    $PluginDirName = "plugin-$PluginId-$PluginVersion"
    $PluginDir = Join-Path $PluginsDir $PluginDirName
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
    $toastLines = @($logText -split "`n" | Where-Object { $_ -match '\[INFO\s*\].*\[toast\]' -and $_ -notmatch '不弹提示|去重跳过' })
    $hasToast = $toastLines.Count -gt 0
    $modeObserved = $logText -match ('dev_mode=' + $(if ($DevMode -eq 'On') { 'true' } else { 'false' }))
    $hasCaps  = $logText -match '能力矩阵'
    $hasStop  = $logText -match '已停止'
    $errCount = ([regex]::Matches($logText, '\[ERROR\]')).Count
    $warnCount = ([regex]::Matches($logText, '\[WARN')).Count
    $fatalHits = @()
    foreach ($p in $FatalPatterns) { if ($logText -match [regex]::Escape($p)) { $fatalHits += $p } }

    $fileCount = if (Test-Path -LiteralPath $PluginDir) { @(Get-ChildItem -LiteralPath $PluginDir -Recurse -File).Count } else { 0 }
    $hostLogTail = if (Test-Path -LiteralPath $HostLog) { Get-DeltaText -File $HostLog -Offset $base.hostLogOffset } else { '' }
    $manifestPath = Join-Path $PluginDir 'META-INF/MANIFEST.MF'
    $installedVersion = $null
    $installedId = $null
    if (Test-Path -LiteralPath $manifestPath) {
        $manifest = Get-Content -LiteralPath $manifestPath -Raw
        if ($manifest -match '(?m)^Plugin-Version:\s*([^\r\n]+)') { $installedVersion = $Matches[1].Trim() }
        if ($manifest -match '(?m)^Plugin-Id:\s*([^\r\n]+)') { $installedId = $Matches[1].Trim() }
    }
    $identityMatch = $installedVersion -eq $PluginVersion -and $installedId -eq $PluginId
    $dbResult = & python $DbGate check $SpwDb $DbBaseline
    $dbCode = $LASTEXITCODE
    $hostLogFatal = @()
    foreach ($p in $FatalPatterns) { if ($hostLogTail -match [regex]::Escape($p)) { $hostLogFatal += $p } }

    # StrictMode 下 $null / 单元素会被解包：访问 .Count 会炸，这里统一强制成数组
    $fatalHits    = @($fatalHits)
    $hostLogFatal = @($hostLogFatal)

    # Raw logs can contain user-entered text. Reports contain verdicts/counts only.
    Write-Step '判定表'
    $rows = @()
    foreach ($r in @(
        [pscustomobject]@{ Id = 'A1';  Item = '插件目录已就位';            Verdict = $(if ($fileCount -ge $MinFiles -and $identityMatch) { 'PASS' } else { 'FAIL' });   Detail = "$fileCount 个文件（阈值 $MinFiles）；Manifest id=$installedId version=$installedVersion，预期 $PluginId $PluginVersion" },
        [pscustomobject]@{ Id = 'A2';  Item = '插件日志出现「启动完成」';   Verdict = $(if ($hasStart) { 'PASS' } else { 'FAIL' });                   Detail = 'start() 跑到底的机器侧证据' },
        [pscustomobject]@{ Id = 'A3';  Item = '无 ERROR / 无致命异常';      Verdict = $(if ($fatalHits.Count -gt 0) { 'FAIL' } elseif ($errCount -eq 0) { 'PASS' } else { 'FAIL' }); Detail = "ERROR=$errCount WARN=$warnCount 致命异常=$(if ($fatalHits.Count) { $fatalHits -join ',' } else { '无' })" },
        [pscustomobject]@{ Id = 'A4'; Item = '开发者模式 toast 策略'; Verdict = $(if (-not $modeObserved) { 'UNKNOWN' } elseif ($DevMode -eq 'Off' -and $hasToast) { 'FAIL' } elseif ($DevMode -eq 'Off') { 'PASS' } elseif ($hasToast) { 'PASS' } else { 'UNKNOWN' }); Detail = "模式=$DevMode 调度记录=$($toastLines.Count)；模式需在本次配置日志中确认，画面另验" },
        [pscustomobject]@{ Id = 'A5';  Item = '干净退出（stop）';           Verdict = $(if ($hasStop) { 'PASS' } else { 'UNKNOWN' });                 Detail = "$(if ($hasStop) { '日志出现「已停止」' } else { '本轮日志无「已停止」——宿主进程级退出未必回调 stop()，请到工坊「停用插件」再跑一次本脚本' })" },
        [pscustomobject]@{ Id = 'A6'; Item = '本地记录保留与插件关联完整性'; Verdict = $(if ($dbCode -eq 0) { 'PASS' } elseif ($dbCode -eq 1) { 'FAIL' } else { 'UNKNOWN' }); Detail = "$dbResult" },
        [pscustomobject]@{ Id = 'A7';  Item = '能力矩阵已探测';             Verdict = $(if ($hasCaps) { 'PASS' } else { 'FAIL' });                    Detail = 'caps 行 = 宿主 API 可用性实探' },
        [pscustomobject]@{ Id = 'A10'; Item = '宿主 logs.txt 无插件异常';   Verdict = $(if ($hostLogFatal.Count -gt 0) { 'FAIL' } elseif ([string]::IsNullOrWhiteSpace($hostLogTail)) { 'UNKNOWN' } else { 'PASS' });   Detail = "$(if ($hostLogFatal.Count) { $hostLogFatal -join ',' } else { '未命中致命异常模式' })" }
    )) { $rows += $r }
    foreach ($id in @('V1 搜索实际出声','V2 切歌与拖动进度','V3 歌单及合并开关','V4 三处封面与歌词','V5 窗口进度错误与重试')) {
        $rows += [pscustomobject]@{ Id=$id.Split(' ')[0]; Item=$id.Substring(3); Verdict='UNKNOWN'; Detail='未验证；必须另附本次真机行为与画面证据' }
    }
    Save-Json -Object @{ sandbox=$IsTempRoot; pluginVersion=$PluginVersion; rows=$rows } -Path (Join-Path $OutDir 'report-post.json')

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
    $report | Set-Content -LiteralPath $ReportTxt -Encoding UTF8
    Write-Ok "报告已写入 $ReportTxt"

    if ($fail.Count -gt 0) { exit 1 }
    if ($unk.Count -gt 0 -or $IsTempRoot) { exit 2 }
}

switch ($Phase) {
    'Pre'  { Invoke-Pre }
    'Post' { Invoke-Post }
}
