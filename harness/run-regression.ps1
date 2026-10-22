<# Formal offline cover regressions. All generated data stays under harness/work. #>
[CmdletBinding()]
param([string] $Spmod, [ValidateSet('all','1','2','3','4','5','6','7','8','9')] [string] $Batch = 'all')
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$Repo = Split-Path -Parent $PSScriptRoot
. (Join-Path $Repo 'tools/jdk.ps1')
$jdk = Resolve-RepoJdk
$project = Get-Content (Join-Path $Repo 'project.json') -Raw | ConvertFrom-Json
if (-not $Spmod) { $Spmod = Join-Path $Repo "build/dist/plugin-$($project.pluginId)-$($project.version).spmod" }
$Spmod = (Resolve-Path -LiteralPath $Spmod).Path
$Work = Join-Path $PSScriptRoot ('work/regression-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $Work -Force | Out-Null
$Extract = Join-Path $Work 'plugin'
[System.IO.Compression.ZipFile]::ExtractToDirectory($Spmod, $Extract)
$Cls = Join-Path $Extract 'classes'
$Build = Join-Path $Work 'classes'
New-Item -ItemType Directory -Path $Build | Out-Null
$HostJars = @('spw-workshop-api-host.jar','pf4j-3.12.0.jar','kotlin-stdlib-host.jar') |
    ForEach-Object { Join-Path $Repo "tools/.cache/$_" }
$Libs = @(Get-ChildItem (Join-Path $Extract 'lib') -Filter '*.jar' | ForEach-Object FullName)
$Cp = (@($Build,$Cls) + $Libs + $HostJars) -join ';'
# 批次 8 的探针会触达 Compose runtime 类型，需要把宿主可执行目录里的运行时补进 classpath。
$HostRuntime = 'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll'
$CpRun8 = if (Test-Path -LiteralPath $HostRuntime) { $Cp + ';' + $HostRuntime } else { $Cp }
$batches = if ($Batch -eq 'all') { @(1,2,3,4,5,6,7,8,9) } else { @([int]$Batch) }
foreach ($n in $batches) {
    # 批次 5/6 = 原生 UI 控制器（搜索/歌单、登录/本地命名）的离线语义探针，各自独立 main，分开编译。
    $sources = if ($n -eq 5) {
        @(
            (Join-Path $PSScriptRoot 'regression/cover/SearchControllerProbe.java'),
            (Join-Path $PSScriptRoot 'regression/cover/PlaylistControllerProbe.java'),
            (Join-Path $PSScriptRoot 'regression/cover/PlaylistLockOrderProbe.java'),
            (Join-Path $PSScriptRoot 'regression/cover/SearchKeyboardProbe.java')
        )
    } elseif ($n -eq 6) {
        @(
            (Join-Path $PSScriptRoot 'regression/cover/LoginControllerProbe.java'),
            (Join-Path $PSScriptRoot 'regression/cover/LocalSaveControllerProbe.java')
        )
    } elseif ($n -eq 7) {
        @(
            (Join-Path $PSScriptRoot 'regression/cover/VerifyEmbedControllerProbe.java'),
            (Join-Path $PSScriptRoot 'regression/cover/FakeVerifyHost.java')
        )
    } elseif ($n -eq 8) {
        @((Join-Path $PSScriptRoot 'regression/cover/NativeUiSessionProbe.java'))
    } elseif ($n -eq 9) {
        @((Join-Path $PSScriptRoot 'regression/cover/UiMountScopeProbe.java'))
    } else {
        @((Join-Path $PSScriptRoot "regression/cover/Batch${n}Probe.java"))
    }
    & $jdk.Javac -encoding UTF-8 -d $Build -cp $Cp $sources
    if ($LASTEXITCODE -ne 0) { throw "Regression compilation failed: batch $n" }
}
$rc = 0
$oldAppData = $env:APPDATA
try {
    foreach ($n in $batches) {
        $phases = switch ($n) {
            1 { @('all') }
            2 { @('f3','f4','f5') }
            3 { @('f6','f7') }
            4 { @('f8a','f8b') }
            5 { @('search','playlist','lock-order','search-keyboard') }
            6 { @('login','local-save') }
            7 { @('verify') }
            8 { @('native-ui-session') }
            9 { @('ui-mount-scope') }
        }
        foreach ($phase in $phases) {
            $Sandbox = Join-Path $Work "sandbox-$n-$phase"
            New-Item -ItemType Directory -Path $Sandbox | Out-Null
            $env:APPDATA = $Sandbox
            $main = if ($n -eq 4) {
                'com.example.netease.ui.Batch4Probe'
            } elseif ($n -eq 5) {
                if ($phase -eq 'search') { 'SearchControllerProbe' } elseif ($phase -eq 'playlist') { 'PlaylistControllerProbe' } elseif ($phase -eq 'lock-order') { 'PlaylistLockOrderProbe' } else { 'SearchKeyboardProbe' }
            } elseif ($n -eq 6) {
                if ($phase -eq 'login') { 'LoginControllerProbe' } else { 'LocalSaveControllerProbe' }
            } elseif ($n -eq 7) {
                'com.example.netease.ui.controller.VerifyEmbedControllerProbe'
            } elseif ($n -eq 8) {
                'NativeUiSessionProbe'
            } elseif ($n -eq 9) {
                'com.example.netease.nativeui.UiMountScopeProbe'
            } else {
                "Batch${n}Probe"
            }
            $runCp = if ($n -eq 8 -or $n -eq 9) { $CpRun8 } else { $Cp }
            & $jdk.Java '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '--add-modules' 'jdk.httpserver' `
                -cp $runCp $main $Sandbox $project.version $phase 2>&1 |
                Tee-Object -FilePath (Join-Path $Work "batch-$n-$phase.txt")
            if ($LASTEXITCODE -ne 0) { $rc = 1 }
        }
    }
} finally { $env:APPDATA = $oldAppData }
Write-Host "Regression evidence: $Work"
exit $rc
