[CmdletBinding()]
param([string] $Spmod)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$Repo = Split-Path -Parent $PSScriptRoot
. (Join-Path $Repo 'tools/jdk.ps1')
$jdk = Resolve-RepoJdk
$project = Get-Content (Join-Path $Repo 'project.json') -Raw | ConvertFrom-Json
if (-not $Spmod) { $Spmod = Join-Path $Repo "build/dist/plugin-$($project.pluginId)-$($project.version).spmod" }
$Work = Join-Path $PSScriptRoot ('work/behavior-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $Work -Force | Out-Null
[System.IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path -LiteralPath $Spmod).Path, (Join-Path $Work 'plugin'))
$cpParts = @((Join-Path $Work 'classes'), (Join-Path $Work 'plugin/classes'))
$cpParts += @(Get-ChildItem (Join-Path $Work 'plugin/lib') -Filter '*.jar' | ForEach-Object FullName)
$cpParts += @('spw-workshop-api-host.jar','pf4j-3.12.0.jar','kotlin-stdlib-host.jar') | ForEach-Object { Join-Path $Repo "tools/.cache/$_" }
$Cp = $cpParts -join ';'
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'regression') -Filter '*Probe.java' | ForEach-Object FullName)
& $jdk.Javac -encoding UTF-8 -cp $Cp -d (Join-Path $Work 'classes') @sources
if ($LASTEXITCODE -ne 0) { throw 'Behavior compilation failed' }
$oldAppData = $env:APPDATA
try {
    $env:APPDATA = Join-Path $Work 'sandbox'
    New-Item -ItemType Directory -Path $env:APPDATA | Out-Null
    & $jdk.Java '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $Cp BehaviorProbe $env:APPDATA 2>&1 | Tee-Object -FilePath (Join-Path $Work 'result.txt')
    $rc = $LASTEXITCODE
    foreach ($probe in @('InteractionPlaybackProbe', 'StreamShutdownProbe', 'com.example.netease.net.CloudPlaylistWriteProbe')) {
        & $jdk.Java '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $Cp $probe 2>&1 | Tee-Object -FilePath (Join-Path $Work ($probe + '-result.txt'))
        if ($LASTEXITCODE -ne 0) { $rc = 1 }
    }
    if (Test-Path -LiteralPath (Join-Path $Work 'classes/AsyncProbe.class')) {
        & $jdk.Java '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $Cp AsyncProbe 2>&1 | Tee-Object -FilePath (Join-Path $Work 'async-result.txt')
        if ($LASTEXITCODE -ne 0) { $rc = 1 }
    }
    if (Test-Path -LiteralPath (Join-Path $Work 'classes/HostTransactionProbe.class')) {
        & $jdk.Java '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $Cp HostTransactionProbe 2>&1 | Tee-Object -FilePath (Join-Path $Work 'host-transaction-result.txt')
        if ($LASTEXITCODE -ne 0) { $rc = 1 }
    }
    if (Test-Path -LiteralPath (Join-Path $Work 'classes/SearchArtworkProbe.class')) {
        & $jdk.Java '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $Cp SearchArtworkProbe 2>&1 | Tee-Object -FilePath (Join-Path $Work 'search-artwork-result.txt')
        if ($LASTEXITCODE -ne 0) { $rc = 1 }
    }
    if (Test-Path -LiteralPath (Join-Path $Work 'classes/com/example/netease/net/SearchArtworkMergeProbe.class')) {
        & $jdk.Java '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $Cp com.example.netease.net.SearchArtworkMergeProbe 2>&1 | Tee-Object -FilePath (Join-Path $Work 'search-artwork-merge-result.txt')
        if ($LASTEXITCODE -ne 0) { $rc = 1 }
    }
    if (Test-Path -LiteralPath (Join-Path $Work 'classes/com/xuncorp/voxzen/data/HostProfileProbe.class')) {
        & $jdk.Java '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $Cp com.xuncorp.voxzen.data.HostProfileProbe 2>&1 | Tee-Object -FilePath (Join-Path $Work 'host-profile-result.txt')
        if ($LASTEXITCODE -ne 0) { $rc = 1 }
    }
} finally { $env:APPDATA = $oldAppData }
Write-Host "Behavior evidence: $Work"
exit $rc
