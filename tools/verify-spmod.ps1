<#
.SYNOPSIS
    SPW 网易云插件 · .spmod 产物结构校验器（纯 PowerShell，零外部依赖）。

.DESCRIPTION
    逐项打印 PASS/FAIL。任何 FAIL 时退出码非 0。
    校验依据：docs/00-工程约定与接口合同.md §1/§3/§7、docs/01-宿主环境与插件机制.md §4、docs/06-构建打包与调试.md §2。

    关键事实（来自对宿主 1.18.5 与 PF4J 3.12.0 的实测反编译）：
      * PF4J 的 ManifestPluginDescriptorFinder 对 **zip 形态** 只读 `classes/META-INF/MANIFEST.MF`；
        对 **目录形态** 用 FileUtils.findFile 递归找第一个名为 MANIFEST.MF 的文件。
        → 两份 manifest 必须存在、必须一致，且折行正确，否则宿主读不到元数据。
      * jar 规范：每条物理行（含 CRLF 的行尾）不得超过 72 字节；续行必须以单个空格开头。

.PARAMETER Spmod
    .spmod 路径。缺省取 build\dist\ 下最新的 *.spmod。

.PARAMETER Json
    只输出机器可读 JSON 结果，不打印表格。

.EXAMPLE
    pwsh -File tools\verify-spmod.ps1
    pwsh -File tools\verify-spmod.ps1 -Spmod build\dist\plugin-com.example.netease-0.2.0.spmod
#>
[CmdletBinding()]
param(
    [string] $Spmod,
    [switch] $Json
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

$RepoRoot    = Split-Path -Parent $PSScriptRoot
$ProjectJson = Join-Path $RepoRoot 'project.json'
$DistDir     = Join-Path $RepoRoot 'build\dist'
$MaxLineBytes = 72
$AllowedPrefTypes = @('switch', 'list', 'button', 'seekbar', 'edittext')
$ForbiddenClassPrefixes = @('com/xuncorp/', 'org/pf4j/')

# ---------------------------------------------------------------- 契约常量（单一来源）
# 各分组宿主托管配置文件名（docs/00 §7 / §8）。改名只改这一行 —— P6.6 与 P10.1/P10.2 共用。
# 注意：client_cfg.json 是「配置」；account.json 是 svc.CookieVault 的凭据密文，两者不得撞名。
# 0.4.0：网页扫码登录（account_cfg.json）整块撤下，界面只剩客户端接入 / 在线播放 / 维护三组。
# 0.11.7：第 4 组 probe.json = **临时探针组**（P-2/P-3/P-4/P-5 手工触发入口）。
#         0.11.10：四条管线的验收证据已齐，该组已连同 `NeteasePlugin.probeP2..P5` 与
#         `svc\ProbeWindow` / `svc\ProbeCover` **整组删除**（登记见 docs/00 §7 与 §8、
#         `docs\51-W1c探针组退役-0.11.10.md`）；harness `[H9.12]` 断言它不得回潮。
#         W1（去客户端化）落地时会撤下 client_cfg.json 组，届时这里同步去掉。
# 0.11.7 起：客户端组（client_cfg.json）已删；账号组改为 account_cfg.json（手机号+原生登录），
# 新增「状态与缓存」组 cache_cfg.json（封面/歌词完成度与两个独立清理按钮）。
# 0.11.30（需求③ 开发者模式）：生效中的 schema = **用户档**，只剩 3 组 ——
# 「在线播放（实验）」整组（online.json）连同另外 9 个开发者入口一起搬进了
# classes\preference_config.dev.json（开发者档）；用户档里不再有 online.json 组，
# 所以这里同步收窄成 3 个文件名。两份档位文件的齐全性由 P10.21~P10.24 断言。
$ExpectedConfigs = @('config.json', 'account_cfg.json', 'cache_cfg.json')

# P3 新增实现类（docs/14 §3；必须在包内，见 P10.10）
# 0.11.27：皮肤层拆出 ui/HostTheme（宿主同款样式令牌）与 LoginDialog 的绘制零件
#          （CardPane / PillButton / FlatField / RootPane 都是内部类，P10.10 只钉两个代表）。
# 0.11.30：ui/LoginWindow（独立扫码窗）**退役** —— 整类删掉、换成反向断言（P10.25 查它不在包里）；
#          core/QrEncoder（二维码编码器）与 core/DevMode（开发者模式两档资源切换）接位。
$ExpectedP3Classes = @(
    'classes/com/example/netease/svc/AccountService.class',
    'classes/com/example/netease/svc/CookieVault.class',
    'classes/com/example/netease/core/QrEncoder.class',
    'classes/com/example/netease/core/DevMode.class',
    'classes/com/example/netease/host/HostTrackListGate.class',
    'classes/com/example/netease/host/HostTrackListGate$ListMapHandler.class',
    'classes/com/example/netease/ui/LoginDialog.class',
    'classes/com/example/netease/ui/HostTheme.class',
    'classes/com/example/netease/ui/LoginDialog$CardPane.class',
    'classes/com/example/netease/ui/LoginDialog$PillButton.class',
    'classes/com/example/netease/ui/LoginDialog$FlatField.class',
    'classes/com/example/netease/ui/PlaylistWindow.class'
)

# ---------------------------------------------------------------- 结果收集

$script:Results = New-Object System.Collections.Generic.List[object]

function Add-Result {
    param(
        [Parameter(Mandatory)][string] $Id,
        [Parameter(Mandatory)][string] $Title,
        [bool] $Ok,
        [string] $Detail = '',
        [string] $Offender = ''
    )
    $script:Results.Add([pscustomobject]@{
        id       = $Id
        title    = $Title
        ok       = $Ok
        detail   = $Detail
        offender = $Offender
    }) | Out-Null
}

function Write-Section {
    param([string] $Name)
    if (-not $Json) {
        Write-Host ''
        Write-Host "── $Name " -ForegroundColor DarkCyan -NoNewline
        Write-Host ('─' * [Math]::Max(1, 62 - $Name.Length)) -ForegroundColor DarkCyan
    }
}

# ---------------------------------------------------------------- 小工具

# 取一个 ZipArchiveEntry 的原始字节
function Get-EntryBytes {
    param($Archive, [string] $Name)
    $key = $Name.Replace('\', '/')
    foreach ($e in $Archive.Entries) {
        if ($e.FullName.Replace('\', '/') -ceq $key) {
            $ms = New-Object System.IO.MemoryStream
            $s = $e.Open()
            try { $s.CopyTo($ms) } finally { $s.Dispose() }
            return $ms.ToArray()
        }
    }
    return $null
}

# 从字节流里读 ZIP（用于检查 lib/*.jar 内容）
function Read-InnerZipEntries {
    param([byte[]] $Bytes)
    try {
        $ms = New-Object System.IO.MemoryStream(, $Bytes)
        # .NET 6+ 提供 ZipArchive(Stream, ZipArchiveMode) 重载
        $zip = $null
        try {
            $zip = New-Object System.IO.Compression.ZipArchive($ms, [System.IO.Compression.ZipArchiveMode]::Read, $true)
        } catch {
            return @{ ok = $false; error = $_.Exception.Message; names = @() }
        }
        $names = @()
        foreach ($e in $zip.Entries) { $names += $e.FullName.Replace('\', '/') }
        $zip.Dispose()
        return @{ ok = $true; error = ''; names = $names }
    } catch {
        return @{ ok = $false; error = $_.Exception.Message; names = @() }
    }
}

function Convert-BytesToText {
    param([byte[]] $Bytes)
    return [System.Text.Encoding]::UTF8.GetString($Bytes)
}

# ---------------------------------------------------------------- manifest 解析

<#
    解析 MANIFEST.MF：
      * 按物理行切分（保留行尾以正确统计字节数）
      * 解折行（续行 = 以单个空格开头的行，去掉那个前导空格后拼接）
    返回 @{ Physical = @(...); Entries = @{k=v}; FoldedKeys = @(...) ; Bytes = byte[] }
#>
function Parse-Manifest {
    param([byte[]] $Bytes)

    $text = [System.Text.Encoding]::UTF8.GetString($Bytes)
    $raw  = $text -split "`n"
    # 末尾换行会产生一个空尾巴
    if ($raw.Count -gt 0 -and $raw[$raw.Count - 1] -eq '') {
        $raw = $raw[0..($raw.Count - 2)]
    }

    $physical  = @()   # 每行 = 原始文本（不含行尾）
    $unfolded  = @()   # 解折行后的逻辑行
    foreach ($line in $raw) {
        $l = $line
        if ($l.EndsWith("`r")) { $l = $l.Substring(0, $l.Length - 1) }
        $physical += $l
        if ($l.StartsWith(' ') -and $unfolded.Count -gt 0) {
            $unfolded[$unfolded.Count - 1] = $unfolded[$unfolded.Count - 1] + $l.Substring(1)
        } else {
            $unfolded += $l
        }
    }

    $entries = @{}
    $foldedKeys = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $unfolded.Count; $i++) {
        $l = $unfolded[$i]
        $colon = $l.IndexOf(':')
        if ($colon -lt 0) { continue }
        $k = $l.Substring(0, $colon).Trim()
        $v = $l.Substring($colon + 1)
        if ($v.StartsWith(' ')) { $v = $v.Substring(1) }
        if ($k.Length -eq 0) { continue }
        $entries[$k] = $v
    }

    return [pscustomobject]@{
        Physical   = $physical
        Entries    = $entries
        Bytes      = $Bytes
        Text       = $text
    }
}

# 返回 @(byte[]) —— 按 LF 切分，**并去掉行尾 CR**。
# ⚠️ 口径（务必别改回去）：jar 规范的 72 字节上限按「行内容」计，**不含量终止符 CRLF**。
#    参考实现一致：JDK 的 Manifest 写入器（make72Safe 在第 72 个字符处断行）与
#    tools/build.py 的 _fold_line（首行 ≤72 字节；续行 = 1 个空格 + ≤71 字节 = 仍 ≤72）。
#    若把 CR 也算进去，build.py 折行正好落在 72 字节的行会被误报成 73 字节 FAIL。
function Get-ManifestPhysicalByteLines {
    param([byte[]] $Bytes)
    $lines = New-Object System.Collections.Generic.List[byte[]]
    $cur   = New-Object System.Collections.Generic.List[byte]
    foreach ($b in $Bytes) {
        if ($b -eq 10) {
            $lines.Add((Remove-TrailingCr $cur.ToArray())) | Out-Null
            $cur = New-Object System.Collections.Generic.List[byte]
        } else {
            $cur.Add($b) | Out-Null
        }
    }
    if ($cur.Count -gt 0) { $lines.Add((Remove-TrailingCr $cur.ToArray())) | Out-Null }
    return $lines
}

# 去掉行尾的 CR（只去一个）。注意 $arr.Count -eq 1 时不能用 0..-1（PowerShell 会得到 0,-1 倒序范围）
function Remove-TrailingCr {
    param([byte[]] $Arr)
    if ($null -eq $Arr -or $Arr.Count -eq 0) { return ,@() }
    if ($Arr[$Arr.Count - 1] -ne 13) { return ,$Arr }
    if ($Arr.Count -eq 1) { return ,@() }
    return ,($Arr[0..($Arr.Count - 2)])
}

# ---------------------------------------------------------------- 主流程

$zip = $null
$zipArchivePath = $null

try {
    # === 0. 定位产物 ===============================================
    if (-not $Spmod) {
        if (-not (Test-Path -LiteralPath $DistDir)) {
            Add-Result 'P0.1' '定位 .spmod 产物' $false "目录不存在：$DistDir（先跑 python tools\build.py）"
            throw [System.Management.Automation.RuntimeException] 'NO_DIST_DIR'
        }
        # 注意 StrictMode 3.0：单元素时 Get-ChildItem 返回 FileInfo（无 .Count），必须 @() 包住
        $cand = @(Get-ChildItem -LiteralPath $DistDir -Filter '*.spmod' -File -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending)
        if ($cand.Count -eq 0) {
            Add-Result 'P0.1' '定位 .spmod 产物' $false "build\dist 下没有 *.spmod（先跑 python tools\build.py）"
            throw [System.Management.Automation.RuntimeException] 'NO_SPMOD'
        }
        $Spmod = $cand[0].FullName
    }
    if (-not (Test-Path -LiteralPath $Spmod)) {
        throw "找不到 .spmod：$Spmod"
    }
    $SpmodFull = (Resolve-Path -LiteralPath $Spmod).Path
    $zipArchivePath = $SpmodFull
    $zip = [System.IO.Compression.ZipFile]::OpenRead($SpmodFull)

    # === 0. 读 project.json =========================================
    $pj = $null
    if (Test-Path -LiteralPath $ProjectJson) {
        try {
            $pj = Get-Content -LiteralPath $ProjectJson -Raw -Encoding UTF8 | ConvertFrom-Json
        } catch {
            $pj = $null
        }
    }

    if (-not $Json) {
        Write-Host ''
        Write-Host '════════════════════════════════════════════════════════════════' -ForegroundColor Cyan
        Write-Host ' .spmod 结构校验 · SPW 网易云插件' -ForegroundColor Cyan
        Write-Host '════════════════════════════════════════════════════════════════' -ForegroundColor Cyan
        Write-Host "  产物 : $SpmodFull"
        Write-Host ("  大小 : {0:N0} bytes   条目数 : {1}" -f (Get-Item -LiteralPath $SpmodFull).Length, $zip.Entries.Count)
        if ($pj) { Write-Host "  期望 : id=$($pj.pluginId)  ver=$($pj.version)  main=$($pj.mainClass)" }
        else     { Write-Host '  期望 : project.json 读取失败 —— 所有比对项将按「无法比对」处理' -ForegroundColor Yellow }
    }

    if (-not $pj) {
        Add-Result 'P0.2' '读取 project.json（作为比对基准）' $false "无法解析 $ProjectJson"
    } else {
        Add-Result 'P0.2' '读取 project.json（作为比对基准）' $true "id=$($pj.pluginId) version=$($pj.version)"
    }

    # 归一化条目名映射（Compress-Archive 在 Windows 上可能写反斜杠）
    $entryNames = @()
    $entryMap   = @{}
    foreach ($e in $zip.Entries) {
        $n = $e.FullName.Replace('\', '/')
        $entryNames += $n
        if (-not $entryMap.ContainsKey($n)) { $entryMap[$n] = $e }
    }
    $hasEntry = { param([string] $n) $entryMap.ContainsKey($n.Replace('\', '/')) }

    # ================================================================
    # 1. 根 META-INF/MANIFEST.MF
    # ================================================================
    Write-Section '1 · Manifest 存在性与折行'

    $rootManifestBytes = Get-EntryBytes $zip 'META-INF/MANIFEST.MF'
    $rootOk = $null -ne $rootManifestBytes
    Add-Result 'P1.1' '根 META-INF/MANIFEST.MF 存在' $rootOk `
        $(if ($rootOk) { "$($rootManifestBytes.Length) bytes" } else { '缺失' })

    $clsManifestBytes = Get-EntryBytes $zip 'classes/META-INF/MANIFEST.MF'
    $clsOk = $null -ne $clsManifestBytes

    $rootParsed = $null
    if ($rootOk) {
        $rootParsed = Parse-Manifest $rootManifestBytes

        # --- 1.2 每条物理行的字节数 ≤ 72 ---
        $byteLines = Get-ManifestPhysicalByteLines $rootManifestBytes
        $over = @()
        for ($i = 0; $i -lt $byteLines.Count; $i++) {
            if ($byteLines[$i].Length -gt $MaxLineBytes) {
                $over += "第 $($i + 1) 行 = $($byteLines[$i].Length) bytes"
            }
        }
        $lineOk = ($over.Count -eq 0)
        Add-Result 'P1.2' "根 manifest 每条物理行内容 ≤ $MaxLineBytes 字节（不含 CRLF 行终止符；共 $($byteLines.Count) 行，最长 $(($byteLines | ForEach-Object { $_.Length } | Measure-Object -Maximum).Maximum)）" `
            $lineOk '' ($over -join ' / ')

        # --- 1.2b 续行必须以单个空格开头（折行完整性） ---
        $badCont = @()
        for ($i = 0; $i -lt $rootParsed.Physical.Count; $i++) {
            $l = $rootParsed.Physical[$i]
            if ($l.StartsWith('  ')) { $badCont += "第 $($i + 1) 行续行以 2 个空格开头" }
            if ($l.Length -gt 0 -and -not $l.Contains(':')) {
                # 既没有冒号又不以空格开头 → 说明是被截断/丢失前导空格的续行
                if (-not $l.StartsWith(' ')) { $badCont += "第 $($i + 1) 行无冒号且非续行：'$l'" }
            }
        }
        # 更直接的判据：折行后能读出全部必填键
        $needKeys = @('Plugin-Class', 'Plugin-Id', 'Plugin-Version', 'Plugin-Name')
        $missing  = @()
        foreach ($k in $needKeys) {
            if (-not $rootParsed.Entries.ContainsKey($k) -or [string]::IsNullOrEmpty($rootParsed.Entries[$k])) {
                $missing += $k
            }
        }
        $unfoldOk = ($missing.Count -eq 0) -and ($badCont.Count -eq 0)
        Add-Result 'P1.3' '解折行后能读出 Plugin-Class / Plugin-Id / Plugin-Version / Plugin-Name' `
            $unfoldOk '' `
            $(@($missing | ForEach-Object { "缺键 $_" }) + $badCont -join ' / ')

        if ($rootParsed.Entries.ContainsKey('Plugin-Name')) {
            Add-Result 'P1.3b' 'Plugin-Name 解折行结果（含中文必须靠续行拼接）' $true "『$($rootParsed.Entries['Plugin-Name'])』"
        }
        if ($rootParsed.Entries.ContainsKey('Plugin-Description')) {
            Add-Result 'P1.3c' 'Plugin-Description 解折行结果' $true "『$($rootParsed.Entries['Plugin-Description'])』"
        }

        # --- 1.4 与 project.json 一致 ---
        if ($pj) {
            $cmp = @(
                @{ key = 'Plugin-Id';          want = [string]$pj.pluginId },
                @{ key = 'Plugin-Version';     want = [string]$pj.version },
                @{ key = 'Plugin-Class';       want = [string]$pj.mainClass },
                @{ key = 'Plugin-Name';        want = [string]$pj.name },
                @{ key = 'Plugin-Provider';    want = [string]$pj.provider },
                @{ key = 'Plugin-Description'; want = [string]$pj.description },
                @{ key = 'Plugin-Open-Source-Url'; want = [string]$pj.openSourceUrl }
            )
            $mismatch = @()
            foreach ($c in $cmp) {
                $got = if ($rootParsed.Entries.ContainsKey($c.key)) { $rootParsed.Entries[$c.key] } else { '<缺>' }
                if ($got -cne $c.want) { $mismatch += "$($c.key)：实『$got』≠ 期望『$($c.want)』" }
            }
            $verOk = ($mismatch.Count -eq 0)
            Add-Result 'P1.4' 'manifest 键值与 project.json 逐项一致' $verOk '' ($mismatch -join ' | ')
        }

        # --- 1.5 Plugin-Has-Config ---
        if ($pj -and $pj.hasConfig) {
            $hv = if ($rootParsed.Entries.ContainsKey('Plugin-Has-Config')) { $rootParsed.Entries['Plugin-Has-Config'] } else { '<缺>' }
            Add-Result 'P1.5' "Project-Has-Config=true → manifest 必须有 Plugin-Has-Config: true" `
                ($hv -ieq 'true') "实际『$hv』"
        } else {
            Add-Result 'P1.5' 'Plugin-Has-Config 检查（project.hasConfig=false，跳过严格比对）' $true ''
        }
    }

    # ================================================================
    # 2. classes/META-INF/MANIFEST.MF 与根一致
    # ================================================================
    Write-Section '2 · 两份 Manifest 一致性'
    $sameOk = $false
    if ($rootOk -and $clsOk) {
        $sameOk = ($rootManifestBytes.Length -eq $clsManifestBytes.Length)
        if ($sameOk) {
            for ($i = 0; $i -lt $rootManifestBytes.Length; $i++) {
                if ($rootManifestBytes[$i] -ne $clsManifestBytes[$i]) { $sameOk = $false; break }
            }
        }
    }
    Add-Result 'P2.1' 'classes/META-INF/MANIFEST.MF 存在' $clsOk $(if ($clsOk) { "$($clsManifestBytes.Length) bytes" } else { '缺失' })
    Add-Result 'P2.2' '两份 manifest 字节完全一致' $sameOk `
        $(if ($rootOk -and $clsOk) { "root=$($rootManifestBytes.Length) classes=$($clsManifestBytes.Length)" } else { '无法比对（有一份缺失）' })

    # ================================================================
    # 3. extensions.idx
    # ================================================================
    Write-Section '3 · 扩展点注册索引'

    $idxBytes = Get-EntryBytes $zip 'classes/META-INF/extensions.idx'
    $idxOk = $null -ne $idxBytes
    Add-Result 'P3.1' 'classes/META-INF/extensions.idx 存在' $idxOk $(if ($idxOk) { "$($idxBytes.Length) bytes" } else { '缺失' })

    $idxClasses = @()
    if ($idxOk) {
        $idxText = (Convert-BytesToText $idxBytes) -replace "`r`n", "`n"
        # StrictMode 3.0 下管道结果可能是标量（无 .Count），必须 @() 包住
        $idxLines = @($idxText -split "`n" | Where-Object { $_.Trim().Length -gt 0 })
        $firstLine = if ($idxLines.Count -gt 0) { $idxLines[0] } else { '<空文件>' }
        Add-Result 'P3.2' 'extensions.idx 首行恰好是 "# Generated by PF4J"' `
            ($firstLine -ceq '# Generated by PF4J') "实际『$firstLine』"

        $idxClasses = @($idxLines | Where-Object { -not $_.StartsWith('#') } | ForEach-Object { $_.Trim() })
        Add-Result 'P3.3' "extensions.idx 含扩展类（$($idxClasses.Count) 条）" ($idxClasses.Count -gt 0) ($idxClasses -join ', ')

        if ($pj) {
            $wantExt = @($pj.extensionClasses)
            $missIdx = @($wantExt | Where-Object { $idxClasses -notcontains $_ })
            Add-Result 'P3.4' 'extensions.idx 含 project.json 声明的全部扩展类' ($missIdx.Count -eq 0) '' `
                $(if ($missIdx.Count) { '缺：' + ($missIdx -join ', ') } else { '' })
        }
    }

    # ================================================================
    # 4. META-INF/services
    # ================================================================
    Write-Section '4 · services 注册文件'

    $svcName = $null
    if ($pj -and $pj.extensionPoint) { $svcName = [string]$pj.extensionPoint }
    elseif ($rootParsed -and $rootParsed.Entries.ContainsKey('Plugin-Extension-Point')) { $svcName = $rootParsed.Entries['Plugin-Extension-Point'] }
    if (-not $svcName) { $svcName = 'com.xuncorp.spw.workshop.api.PlaybackExtensionPoint' }

    $svcPath  = "classes/META-INF/services/$svcName"
    $svcBytes = Get-EntryBytes $zip $svcPath
    $svcOk = $null -ne $svcBytes
    Add-Result 'P4.1' "$svcPath 存在" $svcOk $(if ($svcOk) { "$($svcBytes.Length) bytes" } else { '缺失' })

    $svcClasses = @()
    if ($svcOk) {
        $svcText = (Convert-BytesToText $svcBytes) -replace "`r`n", "`n"
        $svcClasses = @($svcText -split "`n" |
            ForEach-Object { ($_ -split '#')[0].Trim() } |
            Where-Object { $_.Length -gt 0 })
        Add-Result 'P4.2' "services 文件含扩展类（$($svcClasses.Count) 条）" ($svcClasses.Count -gt 0) ($svcClasses -join ', ')

        if ($pj) {
            $wantExt = @($pj.extensionClasses)
            $missSvc = @($wantExt | Where-Object { $svcClasses -notcontains $_ })
            Add-Result 'P4.3' 'services 文件含 project.json 声明的全部扩展类' ($missSvc.Count -eq 0) '' `
                $(if ($missSvc.Count) { '缺：' + ($missSvc -join ', ') } else { '' })
        }
        if ($idxClasses.Count -gt 0) {
            $diff = @(Compare-Object -ReferenceObject ($idxClasses | Sort-Object) -DifferenceObject ($svcClasses | Sort-Object))
            Add-Result 'P4.4' 'extensions.idx 与 services 文件内容一致' ($diff.Count -eq 0) '' `
                $(if ($diff.Count) { ($diff | ForEach-Object { "$($_.SideIndicator) $($_.InputObject)" }) -join ' / ' } else { '' })
        }
    }

    # ================================================================
    # 5. 类文件存在性
    # ================================================================
    Write-Section '5 · 主类 / 扩展类 .class 落位'

    $classEntries = @($entryNames | Where-Object { $_ -like 'classes/*.class' })
    $classSet = @{}
    foreach ($c in $classEntries) {
        $fq = $c.Substring('classes/'.Length)
        $fq = $fq.Substring(0, $fq.Length - '.class'.Length).Replace('/', '.')
        $classSet[$fq] = $true
    }
    Add-Result 'P5.0' "classes/ 下 .class 数量" ($classEntries.Count -gt 0) "$($classEntries.Count) 个"

    if ($pj) {
        $mustClasses = @($pj.mainClass) + @($pj.extensionClasses)
        $missCls = @($mustClasses | Where-Object { -not $classSet.ContainsKey($_) })
        foreach ($mc in @($pj.mainClass)) {
            $expectPath = 'classes/' + ($mc.Replace('.', '/')) + '.class'
            Add-Result 'P5.1' "主类 .class 存在：$expectPath" ($classSet.ContainsKey($mc)) `
                $(if ($classSet.ContainsKey($mc)) { 'ok' } else { '缺失' })
        }
        foreach ($ec in @($pj.extensionClasses)) {
            $expectPath = 'classes/' + ($ec.Replace('.', '/')) + '.class'
            Add-Result 'P5.2' "扩展类 .class 存在：$expectPath" ($classSet.ContainsKey($ec)) `
                $(if ($classSet.ContainsKey($ec)) { 'ok' } else { '缺失' })
        }
        # extensions.idx / services 里出现的类也必须有 class
        $otherDeclared = @(@($idxClasses) + @($svcClasses) | Sort-Object -Unique)
        $missOther = @($otherDeclared | Where-Object { $_.Length -gt 0 -and -not $classSet.ContainsKey($_) })
        if ($otherDeclared.Count -gt 0) {
            Add-Result 'P5.3' '索引里声明的类都能在 classes/ 下找到 .class' ($missOther.Count -eq 0) '' `
                $(if ($missOther.Count) { '缺：' + ($missOther -join ', ') } else { '' })
        }
    }

    # ================================================================
    # 6. preference_config.json
    # ================================================================
    Write-Section '6 · preference_config.json 契约'

    $prefBytes = Get-EntryBytes $zip 'classes/preference_config.json'
    $prefOk = $null -ne $prefBytes
    $needPref = $true
    if ($pj -and $null -ne $pj.hasConfig) { $needPref = [bool]$pj.hasConfig }
    if ($needPref) {
        Add-Result 'P6.1' 'classes/preference_config.json 存在（Plugin-Has-Config=true 时必须）' $prefOk `
            $(if ($prefOk) { "$($prefBytes.Length) bytes" } else { '缺失' })
    } else {
        Add-Result 'P6.1' 'classes/preference_config.json 存在性（hasConfig=false，仅提示）' $true `
            $(if ($prefOk) { "存在，$($prefBytes.Length) bytes" } else { '不存在' })
    }

    if ($prefOk) {
        # BOM 容忍
        $prefText = Convert-BytesToText $prefBytes
        if ($prefText.Length -gt 0 -and $prefText[0] -eq [char]0xFEFF) { $prefText = $prefText.Substring(1) }

        $prefObj = $null
        $jsonErr = ''
        try { $prefObj = $prefText | ConvertFrom-Json }
        catch { $jsonErr = $_.Exception.Message }
        Add-Result 'P6.2' 'preference_config.json 是合法 JSON' ($null -ne $prefObj) '' $jsonErr

        if ($null -ne $prefObj) {
            $hasConfigs = ($prefObj.PSObject.Properties.Name -contains 'configs')
            Add-Result 'P6.3' '顶层含 configs 数组' ($hasConfigs -and $null -ne $prefObj.configs) ''

            if ($hasConfigs -and $null -ne $prefObj.configs) {
                $cfgList = @($prefObj.configs)
                Add-Result 'P6.4' "configs[] 条目数" ($cfgList.Count -gt 0) "$($cfgList.Count) 组"

                $cfgProblems = New-Object System.Collections.Generic.List[string]
                $prefTypeCount = @{}
                $idx = 0
                foreach ($cfg in $cfgList) {
                    $names = @($cfg.PSObject.Properties.Name)
                    foreach ($k in @('title', 'config', 'preferences')) {
                        if ($names -notcontains $k) { $cfgProblems.Add("configs[$idx] 缺字段 $k") | Out-Null }
                        elseif ($null -eq $cfg.$k) { $cfgProblems.Add("configs[$idx].$k 为 null") | Out-Null }
                    }
                    if ($names -contains 'preferences' -and $null -ne $cfg.preferences) {
                        $pi = 0
                        foreach ($p in @($cfg.preferences)) {
                            $pn = @($p.PSObject.Properties.Name)
                            if ($pn -notcontains 'type') {
                                $cfgProblems.Add("configs[$idx].preferences[$pi] 缺 type") | Out-Null
                                $pi++; continue
                            }
                            $t = [string]$p.type
                            if ($AllowedPrefTypes -notcontains $t) {
                                $cfgProblems.Add("configs[$idx].preferences[$pi] type='$t' 不在 $($AllowedPrefTypes -join '|')") | Out-Null
                            } else {
                                if ($prefTypeCount.ContainsKey($t)) { $prefTypeCount[$t]++ } else { $prefTypeCount[$t] = 1 }
                            }
                            switch ($t) {
                                'switch' {
                                    foreach ($k in @('key', 'title', 'summary', 'default_value')) {
                                        if ($pn -notcontains $k) { $cfgProblems.Add("configs[$idx].preferences[$pi] (switch) 缺 $k") | Out-Null }
                                    }
                                }
                                'list' {
                                    foreach ($k in @('key', 'title', 'summary', 'entries', 'entry_values', 'default_value')) {
                                        if ($pn -notcontains $k) { $cfgProblems.Add("configs[$idx].preferences[$pi] (list) 缺 $k") | Out-Null }
                                    }
                                    if ($pn -contains 'entries' -and $pn -contains 'entry_values') {
                                        $nE = @($p.entries).Count
                                        $nV = @($p.entry_values).Count
                                        if ($nE -ne $nV) {
                                            $cfgProblems.Add("configs[$idx].preferences[$pi] (list key=$($p.key)) entries($nE) 与 entry_values($nV) 长度不等") | Out-Null
                                        }
                                    }
                                }
                                'button' {
                                    foreach ($k in @('title', 'summary', 'arrow_type', 'on_click')) {
                                        if ($pn -notcontains $k) { $cfgProblems.Add("configs[$idx].preferences[$pi] (button) 缺 $k") | Out-Null }
                                    }
                                    if ($pn -contains 'arrow_type') {
                                        $at = [string]$p.arrow_type
                                        if (@('none', 'link', 'arrow') -notcontains $at) {
                                            $cfgProblems.Add("configs[$idx].preferences[$pi] arrow_type='$at' 不在 none|link|arrow") | Out-Null
                                        }
                                    }
                                    if ($pn -contains 'on_click') {
                                        $oc = [string]$p.on_click
                                        if (($oc -split '\.').Count -lt 3) {
                                            $cfgProblems.Add("configs[$idx].preferences[$pi] on_click='$oc' 不是 包名.类名.方法名") | Out-Null
                                        }
                                    }
                                }
                                'seekbar' {
                                    foreach ($k in @('key', 'title', 'summary', 'min', 'max')) {
                                        if ($pn -notcontains $k) { $cfgProblems.Add("configs[$idx].preferences[$pi] (seekbar) 缺 $k") | Out-Null }
                                    }
                                    if ($pn -contains 'min' -and $pn -contains 'max') {
                                        $mn = 0.0; $mx = 0.0
                                        $okMin = [double]::TryParse([string]$p.min, [ref]$mn)
                                        $okMax = [double]::TryParse([string]$p.max, [ref]$mx)
                                        if (-not $okMin -or -not $okMax) {
                                            $cfgProblems.Add("configs[$idx].preferences[$pi] min/max 不是数字") | Out-Null
                                        } elseif ($mn -ge $mx) {
                                            $cfgProblems.Add("configs[$idx].preferences[$pi] min($mn) >= max($mx)") | Out-Null
                                        }
                                    }
                                }
                                'edittext' {
                                    foreach ($k in @('key', 'title', 'summary', 'default_value')) {
                                        if ($pn -notcontains $k) { $cfgProblems.Add("configs[$idx].preferences[$pi] (edittext) 缺 $k") | Out-Null }
                                    }
                                }
                            }
                            $pi++
                        }
                    }
                    $idx++
                }
                $typeSummary = ($prefTypeCount.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)×$($_.Value)" }) -join ' '
                Add-Result 'P6.5' "每组有 title/config/preferences；每个 preference 字段齐全且 type 合法（$typeSummary）" `
                    ($cfgProblems.Count -eq 0) '' (($cfgProblems | Select-Object -First 12) -join ' | ')

                # config 文件名在 docs/00 §7 白名单（$ExpectedConfigs 单一来源，见脚本顶部）
                $knownConfigs = $ExpectedConfigs
                $unknownCfg = @($cfgList | ForEach-Object { [string]$_.config } | Where-Object { $_ -and ($knownConfigs -notcontains $_) })
                Add-Result 'P6.6' '各组 config 字段是 docs/00 §7 约定的文件名' ($unknownCfg.Count -eq 0) '' `
                    $(if ($unknownCfg.Count) { '未在契约中：' + ($unknownCfg -join ', ') } else { '' })
            }
        }
    }

    # ================================================================
    # 7. 宿主 API / PF4J 类泄漏、.java 源文件
    # ================================================================
    Write-Section '7 · 包内不得含宿主 API / PF4J 类 / 源码'

    $leakClass = @($entryNames | Where-Object {
        $t = $_
        @($ForbiddenClassPrefixes | Where-Object { $t.StartsWith($_) }).Count -gt 0
    })
    Add-Result 'P7.1' '包内无 classes/com/xuncorp/** 与 classes/org/pf4j/**' ($leakClass.Count -eq 0) '' `
        $(if ($leakClass.Count) { ($leakClass | Select-Object -First 10) -join ' / ' } else { '' })

    $javaFiles = @($entryNames | Where-Object { $_.EndsWith('.java', [System.StringComparison]::OrdinalIgnoreCase) })
    Add-Result 'P7.2' '包内无 .java 文件' ($javaFiles.Count -eq 0) '' ($javaFiles -join ' / ')

    $kotlinSrc = @($entryNames | Where-Object { $_.EndsWith('.kt') })
    Add-Result 'P7.3' '包内无 .kt 文件' ($kotlinSrc.Count -eq 0) '' ($kotlinSrc -join ' / ')

    $bannedClass = @($entryNames | Where-Object {
        $_ -like 'classes/kotlin/*' -or $_ -like 'classes/org/slf4j/*' -or $_ -like 'classes/com/xuncorp/*'
    })
    Add-Result 'P7.4' 'classes/ 下无第三方运行时（kotlin/**、org/slf4j/** 等被展开进 classes/）' `
        ($bannedClass.Count -eq 0) '' (($bannedClass | Select-Object -First 10) -join ' / ')

    # ================================================================
    # 8. lib/ 内容
    # ================================================================
    Write-Section '8 · lib/ 依赖 jar'

    $libJars = @($entryNames | Where-Object { $_ -like 'lib/*' -and $_ -like '*.jar' })
    $libOther = @($entryNames | Where-Object { $_ -like 'lib/*' -and $_ -notlike '*.jar' -and $_ -ne 'lib/' })
    Add-Result 'P8.0' "lib/ 下 jar 数量" $true "$($libJars.Count) 个"

    $jarDetail = New-Object System.Collections.Generic.List[string]
    $badJars  = New-Object System.Collections.Generic.List[string]
    foreach ($lj in $libJars) {
        $jb = Get-EntryBytes $zip $lj
        $info = Read-InnerZipEntries -Bytes $jb
        if (-not $info.ok) {
            $jarDetail.Add("$lj  ⚠ 无法解析：$($info.error)") | Out-Null
            $badJars.Add("$lj 无法解析") | Out-Null
            continue
        }
        $jarDetail.Add("$lj  ($($jb.Length) bytes, $($info.names.Count) 条目)") | Out-Null
        # 宿主抽取 API 泄漏
        $hit = @($info.names | Where-Object {
            $_ -like 'com/xuncorp/spw/workshop/*' -or $_ -like 'org/pf4j/*' -or $_ -like 'kotlin/reflect/*'
        })
        if ($hit.Count -gt 0) {
            $badJars.Add("$lj 含宿主/框架类：$(($hit | Select-Object -First 5) -join ', ')") | Out-Null
        }
        # 宿主抽取出来的 API jar 特征：只有 com/xuncorp/spw/workshop/**
        $nonApiEntries = @($info.names | Where-Object { $_ -notlike 'com/xuncorp/spw/workshop/*' -and $_ -notlike 'META-INF/*' -and $_ -notlike 'META-INF' })
        if ($info.names.Count -gt 0 -and $nonApiEntries.Count -eq 0) {
            $badJars.Add("$lj 看起来就是宿主抽取的 API jar（compileOnly 依赖不得进包）") | Out-Null
        }
        # slf4j 不得进包（docs/01 §4.1 铁律 8）
        if (@($info.names | Where-Object { $_ -like 'org/slf4j/*' }).Count -gt 0) {
            $badJars.Add("$lj 含 org/slf4j/**（宿主已内嵌，不得打进插件包）") | Out-Null
        }
    }
    Add-Result 'P8.1' 'lib/ 下每个 jar 都能解析（并逐个列出条目数）' $true '' ''
    Add-Result 'P8.2' 'lib/ 下无宿主抽取 API jar / 无 org/pf4j / 无 org/slf4j' ($badJars.Count -eq 0) '' ($badJars -join ' | ')
    Add-Result 'P8.3' 'lib/ 下无非 jar 杂项文件' ($libOther.Count -eq 0) '' ($libOther -join ' / ')

    # ================================================================
    # 10. P3 新增功能面（账号 / 歌单 / 批量 / 标签回写）
    #     —— 只做「包内静态事实」比对；「public static 无参」由 harness 反射覆盖（H9.4）。
    # ================================================================
    Write-Section '10 · P3 新增功能面（账号 / 歌单 / 批量 / 标签回写）'

    # 本节私有小工具：StrictMode 下安全取属性（缺字段返回 $null 而不是抛异常）
    function Get-P3Prop {
        param($Obj, [string] $Name)
        if ($null -eq $Obj) { return $null }
        if (@($Obj.PSObject.Properties.Name) -notcontains $Name) { return $null }
        return $Obj.$Name
    }
    function Get-P3Group {
        param($Root, [string] $ConfigFile)
        if ($null -eq $Root) { return $null }
        foreach ($g in @($Root.configs)) {
            if ([string](Get-P3Prop $g 'config') -ceq $ConfigFile) { return $g }
        }
        return $null
    }
    function Get-P3Pref {
        param($Group, [string] $Title)
        if ($null -eq $Group) { return $null }
        foreach ($p in @((Get-P3Prop $Group 'preferences'))) {
            if ([string](Get-P3Prop $p 'title') -ceq $Title) { return $p }
        }
        return $null
    }
    function Format-P3Val {
        param($V)
        if ($null -eq $V) { return 'null' }
        return "$V"
    }

    # P10.10 与 preference_config.json 无关，先判（纯包内事实）
    $missClasses = @($ExpectedP3Classes | Where-Object { $entryNames -notcontains $_ })
    Add-Result 'P10.10' "P3 新增实现类都在包内（$($ExpectedP3Classes.Count) 个）" ($missClasses.Count -eq 0) `
        "$($ExpectedP3Classes.Count - $missClasses.Count)/$($ExpectedP3Classes.Count) 存在" `
        $(if ($missClasses.Count) { '缺：' + ($missClasses -join ', ') } else { '' })

    # ---------- P10.27 「合并歌单」门：必须带上 map 逐列表变换（0.11.46 二次修正）
    #  首版把谓词直挂 FlowKt.filter，但流的元素是 List<Track> ⇒ 谓词拿到 ArrayList 反射 getId()
    #  抛异常被吞 ⇒ 整份列表放行；真机症状「关了开关歌曲里仍显示网易云」。这里用变换器标记字符串
    #  正向钉住新实现、并反向钉住旧谓词标记不许回潮（注释不进字节码，只认真实常量）。
    $gateBytes = Get-EntryBytes $zip 'classes/com/example/netease/host/HostTrackListGate.class'
    $gateText = if ($null -ne $gateBytes) { [System.Text.Encoding]::UTF8.GetString($gateBytes) } else { '' }
    $gateInnerBytes = Get-EntryBytes $zip 'classes/com/example/netease/host/HostTrackListGate$ListMapHandler.class'
    $gateInnerText = if ($null -ne $gateInnerBytes) { [System.Text.Encoding]::UTF8.GetString($gateInnerBytes) } else { '' }
    #  marker 常量在内部类；外层不该再出现 "filter"（旧版解析 FlowKt.filter 才需要的字符串常量）
    $gateOk = $gateInnerText.Contains('netease-merge-playlists-transformer') `
        -and (-not $gateInnerText.Contains('netease-merge-playlists-predicate')) `
        -and (-not $gateText.Contains('filter'))
    Add-Result 'P10.27' '合并歌单门是 map 逐列表变换版（变换器标记在、旧 filter 谓词标记不在）' $gateOk '' `
        '门类仍是旧 filter 谓词版：关掉开关后「歌曲」列表不会被过滤（2026-10-04 真机事故）'

    # ---------- P10.28 提示闸：Notifier 必须在开发者模式关闭时静默（0.11.46 追加）
    #  用户口径：「只要是提示的，关闭了开发者模式就完全不会弹出」。单点闸在 Notifier.toast()，
    #  用一个只可能出现在该闸里的字符串钉住（注释不进字节码）。
    $notifierBytes = Get-EntryBytes $zip 'classes/com/example/netease/core/Notifier.class'
    $notifierText = if ($null -ne $notifierBytes) { [System.Text.Encoding]::UTF8.GetString($notifierBytes) } else { '' }
    $notifierOk = $notifierText.Contains('（开发者模式关闭，不弹提示）') `
        -and $notifierText.Contains('com/example/netease/cfg/PluginConfig')
    Add-Result 'P10.28' 'Notifier 带开发者模式静默闸（关 = 全部提示不弹）' $notifierOk '' `
        'Notifier 未接开发者模式闸：关闭开发者模式后启动「已就绪」等提示仍会弹（用户明确禁止）'

    # ---------- P10.29 列表去重（0.11.47 用户需求）
    #  用户口径：「两个歌名、标签、作者完全一样 … 隐藏掉占用空间最小的那一个」。判定是纯函数
    #  dedupHiddenMask（离线探针 tools\smoke\DedupGateProbe.java），这里钉方法名 + 日志文案。
    $dedupOk = $gateText.Contains('dedupHiddenMask') `
        -and $gateText.Contains('同名同标签同作者') `
        -and $gateText.Contains('去重隐藏')
    Add-Result 'P10.29' '列表去重（三要素相同保占用空间最大者）已接进歌曲列表变换' $dedupOk '' `
        'HostTrackListGate 里没有 dedupHiddenMask / 去重日志：本地与网易云重复曲目会同时显示'

    # ---------- P10.30 扫库屏蔽（0.11.47 用户需求）
    #  用户口径：「扫库只管本地的，不管插件接进来的音乐（别冒感叹号）」。实现 = 第二处 DAO 静态字段
    #  \u069e.\u0528 也装门，拦下「不可读标记类（U+0D37）List」里 netease-* 的写。
    #  只认真实常量：类名两个都以字符串常量存在（转义在编译期解析为真字符），加方法名与日志文案。
    $scanOk = $gateText.Contains('installScanGate') `
        -and $gateText.Contains('扫库屏蔽') `
        -and $gateText.Contains('scanGateSelfTest') `
        -and $gateText.Contains('shouldBlockUnreadableWrite') `
        -and $gateText.Contains('androidx.compose.ui.' + [char]0x069E) `
        -and $gateText.Contains('androidx.compose.ui.' + [char]0x0D37)
    Add-Result 'P10.30' '扫库屏蔽（TrackRepoKt 的第二处 DAO 门 + 不可读标记过滤）已在包内' $scanOk '' `
        '缺扫库屏蔽实现：宿主「刷新音乐库」会把插件曲目全部标成不可读（界面感叹号）'

    # ---------- P10.31 刷新音乐库联动（0.11.48 用户需求）
    #  用户口径：「用户点击软件里的刷新音乐库就会触发同步网易云那边的音乐，网易云那边用户添加了音乐
    #  这样也能手动刷新出新音乐」。实现 = 把宿主 er 的「扫库中」状态（\u052C，MutableStateFlow）
    #  换成代理：扫库协程开头那次 emit(true) 触发 NeteasePlugin.syncFromRefreshLibrary（force 静默通路）；
    #  注释不进字节码，只认真实常量 / 方法名；并反向钉住「不得残留取证标记」。
    $pluginBytes = Get-EntryBytes $zip 'classes/com/example/netease/NeteasePlugin.class'
    $pluginText = if ($null -ne $pluginBytes) { [System.Text.Encoding]::UTF8.GetString($pluginBytes) } else { '' }
    $linkOk = $gateText.Contains('installRefreshHook') `
        -and $gateText.Contains('refreshHookSelfTest') `
        -and $gateText.Contains('isScanStartCall') `
        -and $gateText.Contains('刷新音乐库联动') `
        -and $gateText.Contains('androidx.compose.ui.er') `
        -and $gateText.Contains('kotlinx.coroutines.flow.MutableStateFlow') `
        -and $gateText.Contains([string][char]0x052C) `
        -and $pluginText.Contains('syncFromRefreshLibrary') `
        -and $pluginText.Contains('force:刷新音乐库')
    Add-Result 'P10.31' '刷新音乐库联动（扫库开始信号 → force 网易云同步）已在包内' $linkOk '' `
        '缺刷新音乐库联动：点「刷新音乐库」不会顺带同步网易云（0.11.48 用户需求）'

    $linkNoTestOk = (-not $gateText.Contains('netease-refresh-e2e')) `
        -and (-not $pluginText.Contains('netease-refresh-e2e'))
    Add-Result 'P10.31b' '正式包不含「刷新音乐库」取证脚手架（临时标记已删）' $linkNoTestOk '' `
        '正式包里残留 netease-refresh-e2e 取证代码：必须删掉临时脚手架再出包'

    # ---------- P10.32 封面绑定修复（0.11.49 用户报障）
    #  用户口径：「播放条和播放列表封面不一致」「有些音乐封面绑定错误」。三处修复：
    #  A 播放条投递认宿主权威当前曲（VoxzenBridge.playingTrackId，PlaybarCover.playingNow/cancelFor）；
    #  B 宿主缓存键陈旧自愈（按图 mtime：CoverPrimer.olderThan 不再跳过、CoverDelivery.staleKey 重写）；
    #  C 同名专辑复合引用键「专辑 U+0001 歌手」（CoverStore.refKey/migrateRefs、NativeLibrary 按 title+artist）。
    #  注释不进字节码，只认真实常量 / 方法名 / SQL 字符串。
    $pbBytes = Get-EntryBytes $zip 'classes/com/example/netease/svc/PlaybarCover.class'
    $pbText = if ($null -ne $pbBytes) { [System.Text.Encoding]::UTF8.GetString($pbBytes) } else { '' }
    $csBytes = Get-EntryBytes $zip 'classes/com/example/netease/svc/CoverStore.class'
    $csText = if ($null -ne $csBytes) { [System.Text.Encoding]::UTF8.GetString($csBytes) } else { '' }
    $cdBytes = Get-EntryBytes $zip 'classes/com/example/netease/svc/CoverDelivery.class'
    $cdText = if ($null -ne $cdBytes) { [System.Text.Encoding]::UTF8.GetString($cdBytes) } else { '' }
    $prBytes = Get-EntryBytes $zip 'classes/com/example/netease/svc/CoverPrimer.class'
    $prText = if ($null -ne $prBytes) { [System.Text.Encoding]::UTF8.GetString($prBytes) } else { '' }
    $vbBytes = Get-EntryBytes $zip 'classes/com/example/netease/host/VoxzenBridge.class'
    $vbText = if ($null -ne $vbBytes) { [System.Text.Encoding]::UTF8.GetString($vbBytes) } else { '' }
    $nlBytes = Get-EntryBytes $zip 'classes/com/example/netease/svc/NativeLibrary.class'
    $nlText = if ($null -ne $nlBytes) { [System.Text.Encoding]::UTF8.GetString($nlBytes) } else { '' }
    $coverFixOk = $pbText.Contains('playingTrackId') `
        -and $pbText.Contains('playingNow') `
        -and $pbText.Contains('cancelFor') `
        -and $pbText.Contains('纠正：当前播放曲已切换') `
        -and $pbText.Contains('权威滞后') `
        -and $vbText.Contains('playingTrackId') `
        -and $csText.Contains('migrateRefs') `
        -and $csText.Contains('imageTime') `
        -and $csText.Contains([string][char]0x0001) `
        -and $cdText.Contains('staleKey') `
        -and $prText.Contains('olderThan') `
        -and $nlText.Contains('WHERE title = ? AND artist = ?')
    Add-Result 'P10.32' '封面绑定修复（权威当前曲 + 陈旧键自愈 + 同名专辑复合键）已在包内' $coverFixOk '' `
        '缺封面绑定修复：播放条会投上一首的图 / 宿主缓存键陈旧串图 / 同名专辑共用一张图（0.11.49 用户报障）'

    # ---------- P10.33 无词落盘（0.11.50 用户报障：「待处理 1250」口径）
    #  用户口径：预热把 1598 首非磁盘曲全试过、网易云全回无词，但负缓存上限 500、淘汰后 1250 条
    #  被 stats() 重算成「待处理」，且每次重启重查网络。实现 = <id>.none 标记落盘（绝不写空 .lrc）
    #  + 回读进 NEGATIVE_ID + 淘汰跳过已落盘条目 + fetch 短路 + LyricWarmer 等回读闸并按已知无词过滤。
    $lyBytes = Get-EntryBytes $zip 'classes/com/example/netease/svc/LyricService.class'
    $lyText = if ($null -ne $lyBytes) { [System.Text.Encoding]::UTF8.GetString($lyBytes) } else { '' }
    $lwBytes = Get-EntryBytes $zip 'classes/com/example/netease/svc/LyricWarmer.class'
    $lwText = if ($null -ne $lwBytes) { [System.Text.Encoding]::UTF8.GetString($lwBytes) } else { '' }
    $negDiskOk = $lyText.Contains('no_lyric') `
        -and $lyText.Contains('.none') `
        -and $lyText.Contains('markNegativeDisk') `
        -and $lyText.Contains('trimNegativeIds') `
        -and $lyText.Contains('knownNoLyric') `
        -and $lyText.Contains('awaitDiskScan') `
        -and $lwText.Contains('knownNoLyric') `
        -and $lwText.Contains('无待办') `
        -and $lwText.Contains('已知无词')
    Add-Result 'P10.33' '无词落盘（.none 标记 + 回读 + 淘汰豁免 + 预热过滤）已在包内' $negDiskOk '' `
        '缺无词落盘实现：负缓存淘汰后「待处理」会虚高（真机 1250），且每次重启把无词曲联网重查一遍'

    # ---------- P10.34 占位桩自愈（0.11.51 用户报障：「有封面的歌显示的是无封面占位图」）
    #  真机 2026-10-04：两张专辑 01:07 一阵网络抖动后落成占位桩（i=placeholder-*.png）且 URL 是好的，
    #  而「已有可用桩 → 直接返回」让占位成了永久终态。实现 = 桩名反查 idx + 索引 i 前缀判定
    #  （refetchableStub/placeholderStub）：ensure 不再把「有图 URL 的占位」当已就绪；pending 重新入池；
    #  ensurePlaying 播放中也可即时补；build 重取失败不投空占位、按 STUCK_RETRY_MS 节流。
    $cs2Bytes = Get-EntryBytes $zip 'classes/com/example/netease/svc/CoverStore.class'
    $cs2Text = if ($null -ne $cs2Bytes) { [System.Text.Encoding]::UTF8.GetString($cs2Bytes) } else { '' }
    $phFixOk = $cs2Text.Contains('refetchableStub') `
        -and $cs2Text.Contains('placeholderStub') `
        -and $cs2Text.Contains('placeholder-')
    Add-Result 'P10.34' '占位桩自愈（有真图 URL 的占位不再算已就绪，补齐/播放路径会重取替换）已在包内' $phFixOk '' `
        '缺占位桩自愈：一次网络抖动落成的占位图会永久顶替真封面（2026-10-04 用户报障）'

    $p3Bytes = Get-EntryBytes $zip 'classes/preference_config.json'
    $p3 = $null
    $p3Err = 'classes/preference_config.json 缺失'
    if ($null -ne $p3Bytes) {
        $p3Text = Convert-BytesToText $p3Bytes
        if ($p3Text.Length -gt 0 -and $p3Text[0] -eq [char]0xFEFF) { $p3Text = $p3Text.Substring(1) }
        try { $p3 = $p3Text | ConvertFrom-Json; $p3Err = '' } catch { $p3Err = $_.Exception.Message }
    }

    # 0.11.30 起：开发者档是一份**随包资源**，不是生效中的 schema（0.11.30 为「用户档 + 13 行」，
    # 0.11.35 删「账号状态」后为「用户档 + 12 行」；0.11.45 用户需求：「启用插件」只留开发者档 ⇒ 回到 +13；
    # 0.11.46：「启动时自动登录」搬进开发者档维护组 ⇒ +14）——
    # 生效中的始终是 classes/preference_config.json（用户档）；开关打开时由 core.DevMode 把
    # 开发者档原样覆盖过去。两份都解析出来：用户档查「用户可见形态」，开发者档查「开发者行齐全」。
    $p3dBytes = Get-EntryBytes $zip 'classes/preference_config.dev.json'
    $p3d = $null
    $p3dErr = 'classes/preference_config.dev.json 缺失'
    if ($null -ne $p3dBytes) {
        $p3dText = Convert-BytesToText $p3dBytes
        if ($p3dText.Length -gt 0 -and $p3dText[0] -eq [char]0xFEFF) { $p3dText = $p3dText.Substring(1) }
        try { $p3d = $p3dText | ConvertFrom-Json; $p3dErr = '' } catch { $p3dErr = $_.Exception.Message }
    }
    $p3dGroups = @()
    if ($null -ne $p3d) { $p3dGroups = @($p3d.configs) }

    if ($null -eq $p3) {
        Add-Result 'P10.0' 'P3 增项可校验（preference_config.json 存在且是合法 JSON）' $false '' $p3Err
    } else {
        $p3Groups   = @($p3.configs)
        $p3CfgNames = @($p3Groups | ForEach-Object { [string](Get-P3Prop $_ 'config') })

        # ---------- P10.1 配置分组齐全（与 $ExpectedConfigs 单一来源对齐；0.11.7 起=config/account_cfg/online/cache_cfg；0.11.10 起去掉临时探针组 probe.json）
        $missingCfg = @($ExpectedConfigs | Where-Object { $p3CfgNames -notcontains $_ })
        $extraCfg   = @($p3CfgNames | Where-Object { $ExpectedConfigs -notcontains $_ })
        $cfgOffend  = @(
            @($missingCfg | ForEach-Object { "缺 $_" })
            @($extraCfg   | ForEach-Object { "契约外多余 $_" })
        ) -join ' '
        Add-Result 'P10.1' "配置分组齐全（$($ExpectedConfigs.Count) 组，文件名与 docs/00 §7 一致）" `
            (($missingCfg.Count -eq 0) -and ($extraCfg.Count -eq 0)) `
            ("实际 " + ($p3CfgNames -join ', ')) $cfgOffend

        # ---------- P10.2 账号组 config=account_cfg.json（0.11.7 去客户端化：client_cfg.json 已删）；
        #            不得与 CookieVault 凭据 account.json 撞名（撞名会覆盖凭据密文）
        $acctGroup = Get-P3Group $p3 'account_cfg.json'
        $cfgClash  = @($p3CfgNames | Where-Object { $_ -ceq 'account.json' })
        $cfgLegacy = @($p3CfgNames | Where-Object { $_ -ceq 'client_cfg.json' })
        $p102Prob  = New-Object System.Collections.Generic.List[string]
        if ($null -eq $acctGroup) { $p102Prob.Add('找不到 config=account_cfg.json 的分组') | Out-Null }
        if ($cfgClash.Count)      { $p102Prob.Add('出现 config=account.json —— 会覆盖 svc.CookieVault 写的凭据密文 account.json') | Out-Null }
        if ($cfgLegacy.Count)     { $p102Prob.Add('client_cfg.json 分组仍在 —— 0.11.7 红线要求删掉本机客户端接入整组') | Out-Null }
        $p102Detail = if ($null -ne $acctGroup) { '账号组存在：config=account_cfg.json' } else { '账号组缺失' }
        Add-Result 'P10.2' '账号组 config=account_cfg.json；无 config=account.json（不与凭据密文撞名）；无遗留 client_cfg.json 组' `
            (($null -ne $acctGroup) -and ($cfgClash.Count -eq 0) -and ($cfgLegacy.Count -eq 0)) `
            $p102Detail ($p102Prob -join '；')

        # ---------- P10.3 退役不留痕（0.11.9）：手机号 + 密码的控件与接线不得回潮
        #  0.11.9 起该登录方式已按用户决定退役（docs/00 §6.23）；本项是**反向**断言：
        #  全配置树里不许再出现标题含「密码」的控件，也不许出现指向 loginPassword / promptPassword 的 on_click。
        #  （原来位于此 id 的正向断言「登录（手机号 + 密码）存在」已随控件一并删除。）
        $reviveHits = New-Object System.Collections.Generic.List[string]
        foreach ($g in $p3Groups) {
            $gCfg = [string](Get-P3Prop $g 'config')
            foreach ($p in @((Get-P3Prop $g 'preferences'))) {
                $t = [string](Get-P3Prop $p 'title')
                $c = [string](Get-P3Prop $p 'on_click')
                if ($t -like '*密码*')               { $reviveHits.Add("$gCfg/控件「$t」标题含『密码』") | Out-Null }
                if ($c -like '*loginPassword*')      { $reviveHits.Add("$gCfg/控件「$t」on_click=$c") | Out-Null }
                if ($c -like '*promptPassword*')     { $reviveHits.Add("$gCfg/控件「$t」on_click=$c") | Out-Null }
            }
        }
        Add-Result 'P10.3' '退役不留痕：配置树内无「密码」控件、无 loginPassword/promptPassword 接线（0.11.9 起不该有）' `
            ($reviveHits.Count -eq 0) `
            $(if ($reviveHits.Count -eq 0) { "遍历 $($p3Groups.Count) 组全部 preferences：0 命中" } else { "$($reviveHits.Count) 处命中" }) `
            $(if ($reviveHits.Count) { '回潮：' + ($reviveHits -join '；') } else { '' })

        # ---------- P10.19 退役不留痕（0.11.10）：临时探针组的控件与接线不得回潮
        #  0.11.7 的 P-2/P-3/P-4/P-5 探针是**临时验收设施**（docs/00 §7 表行、§8 布局行都写明
        #  「验收通过后本组与这四个静态入口一起删除」）；0.11.10 证据齐备后整组退役。
        #  与 P10.3 同构的**反向**断言：配置树里不许再有 config=probe.json 的组、不许再有
        #  指向 probeP2..P5 的 on_click（正向断言「探针组存在」已随整组删除）。
        $probeHits = New-Object System.Collections.Generic.List[string]
        foreach ($g in $p3Groups) {
            $gCfg = [string](Get-P3Prop $g 'config')
            if ($gCfg -ceq 'probe.json') { $probeHits.Add("$gCfg/整组仍在") | Out-Null }
            foreach ($p in @((Get-P3Prop $g 'preferences'))) {
                $t = [string](Get-P3Prop $p 'title')
                $c = [string](Get-P3Prop $p 'on_click')
                if ($c -match 'probeP[2-5]')        { $probeHits.Add("$gCfg/控件「$t」on_click=$c") | Out-Null }
                if ($t -match '^P-[2-5]\s')         { $probeHits.Add("$gCfg/控件「$t」标题是探针项") | Out-Null }
            }
        }
        Add-Result 'P10.19' '退役不留痕：配置树内无 probe.json 组、无 probeP2..P5 接线、无 P-2..P-5 探针控件（0.11.10 起不该有）' `
            ($probeHits.Count -eq 0) `
            $(if ($probeHits.Count -eq 0) { "遍历 $($p3Groups.Count) 组全部 preferences：0 命中" } else { "$($probeHits.Count) 处命中" }) `
            $(if ($probeHits.Count) { '回潮：' + ($probeHits -join '；') } else { '' })

        # ---------- P10.4/6/7/9/13/14/15/16/17/18 按钮接线（静态字符串比对；0.11.7 = 原生登录 + 状态与缓存组）
        $p3Buttons = @(
            # 0.11.23：账号组登录块收成一行 ⇒「获取验证码（smsSend）」「确定登录（smsLoginNow）」
            #  两个按钮整组退役，P10.4 / P10.4b 随之删除；它们的「不许回潮」由 P10.5c / P10.20 反向断言接管。
            # 0.11.26：账号组第一行改回**对话框入口**（button「登录」→ openLoginDialog），接线断言在 P10.5b。
            # 0.11.30：Scope='user' ⇒ 用户档里必须有的行；Scope='dev' ⇒ 只在开发者档里
            #  有的行（用户档必须**没有**它们，反向断言在 P10.22）。接线本身与档位无关，
            #  两边都必须指向同一个 NeteasePlugin 入口。
            [pscustomobject]@{ Id = 'P10.6';  Cfg = 'account_cfg.json'; Title = '退出登录';                    Target = 'com.example.netease.NeteasePlugin.logout';               Scope = 'user' }
            # 0.11.35（用户 m00001 问题1）：「账号状态」按钮**已删除**（职责并进账号行按钮「当前账号」，
            #   那是普通用户入口、不设开发者闸）⇒ P10.7 从账号状态改判**当前账号**这一行，Scope 从 dev 改 user。
            [pscustomobject]@{ Id = 'P10.7';  Cfg = 'account_cfg.json'; Title = '当前账号';                    Target = 'com.example.netease.NeteasePlugin.currentAccount';      Scope = 'user' }
            [pscustomobject]@{ Id = 'P10.9';  Cfg = 'online.json';      Title = '在线模式自检';                Target = 'com.example.netease.NeteasePlugin.onlineSelfCheck';      Scope = 'dev' }
            [pscustomobject]@{ Id = 'P10.13'; Cfg = 'cache_cfg.json';   Title = '三条管线总览';                Target = 'com.example.netease.NeteasePlugin.pipelineStatus';       Scope = 'dev' }
            [pscustomobject]@{ Id = 'P10.14'; Cfg = 'cache_cfg.json';   Title = '封面完成度';                  Target = 'com.example.netease.NeteasePlugin.coverStats';           Scope = 'dev' }
            [pscustomobject]@{ Id = 'P10.15'; Cfg = 'cache_cfg.json';   Title = '歌词完成度';                  Target = 'com.example.netease.NeteasePlugin.lyricStats';           Scope = 'dev' }
            [pscustomobject]@{ Id = 'P10.16'; Cfg = 'cache_cfg.json';   Title = '清理音乐封面缓存';            Target = 'com.example.netease.NeteasePlugin.clearCoverCache';      Scope = 'user' }
            [pscustomobject]@{ Id = 'P10.17'; Cfg = 'cache_cfg.json';   Title = '清理歌词缓存';                Target = 'com.example.netease.NeteasePlugin.clearLyricCache';      Scope = 'user' }
            [pscustomobject]@{ Id = 'P10.18'; Cfg = 'config.json';      Title = '音质档位状态';                Target = 'com.example.netease.NeteasePlugin.audioLevelStatus';     Scope = 'dev' }
        )
        foreach ($b in $p3Buttons) {
            $doc  = if ($b.Scope -eq 'dev') { $p3d } else { $p3 }
            $g    = if ($null -eq $doc) { $null } else { Get-P3Group $doc $b.Cfg }
            $pref = Get-P3Pref $g $b.Title
            $ty   = [string](Get-P3Prop $pref 'type')
            $t    = [string](Get-P3Prop $pref 'on_click')
            $ok   = ($null -ne $pref) -and ($ty -ceq 'button') -and ($t -ceq $b.Target)
            $where = if ($b.Scope -eq 'dev') { '开发者档' } else { '用户档' }
            $detail = if ($null -eq $doc) { "$where 缺失（$p3dErr）" }
                      elseif ($null -eq $pref) { "$where 控件缺失" }
                      else { "$where type=$ty on_click=$t" }
            $offend = if ($ok) { '' } else { "$where 控件「$($b.Title)」接线不符：期望 on_click=$($b.Target)，实际 $(if ($t) { $t } else { '(空)' })/type=$(if ($ty) { $ty } else { '(空)' })" }
            Add-Result $b.Id "$($b.Cfg) 的「$($b.Title)」是 button 且 on_click=$($b.Target)（$where）" $ok $detail $offend
        }

        # ---------- P10.5 auto_login（0.11.46 用户需求：从账号组搬进「维护」组，且只在开发者档显示）
        #  形态：switch + key=auto_login + JSON 布尔默认值；用户档不许再有这一行（普通用户看不到）。
        $alUser    = Get-P3Pref $acctGroup '启动时自动登录'
        $devMaint  = Get-P3Group $p3d 'config.json'
        $al        = Get-P3Pref $devMaint '启动时自动登录'
        $alTy      = [string](Get-P3Prop $al 'type')
        $alKey     = [string](Get-P3Prop $al 'key')
        $alDef     = Get-P3Prop $al 'default_value'
        $alOk      = ($null -eq $alUser) -and ($null -ne $al) -and ($alTy -ceq 'switch') -and ($alKey -ceq 'auto_login') -and ($alDef -is [bool])
        $alDetail  = "用户档账号组里=$(if ($null -eq $alUser) { '已摘除（正确）' } else { '仍有一行（错误）' })；开发者档维护组=$(if ($null -eq $al) { '缺失' } else { "type=$alTy key=$alKey default_value=$alDef（$(if ($null -ne $alDef) { $alDef.GetType().Name } else { 'n/a' })）" })"
        Add-Result 'P10.5' 'auto_login 在开发者档「维护」组（type=switch、key=auto_login、default_value 为 JSON 布尔），用户档不再显示「启动时自动登录」' $alOk $alDetail `
            $(if ($alOk) { '' } else { '控件「启动时自动登录」没有搬进开发者档维护组 / 不是 switch/auto_login/布尔默认值 / 用户档仍看得见它' })

        # ---------- P10.5b 账号组「登录」按钮（0.11.26 用户规格：点开弹出登录对话框）
        #  形态沿革：0.11.22 四件套（登录行 + 获取验证码 / 验证码 / 确定登录）→ 0.11.23 收成一行 edittext
        #  （按值的形态分步）→ 0.11.26 回到用户明确要的「对话框」：本行改成 button，点开是
        #  ui/LoginDialog（手机号框 + 验证码框 + 右侧「获取验证码」+ 底部「取消」「登录」）。
        #  为什么必须自绘：宿主 edittext 行点开只有**一个**输入框（宿主自绘模态），多控件表单宿主给不了。
        $ph    = Get-P3Pref $acctGroup '登录'
        $phTy  = [string](Get-P3Prop $ph 'type')
        $phClick = [string](Get-P3Prop $ph 'on_click')
        $phOk  = ($null -ne $ph) -and ($phTy -ceq 'button') -and ($phClick -ceq 'com.example.netease.NeteasePlugin.openLoginDialog')
        $phDetail = if ($null -eq $ph) { '控件缺失' } else { "type=$phTy on_click=$phClick" }
        Add-Result 'P10.5b' '账号组含「登录」按钮（type=button、on_click=…NeteasePlugin.openLoginDialog = 登录对话框入口）' $phOk $phDetail `
            $(if ($phOk) { '' } else { '控件「登录」不是 button/openLoginDialog —— 宿主渲染不出按钮，登录对话框打不开' })

        # ---------- P10.5c 账号组形态（**0.11.30 收口：6 行 → 4 行**）：四行 =「当前账号」展示行 +
        #  「登录」按钮 + 开关 + 退出登录。
        #  0.11.30 用户需求②：扫码与验证码**并在同一个对话框**里（卡片内分段切换，默认扫码）⇒
        #  0.11.29 那行「扫码登录」按钮撤掉，独立扫码窗 ui/LoginWindow 整类退役（见 P10.25）。
        #  0.11.30 用户需求③：「账号状态」搬进开发者档 ⇒ 用户档里不许再有它（见 P10.22）。
        #  反向：四件套残留（获取验证码 / 验证码 / 确定登录）与旧「手机号」行都不许在；
        #  正向（**0.11.35 起，用户 m00001 问题1**）：「当前账号」是 button「当前账号」→ currentAccount，
        #  账号信息**不得常驻**在页面上 —— 标题丢掉 0.11.32 的 `{{ACCOUNT}}` 占位机制，点开才由
        #  ui/AccountStatusWindow 展示「账号名字 / 账号 ID / 会员信息」。
        $legacyRows = @()
        foreach ($t in @('获取验证码', '验证码', '确定登录', '手机号')) {
            if ($null -ne (Get-P3Pref $acctGroup $t)) { $legacyRows += $t }
        }
        #  0.11.35：账号行按 **title** 找（不再是「key + {{ACCOUNT}} 占位」那一套），并同时断言三件事：
        #  ①type=button ②title 恰好「当前账号」③on_click=…currentAccount；外加反向断言：页面上不许出现
        #  `{{` 这种动态占位、也不许出现「当前账号：」开头的常驻登录态文本（用户原话「不要常驻显示」）。
        $curRow = $null
        foreach ($p in @((Get-P3Prop $acctGroup 'preferences'))) {
            if ([string](Get-P3Prop $p 'title') -ceq '当前账号') { $curRow = $p }
        }
        $curTy   = [string](Get-P3Prop $curRow 'type')
        $curTitle = [string](Get-P3Prop $curRow 'title')
        $curClick = [string](Get-P3Prop $curRow 'on_click')
        $curKey   = [string](Get-P3Prop $curRow 'key')
        $curOk   = ($null -ne $curRow) -and ($curTy -ceq 'button') -and ($curClick -ceq 'com.example.netease.NeteasePlugin.currentAccount')
        $curInline = @()
        foreach ($p in @((Get-P3Prop $acctGroup 'preferences'))) {
            $tt = [string](Get-P3Prop $p 'title')
            if ($tt.Contains('{{'))                { $curInline += "「$tt」仍带占位" }
            if ($tt.Contains('当前账号：')) {
                $curInline += "「$tt」把登录态写进了标题（用户要求点击后才显示）"
            }
        }
        $noLoginRow = ($null -eq (Get-P3Pref $acctGroup '登录输入')) -and ($null -eq (Get-P3Prop $acctGroup 'login_input'))
        #  0.11.30 起扫码不再是配置页的一行：它是登录对话框里的一档（分段「扫码 | 验证码」），
        #  所以这里断言那行**不许回潮**（回潮 = 用户又看到两个入口，需求② 判 FAIL）。
        $qrBack   = Get-P3Pref $acctGroup '扫码登录'
        $acctRows = @((Get-P3Prop $acctGroup 'preferences')).Count
        $devRowInUser = @()
        foreach ($t in @('账号状态', '三条管线总览', '封面完成度', '歌词完成度', '音质档位状态', '打开数据目录',
                         '重建曲库映射', '测试网络连通性', '在线模式自检', '试播一首', '试播本地文件', '装拦截器', '日志级别',
                         '启动时自动登录')) {
            if ($null -ne (Get-P3Pref $acctGroup $t)) { $devRowInUser += $t }
        }
        $smsOk = ($legacyRows.Count -eq 0) -and $curOk -and ($curInline.Count -eq 0) -and ($null -eq $qrBack) -and ($acctRows -eq 3) -and ($devRowInUser.Count -eq 0)
        $smsDetail = "账号组行数=$acctRows；「当前账号」=type=$curTy title=$curTitle key=$curKey on_click=$curClick（常驻账号文本=$(if ($curInline.Count) { $curInline -join '；' } else { '无（正确）' })）；「扫码登录」行=$(if ($null -eq $qrBack) { '已撤（正确）' } else { '回潮了' })；账号组里混进的开发者行=$(if ($devRowInUser.Count) { $devRowInUser -join '、' } else { '无' })；仍存在的旧控件=$(if ($legacyRows.Count) { $legacyRows -join '、' } else { '无' })"
        Add-Result 'P10.5c' '账号组恰好 3 行且形态正确：「当前账号」是 button（title 恰好「当前账号」、on_click=…currentAccount，点开才展示账号名字/id/会员）+ 登录按钮 + 退出登录（0.11.46 起「启动时自动登录」搬进开发者档维护组）；页面上不常驻任何账号信息（无 {{ 占位、无「当前账号：」）；「扫码登录」行不许回潮（0.11.30 起扫码在对话框内）；无「获取验证码/验证码/确定登录/手机号」、无任何开发者行' $smsOk $smsDetail `
            $(if ($smsOk) { '' } else { '账号组形态不符：缺「当前账号」按钮（或它不是 button/currentAccount ⇒ 点不出账号信息）、或账号信息仍常驻在标题里（用户 m00001 要求点击后才显示）、或「扫码登录」行回潮（0.11.30 起它在对话框里）、或开发者行混进用户档、或四件套/手机号行回潮、或行数不是 3' })

        # ---------- P10.21 开发者档资源（0.11.30 需求③）：存在、合法、4 组、行数 = 用户档 + 14、
        #  11 个开发者入口接线齐全 + 「日志级别」list 行与「启用插件」switch 行在
        #  （= 14 行开发者行：在线播放组 4 + 状态与缓存组 3 + 维护组 6（含 0.11.46 搬进来的
        #  「启动时自动登录」）+ 「启用插件」；
        #  其中 list / switch 各 1 行无 on_click）。0.11.45 用户需求：缓存两行搬进「状态与缓存」、
        #  用户档不再显示「启用插件」（只留开发者档）⇒ 净增量仍是 13。
        #  0.11.46 用户需求：「合并歌单」两档都加（用户档 +1，抵消「启动时自动登录」搬走 -1），
        #  「启动时自动登录」搬进开发者档维护组 ⇒ 增量 13 → 14。
        #  用户裁定（m00568）的收纳清单（**0.11.35 起减为 11 条**）= 在线播放（实验）4 行 + 三条管线总览 +
        #  封面完成度 + 歌词完成度 + 日志级别 + 音质档位状态 + 打开数据目录 + 重建曲库映射 + 测试网络连通性；
        #  「账号状态」已按用户 m00001 问题1 删除。
        $DEV_TARGETS = @(
            'com.example.netease.NeteasePlugin.onlineSelfCheck',
            'com.example.netease.NeteasePlugin.onlineTryOne',
            'com.example.netease.NeteasePlugin.onlineTryLocal',
            'com.example.netease.NeteasePlugin.onlineInjectInterceptor',
            'com.example.netease.NeteasePlugin.pipelineStatus',
            'com.example.netease.NeteasePlugin.coverStats',
            'com.example.netease.NeteasePlugin.lyricStats',
            'com.example.netease.NeteasePlugin.audioLevelStatus',
            'com.example.netease.NeteasePlugin.openDataDir',
            'com.example.netease.NeteasePlugin.rebuildMapping',
            'com.example.netease.NeteasePlugin.testConnection'
        )
        $devAll = @()
        foreach ($g in $p3dGroups) {
            foreach ($p in @((Get-P3Prop $g 'preferences'))) {
                $c = [string](Get-P3Prop $p 'on_click')
                if (-not [string]::IsNullOrWhiteSpace($c)) { $devAll += $c }
            }
        }
        $devMissing = @($DEV_TARGETS | Where-Object { $devAll -notcontains $_ })
        $devLogLv   = $null
        foreach ($g in $p3dGroups) { $hit = Get-P3Pref $g '日志级别'; if ($null -ne $hit) { $devLogLv = $hit } }
        $devEnabled = $null
        foreach ($g in $p3dGroups) { $hit = Get-P3Pref $g '启用插件'; if ($null -ne $hit) { $devEnabled = $hit } }
        $devEnTy  = [string](Get-P3Prop $devEnabled 'type')
        $devEnKey = [string](Get-P3Prop $devEnabled 'key')
        $devEnDef = Get-P3Prop $devEnabled 'default_value'
        $devEnabledOk = ($null -ne $devEnabled) -and ($devEnTy -ceq 'switch') -and ($devEnKey -ceq 'enabled') -and ($devEnDef -eq $true)
        $devRows    = 0
        foreach ($g in $p3dGroups) { $devRows += @((Get-P3Prop $g 'preferences')).Count }
        # 行数用**相对判据**（开发者档 = 用户档 + 14 行）而不是写死数字：需求④ 以后要再精简用户档文案 /
        # 增删用户可见行时，这里不该跟着改；写死 25 会在那种改动里变成假 FAIL（本轮就栽在写死 23 上）。
        # 0.11.35：账号状态删除 ⇒ 13 → 12；0.11.45：「启用插件」只留开发者档 ⇒ 12 → 13；
        # 0.11.46：「启动时自动登录」搬进开发者档维护组 ⇒ 13 → 14。
        $userRows   = 0
        foreach ($g in $p3Groups) { $userRows += @((Get-P3Prop $g 'preferences')).Count }
        $devOk = ($null -ne $p3d) -and ($p3dGroups.Count -eq 4) -and ($devRows -eq ($userRows + 14)) `
            -and ($devMissing.Count -eq 0) -and ($null -ne $devLogLv) -and $devEnabledOk
        $devHead = if ($null -ne $p3d) { "组数=$($p3dGroups.Count) 行数=$devRows（用户档 $userRows + 14）" } else { $p3dErr }
        $devDetail = "开发者档：$devHead；11 个开发者入口缺=$(if ($devMissing.Count) { ($devMissing -join '、') } else { '无' })；日志级别行=$(if ($null -ne $devLogLv) { '在' } else { '缺' })；启用插件行=$(if ($null -ne $devEnabled) { "type=$devEnTy key=$devEnKey default_value=$(Format-P3Val $devEnDef)" } else { '缺' })"
        Add-Result 'P10.21' '开发者档 classes/preference_config.dev.json 存在且齐全：4 组、行数 = 用户档行数 + 14、11 个开发者入口接线齐全 + 「日志级别」list 行与「启用插件」switch 行在' $devOk $devDetail `
            $(if ($devOk) { '' } else { '开发者档缺失或不齐：开关打开后用户看不到开发者行（需求③ 直接判 FAIL）；行数不符 = 开发者档不是「用户档 + 14 行」；缺「启用插件」= 用户档摘掉它后没有别处能关插件' })

        # ---------- P10.22 反向：开发者行不许留在用户档里（需求③「正常状态为关闭，普通用户不受干扰」；
        #  0.11.45 起「启用插件」也只留开发者档 —— 用户档里出现它即判漏）
        $userLeak  = New-Object System.Collections.Generic.List[string]
        $userTitles = @()
        foreach ($g in $p3Groups) { foreach ($p in @((Get-P3Prop $g 'preferences'))) { $userTitles += [string](Get-P3Prop $p 'title') } }
        foreach ($t in @('账号状态', '在线模式自检', '试播一首', '试播本地文件', '装拦截器', '三条管线总览',
                         '封面完成度', '歌词完成度', '日志级别', '音质档位状态', '打开数据目录', '重建曲库映射', '测试网络连通性',
                         '启用插件', '启动时自动登录')) {
            if ($userTitles -contains $t) { $userLeak.Add($t) | Out-Null }
        }
        $devSwitch = Get-P3Pref (Get-P3Group $p3 'config.json') '开发者模式'
        $devSwTy   = [string](Get-P3Prop $devSwitch 'type')
        $devSwKey  = [string](Get-P3Prop $devSwitch 'key')
        $devSwDef  = Get-P3Prop $devSwitch 'default_value'
        $devSwOk   = ($null -ne $devSwitch) -and ($devSwTy -ceq 'switch') -and ($devSwKey -ceq 'dev_mode') -and ($devSwDef -eq $false)
        $devOutOk  = ($userLeak.Count -eq 0) -and $devSwOk
        $devOutDetail = "用户档共 $($userTitles.Count) 行；混进来的开发者行=$(if ($userLeak.Count) { $userLeak -join '、' } else { '无' })；「开发者模式」=type=$devSwTy key=$devSwKey default_value=$(Format-P3Val $devSwDef)"
        Add-Result 'P10.22' '用户档里没有任何开发者行，且「维护」组含「开发者模式」开关（type=switch、key=dev_mode、default_value=false）' $devOutOk $devOutDetail `
            $(if ($devOutOk) { '' } else { '开发者行漏进用户档（普通用户会看到开发者功能），或缺 dev_mode 开关 / 它不是默认关闭的 switch' })

        # ---------- P10.23 文案精简（需求④）：两档所有行文案 ≤ 21 字，且用户档行序 = 开发者档的同名子集
        #  0.11.46：用户逐字给出的「合并歌单」summary 为 31 字（原话指定，不许改写）⇒ 该行走白名单
        #  豁免；其余行仍受 21 字约束，避免「豁免」变成整体放宽。
        $SUMMARY_ALLOW = @{ '合并歌单' = 31 }
        $longText = New-Object System.Collections.Generic.List[string]
        $maxLen   = 0
        foreach ($pair in @(@('用户档', $p3Groups), @('开发者档', $p3dGroups))) {
            $label = $pair[0]
            foreach ($g in $pair[1]) {
                foreach ($p in @((Get-P3Prop $g 'preferences'))) {
                    $s  = [string](Get-P3Prop $p 'summary')
                    $tt = [string](Get-P3Prop $p 'title')
                    $limit = 21
                    if ($SUMMARY_ALLOW.ContainsKey($tt)) { $limit = [int]$SUMMARY_ALLOW[$tt] }
                    if ($s.Length -gt $limit) { $longText.Add("$label/$tt（$($s.Length) 字，上限 $limit）") | Out-Null }
                    if ($s.Length -gt $maxLen) { $maxLen = $s.Length }
                }
            }
        }
        $devTitlesAll = @()
        foreach ($g in $p3dGroups) { foreach ($p in @((Get-P3Prop $g 'preferences'))) { $devTitlesAll += [string](Get-P3Prop $p 'title') } }
        $subOk = ($null -ne $p3d)
        if ($subOk) {
            foreach ($t in $userTitles) { if ($devTitlesAll -notcontains $t) { $subOk = $false } }
        }
        $textOk = ($longText.Count -eq 0) -and $subOk
        Add-Result 'P10.23' '文案精简：用户档与开发者档每一行 summary ≤ 21 字（「合并歌单」按用户原话豁免 31 字），且用户档的每一行在开发者档里都有同名的行（开发者档 = 用户档 + 14 行）' $textOk `
            "最长 summary=$maxLen 字；超限=$(if ($longText.Count) { $longText -join '、' } else { '无' })；豁免=$(if ($SUMMARY_ALLOW.Count) { ($SUMMARY_ALLOW.Keys -join '、') } else { '无' })；开发者档包含用户档全部行=$(if ($subOk) { '是' } else { '否' })" `
            $(if ($textOk) { '' } else { '有 summary 超过上限（需求④ 判 FAIL，白名单除外），或开发者档漏了用户可见行（开开关会让普通功能消失）' })

        # ---------- P10.26 合并歌单开关（0.11.46 用户需求）：两档「维护」组**都**要有
        #  type=switch、key=merge_playlists、default_value=true 的行，且都紧挨在「开发者模式」上面
        #  （用户口径：放在维护的开发者模式上面；默认开启）。功能落地由 Java 侧闸门负责（本轮不在这里查）。
        $mergeProb = New-Object System.Collections.Generic.List[string]
        foreach ($pair in @(@('用户档', $p3), @('开发者档', $p3d))) {
            $label = $pair[0]
            $root  = $pair[1]
            if ($null -eq $root) { $mergeProb.Add("$label 缺失") | Out-Null; continue }
            $grp = Get-P3Group $root 'config.json'
            if ($null -eq $grp) { $mergeProb.Add("$label 找不到 config=config.json 的「维护」组") | Out-Null; continue }
            $prefs = @((Get-P3Prop $grp 'preferences'))
            $mergeIdx = -1; $devIdx = -1; $mergePref = $null
            for ($i = 0; $i -lt $prefs.Count; $i++) {
                $tt = [string](Get-P3Prop $prefs[$i] 'title')
                if ($tt -ceq '合并歌单') { $mergeIdx = $i; $mergePref = $prefs[$i] }
                if ($tt -ceq '开发者模式') { $devIdx = $i }
            }
            if ($mergeIdx -lt 0) { $mergeProb.Add("$label 缺「合并歌单」行") | Out-Null; continue }
            $mty = [string](Get-P3Prop $mergePref 'type')
            $mky = [string](Get-P3Prop $mergePref 'key')
            $mdf = Get-P3Prop $mergePref 'default_value'
            if ($mty -cne 'switch' -or $mky -cne 'merge_playlists' -or $mdf -ne $true) {
                $mergeProb.Add("$label「合并歌单」形态不对：type=$mty key=$mky default_value=$(Format-P3Val $mdf)") | Out-Null
            }
            if ($devIdx -lt 0 -or $mergeIdx -ge $devIdx) {
                $mergeProb.Add("$label「合并歌单」不在「开发者模式」上面（合并行=$mergeIdx 开发者行=$devIdx）") | Out-Null
            }
        }
        $mergeOk = ($mergeProb.Count -eq 0)
        Add-Result 'P10.26' '「合并歌单」开关（0.11.46）：两档「维护」组都有 type=switch、key=merge_playlists、default_value=true 的行，且都紧挨在「开发者模式」上面' $mergeOk `
            "问题=$(if ($mergeOk) { '无' } else { $mergeProb -join '；' })" `
            $(if ($mergeOk) { '' } else { '合并歌单开关缺失 / 形态不对 / 位置不对 —— 用户要求：放维护的开发者模式上面、默认开启' })

        # ---------- P10.24 用户档主本（0.11.35 真机根因：DevMode 自读 ⇒ 「关」方向从不写盘）
        #  真机上插件类加载器的 classpath 根**就是**装机 classes\ ⇒ 若 DevMode.RES_USER 指回
        #  /preference_config.json，它解析到的就是被改写目标自己：apply() 里 sameContent(源, 目标)
        #  恒真 ⇒ 「关」方向永远提前 return（还打「结果=成功」），装机文件永远停在开发者档。
        #  修复 = 随包多带一份用户档主本 classes/preference_config.user.json（DevMode.RES_USER 指它；
        #  离线探针 tools\smoke\DevModeProbe.java 的 〇/三·二/五·五 节复现装机布局并配阴性对照）。
        #  这里断言：包内它必须存在，且与 classes/preference_config.json **逐字节相同**（同一份用户档；
        #  不一致=两份用户档漂移：关开关写回去的那份与宿主首次渲染用的不是同一份）。
        $umBytes = Get-EntryBytes $zip 'classes/preference_config.user.json'
        $umSame  = $false
        $umDiff  = -1
        if (($null -ne $umBytes) -and ($null -ne $p3Bytes) -and ($umBytes.Length -eq $p3Bytes.Length)) {
            $umSame = $true
            for ($i = 0; $i -lt $umBytes.Length; $i++) {
                if ($umBytes[$i] -ne $p3Bytes[$i]) { $umSame = $false; $umDiff = $i; break }
            }
        }
        $umOk = ($null -ne $umBytes) -and $umSame
        $umDetail = if ($null -eq $umBytes) {
            '包内没有 classes/preference_config.user.json'
        } elseif ($umSame) {
            "$($umBytes.Length) 字节；与用户档逐字节相同（用户档 $($p3Bytes.Length) 字节）"
        } elseif ($umDiff -ge 0) {
            "$($umBytes.Length) 字节；与用户档（$($p3Bytes.Length) 字节）不同：首个不同字节偏移 $umDiff"
        } else {
            "$($umBytes.Length) 字节；长度就与用户档（$($p3Bytes.Length) 字节）不同"
        }
        Add-Result 'P10.24' '用户档主本 classes/preference_config.user.json 存在，且与 classes/preference_config.json 逐字节相同（0.11.35：DevMode 自读缺陷的修复资源）' $umOk $umDetail `
            $(if ($umOk) { '' } else { '缺少用户档主本 / 与用户档不一致 —— DevMode「关」方向会再次空转（装机文件停在开发者档，用户报障会复现）' })

        # ---------- P10.25 退役不留痕（0.11.30 需求②）：独立扫码窗 ui/LoginWindow 整类不得回潮
        #  0.11.30 用户需求②「扫码登录与验证码登录使用完全相同的样式，不允许再弹出独立窗口」⇒
        #  扫码并入 ui/LoginDialog 的分段切换，ui.LoginWindow 整类退役；它的接线路标（showLoginDialog）
        #  仍然保留（登录兜底入口，harness P3Checks 清单按此名找），但类文件不许再出现在包里。
        $lwBack = @($entryNames | Where-Object { $_ -ceq 'classes/com/example/netease/ui/LoginWindow.class' })
        Add-Result 'P10.25' '退役不留痕：包内没有 ui/LoginWindow.class（0.11.30 起扫码在登录对话框内，独立扫码窗退役）' ($lwBack.Count -eq 0) `
            $(if ($lwBack.Count -eq 0) { '未发现' } else { ($lwBack -join ', ') }) `
            $(if ($lwBack.Count) { '回潮：独立扫码窗又在包里了 —— 用户会看到第二个窗口（需求② 判 FAIL）' } else { '' })

        # ---------- P10.20 退役不留痕（0.11.22 + 0.11.23 + 0.11.26）：独立模态登录框与登录四件套都不得回潮
        #  用户规格沿革：① 0.11.22「登录不弹独立窗口（ui/LoginPrompt），输入手机号与登录合并成一行」⇒
        #  loginSms 一行 + LoginPrompt 模态框退役；② 0.11.23「登录不许拆成四个点击部分」⇒ smsSend /
        #  smsLoginNow 两个按钮与「验证码」行退役、单行输入（login_input）；③ 0.11.26「点开是一个框、里面能
        #  分别填手机号和验证码」⇒ 登录改回**自绘对话框**（ui/LoginDialog，见 P10.5b/P10.5c），界面只剩一个
        #  登录按钮 + 一个「当前账号」按钮。这里做与 P10.3 / P10.19 同构的**反向**断言：
        #  退役接线不得回潮、退役标题不得回潮、内部键（phone / sms_code / login_input / current_account）
        #  不得再占用界面控件 —— 0.11.35（用户 m00001 问题1）把 current_account 也降级成**插件内部写盘键**：
        #  账号行已改成 button，页面上不再有承载它的行，账号信息只在点击后由 ui/AccountStatusWindow 展示。
        $smsHits = New-Object System.Collections.Generic.List[string]
        foreach ($g in $p3Groups) {
            $gCfg = [string](Get-P3Prop $g 'config')
            foreach ($p in @((Get-P3Prop $g 'preferences'))) {
                $t = [string](Get-P3Prop $p 'title')
                $c = [string](Get-P3Prop $p 'on_click')
                $k = [string](Get-P3Prop $p 'key')
                if ($c -like '*loginSms*' -or $c -like '*smsSend*' -or $c -like '*smsLoginNow*') {
                    $smsHits.Add("$gCfg/控件「$t」on_click=$c") | Out-Null
                }
                if ($t -ceq '登录（手机号 + 短信验证码）')  { $smsHits.Add("$gCfg/控件「$t」还在（0.11.22 起应叫「登录」）") | Out-Null }
                if ($t -ceq '手机号')                      { $smsHits.Add("$gCfg/控件「$t」还在（0.11.22 起应叫「登录」）") | Out-Null }
                if ($t -ceq '获取验证码' -or $t -ceq '验证码' -or $t -ceq '确定登录') {
                    $smsHits.Add("$gCfg/控件「$t」还在（0.11.26 起这些都在登录对话框里）") | Out-Null
                }
                if ($k -ceq 'phone' -or $k -ceq 'sms_code' -or $k -ceq 'login_input') {
                    # 0.11.35：current_account 也已降级为插件内部写盘键（登录态落点），界面上不许再有承载它的行
                    $smsHits.Add("$gCfg/控件「$t」仍占用内部键 $k（0.11.35 起内部键不得再占用任何界面控件）") | Out-Null
                }
            }
        }
        Add-Result 'P10.20' '退役不留痕：无 loginSms/smsSend/smsLoginNow 接线、无「登录（手机号 + 短信验证码）/手机号/获取验证码/验证码/确定登录」旧控件、界面键无 phone/sms_code/login_input（0.11.26 起不该有）' `
            ($smsHits.Count -eq 0) `
            $(if ($smsHits.Count -eq 0) { "遍历 $($p3Groups.Count) 组全部 preferences：0 命中" } else { "$($smsHits.Count) 处命中" }) `
            $(if ($smsHits.Count) { '回潮：' + ($smsHits -join '；') } else { '' })

        # ---------- P10.8 维护组 audio_level（list + 字符串白名单；0.4.0 起档位随在线播放走；
        #            0.10.2 起含无损以上：sky 高清臻音 / jymaster 超清母带 / jyeffect 沉浸声 / dolby 杜比全景声；
        #            0.11.7 起随客户端组删除迁入 config.json，键名与取值一字不改）
        $dlGroup    = Get-P3Group $p3 'config.json'
        $bm         = Get-P3Pref $dlGroup '在线播放音质'
        $bmTy       = [string](Get-P3Prop $bm 'type')
        $bmKey      = [string](Get-P3Prop $bm 'key')
        $bmVals     = @((Get-P3Prop $bm 'entry_values'))
        $bmDef      = Get-P3Prop $bm 'default_value'
        $bmExpected = @('standard', 'higher', 'exhigh', 'lossless', 'hires', 'sky', 'jymaster', 'jyeffect', 'dolby')
        $bmProblems = New-Object System.Collections.Generic.List[string]
        if ($null -eq $bm) {
            $bmProblems.Add('控件「在线播放音质」缺失') | Out-Null
        } else {
            if ($bmTy -cne 'list')          { $bmProblems.Add("type=$bmTy，期望 list") | Out-Null }
            if ($bmKey -cne 'audio_level')  { $bmProblems.Add("key=$bmKey，期望 audio_level") | Out-Null }
            if ($bmVals.Count -ne $bmExpected.Count) { $bmProblems.Add("entry_values 有 $($bmVals.Count) 项，期望 $($bmExpected.Count) 项") | Out-Null }
            for ($i = 0; $i -lt $bmVals.Count; $i++) {
                $v = $bmVals[$i]
                if ($v -isnot [string]) {
                    $bmProblems.Add("entry_values[$i] 不是 JSON 字符串（实际 $($v.GetType().Name)：$v）—— 宿主只会回传字符串") | Out-Null
                }
            }
            $bmStr = @($bmVals | ForEach-Object { if ($_ -is [string]) { $_ } else { "!$($_.GetType().Name)" } })
            $bmDiff = @(
                @($bmExpected | Where-Object { $bmStr -notcontains $_ })
                @($bmStr | Where-Object { $bmExpected -notcontains $_ })
            )
            if ($bmDiff.Count -gt 0) {
                $bmProblems.Add("entry_values=[$($bmStr -join ', ')]，期望恰好 [$($bmExpected -join ', ')]（字符串）") | Out-Null
            }
            if ($bmDef -isnot [string]) {
                $bmProblems.Add("default_value 不是 JSON 字符串（实际 $(if ($null -eq $bmDef) { 'null' } else { $bmDef.GetType().Name })）") | Out-Null
            } elseif ($bmExpected -notcontains $bmDef) {
                $bmProblems.Add("default_value='$bmDef' 不在白名单 $($bmExpected -join '/')") | Out-Null
            }
        }
        Add-Result 'P10.8' '维护组 config.json 含 audio_level（type=list、entry_values 为字符串档位、默认值在白名单内）' `
            ($bmProblems.Count -eq 0) `
            ("entry_values=[" + ((@($bmVals) | ForEach-Object { [string]$_ }) -join ', ') + "] default_value=$(Format-P3Val $bmDef)") `
            (($bmProblems | Select-Object -First 6) -join ' | ')

        # ---------- P10.11/P10.12 所有 on_click 的目标类必须在包内、且必须在插件命名空间
        #  0.11.30：两档都要过 —— 开发者档里的 13 个入口同样要能反射到真实类（用户档 11 个接线
        #  与开发者档 24 个接线里含重复，这里按出现次数计，不做去重）。
        $ocBadClass = New-Object System.Collections.Generic.List[string]
        $ocBadNs    = New-Object System.Collections.Generic.List[string]
        $ocAll      = New-Object System.Collections.Generic.List[string]
        foreach ($g in (@($p3Groups) + @($p3dGroups))) {
            foreach ($p in @((Get-P3Prop $g 'preferences'))) {
                $t = [string](Get-P3Prop $p 'on_click')
                if ([string]::IsNullOrWhiteSpace($t)) { continue }
                $ocAll.Add($t) | Out-Null
                $title = [string](Get-P3Prop $p 'title')
                if (-not $t.StartsWith('com.example.netease.')) {
                    $ocBadNs.Add("$title → $t") | Out-Null
                }
                $segs = @($t.Split('.'))
                if ($segs.Count -ge 3) {
                    $clsPath = 'classes/' + (($segs[0..($segs.Count - 2)]) -join '/') + '.class'
                    if ($entryNames -notcontains $clsPath) {
                        $ocBadClass.Add("$title → $t（包内无 $clsPath）") | Out-Null
                    }
                }
            }
        }
        Add-Result 'P10.11' "所有 on_click 的目标类都能在包内找到（共 $($ocAll.Count) 个接线）" ($ocBadClass.Count -eq 0) `
            "$($ocAll.Count) 个" (($ocBadClass | Select-Object -First 6) -join ' | ')
        Add-Result 'P10.12' '所有 on_click 目标都在 com.example.netease.**（不得指向宿主 / PF4J / 第三方类）' ($ocBadNs.Count -eq 0) '' `
            (($ocBadNs | Select-Object -First 6) -join ' | ')
    }

    # ================================================================
    # 9. 汇总
    # ================================================================
    Write-Section '9 · 汇总'

    $fail = @($script:Results | Where-Object { -not $_.ok })
    $passCount = @($script:Results | Where-Object { $_.ok }).Count

    if (-not $Json) {
        Write-Host ''
        foreach ($r in $script:Results) {
            $tag = if ($r.ok) { 'PASS' } else { 'FAIL' }
            $color = if ($r.ok) { 'Green' } else { 'Red' }
            Write-Host ("  [{0}] {1,-6} {2}" -f $r.id, $tag, $r.title) -ForegroundColor $color
            if ($r.detail)   { Write-Host ("             └ {0}" -f $r.detail) -ForegroundColor DarkGray }
            if ($r.offender) { Write-Host ("             └ {0}" -f $r.offender) -ForegroundColor Yellow }
        }
        if ($libJars.Count -gt 0) {
            Write-Host ''
            Write-Host '  lib/ 明细：' -ForegroundColor DarkCyan
            foreach ($d in $jarDetail) { Write-Host "    · $d" -ForegroundColor DarkGray }
        }
        Write-Host ''
        Write-Host '────────────────────────────────────────────────────────────────' -ForegroundColor Cyan
        if ($fail.Count -eq 0) {
            Write-Host "  结果：全部通过（$passCount 项 PASS）" -ForegroundColor Green
        } else {
            Write-Host "  结果：$($fail.Count) 项 FAIL / $passCount 项 PASS" -ForegroundColor Red
            foreach ($f in $fail) { Write-Host "    ✗ [$($f.id)] $($f.title)" -ForegroundColor Red }
        }
        Write-Host '────────────────────────────────────────────────────────────────' -ForegroundColor Cyan
        Write-Host ''
    } else {
        $obj = [pscustomobject]@{
            spmod   = $SpmodFull
            passed  = $passCount
            failed  = $fail.Count
            results = $script:Results
            libJars = $jarDetail
        }
        $obj | ConvertTo-Json -Depth 6
    }

    if ($fail.Count -gt 0) { exit 1 } else { exit 0 }
}
catch {
    if ($_.Exception.Message -in @('NO_SPMOD', 'NO_DIST_DIR')) {
        if (-not $Json) {
            Write-Host ''
            Write-Host '  结果：无法校验（产物不存在）' -ForegroundColor Red
        }
        exit 2
    }
    if (-not $Json) {
        Write-Host ''
        Write-Host "  校验器异常终止：$($_.Exception.Message)" -ForegroundColor Red
        Write-Host $_.ScriptStackTrace -ForegroundColor DarkGray
    }
    exit 3
}
finally {
    if ($zip) { $zip.Dispose() }
}
