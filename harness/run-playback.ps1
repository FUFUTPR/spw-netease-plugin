[CmdletBinding()]
param([Parameter(Mandatory)][string]$Spmod)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
. (Join-Path $repo 'tools/jdk.ps1')
$jdk=Resolve-RepoJdk
$work=Join-Path $PSScriptRoot ('work/playback-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work | Out-Null
[IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path -LiteralPath $Spmod).Path,(Join-Path $work 'plugin'))
$cp=(@((Join-Path $work 'classes'),(Join-Path $work 'plugin/classes'))+@(Get-ChildItem (Join-Path $work 'plugin/lib') -Filter '*.jar' | ForEach-Object FullName)+@('spw-workshop-api-host.jar','pf4j-3.12.0.jar','kotlin-stdlib-host.jar' | ForEach-Object {Join-Path $repo "tools/.cache/$_"})) -join ';'
& $jdk.Javac -encoding UTF-8 -cp $cp -d (Join-Path $work 'classes') (Join-Path $PSScriptRoot 'playback/PlaybackController.java') (Join-Path $PSScriptRoot 'playback/PlaybackBridgeProbe.java')
if($LASTEXITCODE -ne 0){throw 'Playback fixture compilation failed'}
$previous=$env:APPDATA
try {
    $env:APPDATA=Join-Path $work 'sandbox'
    New-Item -ItemType Directory -Force $env:APPDATA | Out-Null
    & $jdk.Java '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp $cp spw.harness.playback.PlaybackBridgeProbe 2>&1 | Tee-Object -FilePath (Join-Path $work 'result.txt')
    if($LASTEXITCODE -ne 0){throw 'Playback bridge regression failed'}
}finally{$env:APPDATA=$previous}
Write-Host "Playback evidence: $work"
