[CmdletBinding()]
param([string] $Spmod, [string] $HostApp = 'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\app', [string] $OutputDir,
    [string[]] $Pages = @('HOME','SEARCH','PLAYLIST','LOGIN','SMS','RISK','ACCOUNT','SYNC','TOPSEARCH'),
    [int[]] $Widths = @(800,480), [string[]] $Audits = @('THEME','HOOK','ENGINE'), [string] $RuntimeJar)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$repo = Split-Path -Parent $PSScriptRoot
. (Join-Path $repo 'tools/jdk.ps1')
$jdk = Resolve-RepoJdk
$pj = Get-Content (Join-Path $repo 'project.json') -Raw | ConvertFrom-Json
if (-not $Spmod) { $Spmod = Join-Path $repo "build/dist/plugin-$($pj.pluginId)-$($pj.version).spmod" }
if (-not $OutputDir) { $OutputDir = Join-Path $repo ('build/evidence/host-salt-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
New-Item -ItemType Directory -Force $OutputDir | Out-Null
$hostRuntime = if ($RuntimeJar) { (Resolve-Path -LiteralPath $RuntimeJar).Path } else { Join-Path $HostApp 'ffmpeg-x64.dll' }
if (-not (Test-Path -LiteralPath $hostRuntime)) { throw 'Installed Salt Player runtime is required; no stub fallback.' }
# Enforce the user's UI boundary on the actual package, not only the sources.
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Spmod).Path)
try {
    foreach ($entry in $zip.Entries | Where-Object { $_.FullName.EndsWith('.class') }) {
        if ($entry.FullName -match '^classes/(androidx/compose/|com/moriafly/salt/|kotlin/)') { throw "Bundled host runtime: $($entry.FullName)" }
        $stream = $entry.Open()
        try { $ms = [IO.MemoryStream]::new(); $stream.CopyTo($ms); $bytes = [Text.Encoding]::Latin1.GetString($ms.ToArray()) }
        finally { $stream.Dispose(); $ms.Dispose() }
        if ($bytes.Contains('androidx/compose/ui/window/Dialog') -or $bytes.Contains('androidx/compose/ui/awt/ComposeDialog')) { throw "Separate dialog window reference: $($entry.FullName)" }
        if ($bytes.Contains('androidx/compose/material3/')) { throw "Material3 reference: $($entry.FullName)" }
    }
} finally { $zip.Dispose() }
Write-Host 'HOST_SALT_PACKAGE_RESULT=PASS (embedded dialogs allowed; no separate dialog windows, Material3 or bundled host runtime)'
$extract = Join-Path $OutputDir 'plugin'
[IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path -LiteralPath $Spmod).Path, $extract)
$classes = Join-Path $OutputDir 'probe'
New-Item -ItemType Directory -Force $classes | Out-Null
$cp = (@($classes, (Join-Path $extract 'classes'), $hostRuntime, $HostApp) + @(Get-ChildItem (Join-Path $extract 'lib') -Filter '*.jar' | ForEach-Object FullName)) -join ';'
& $jdk.Javac -encoding UTF-8 -cp $cp -d $classes (Join-Path $PSScriptRoot 'nativeui/HostSaltRenderProbe.java') (Join-Path $PSScriptRoot 'nativeui/HostHookEngineProbe.java') (Join-Path $PSScriptRoot 'nativeui/GlobalSearchHookProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Render probe compilation failed' }
$bridge = Join-Path $OutputDir 'bridge'
New-Item -ItemType Directory -Force $bridge | Out-Null
& $jdk.Javac -encoding UTF-8 -cp $cp -d $bridge (Join-Path $PSScriptRoot 'nativeui/bridge/WorkshopHookBridge.java')
if ($LASTEXITCODE -ne 0) { throw 'Bridge fixture compilation failed' }
$oldAppData = $env:APPDATA
$oldLocalData = $env:LOCALAPPDATA
try {
    $env:APPDATA = Join-Path $OutputDir 'sandbox/roaming'
    $env:LOCALAPPDATA = Join-Path $OutputDir 'sandbox/local'
    $probeHome = Join-Path $OutputDir 'sandbox/home'
    New-Item -ItemType Directory -Force $env:APPDATA, $env:LOCALAPPDATA, $probeHome | Out-Null
    & $jdk.Javac -encoding UTF-8 -cp $cp -d $classes (Join-Path $PSScriptRoot 'nativeui/CloudMenuProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Cloud menu probe compilation failed' }
    & $jdk.Java ("-Duser.home=" + $probeHome) -cp $cp CloudMenuProbe *> (Join-Path $OutputDir 'CLOUD-MENU.log')
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $OutputDir 'CLOUD-MENU.log') -Tail 35; throw 'Native cloud menu regression failed' }
    Get-Content (Join-Path $OutputDir 'CLOUD-MENU.log') | Where-Object { $_ -match '^CLOUD_NATIVE_MENU_RESULT=' } | Write-Host
    $baselineLog = Join-Path $OutputDir 'ENGINE-BASELINE.log'
    & $jdk.Java ("-Duser.home=" + $probeHome) -cp $cp HostHookEngineProbe $bridge *> $baselineLog
    if ($LASTEXITCODE -ne 0) { Get-Content $baselineLog -Tail 30; throw 'Actual-engine baseline reproduction failed' }
    Get-Content $baselineLog | Where-Object { $_ -match '^HOST_UI_ENGINE_BASELINE_RESULT=' } | Write-Host
    $globalLog = Join-Path $OutputDir 'GLOBAL-ENGINE.log'
    & $jdk.Java ("-Duser.home=" + $probeHome) -cp $cp GlobalSearchHookProbe $bridge *> $globalLog
    if ($LASTEXITCODE -ne 0) { Get-Content $globalLog -Tail 30; throw 'Global search hook execution failed' }
    Get-Content $globalLog | Where-Object { $_ -match '^GLOBAL_SEARCH_HOOK_RESULT=' } | Write-Host
    foreach ($width in $Widths) {
        foreach ($page in $Pages) {
            $log = Join-Path $OutputDir "$page-$width.log"
            & $jdk.Java '--enable-native-access=ALL-UNNAMED' '-Dskiko.renderApi=SOFTWARE' ("-Duser.home=" + $probeHome) -cp $cp HostSaltRenderProbe $page $width $OutputDir *> $log
            if ($LASTEXITCODE -ne 0) { Get-Content $log -Tail 35; throw "Render failed: $page width=$width" }
            $result = Get-Content $log | Where-Object { $_ -match '^HOST_SALT_RENDER_RESULT=' }
            if (-not $result) { throw "No render result: $page width=$width" }
            Write-Host $result
        }
    }
    foreach ($audit in $Audits) {
        $log = Join-Path $OutputDir "$audit.log"
        & $jdk.Java '--enable-native-access=ALL-UNNAMED' '-Dskiko.renderApi=SOFTWARE' ("-Duser.home=" + $probeHome) -cp $cp HostSaltRenderProbe $audit 480 $OutputDir *> $log
        if ($LASTEXITCODE -ne 0) { Get-Content $log -Tail 40; throw "Audit failed: $audit" }
        $result = Get-Content $log | Where-Object { $_ -match '^HOST_(THEME_FOLLOW|UI_REPLACEMENT|UI_ENGINE)_RESULT=' }
        if (-not $result) { throw "No audit result: $audit" }
        Write-Host $result
    }
} finally { $env:APPDATA = $oldAppData; $env:LOCALAPPDATA = $oldLocalData }
Write-Host "Evidence: $OutputDir"
