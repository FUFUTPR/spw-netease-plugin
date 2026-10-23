[CmdletBinding()]
param([string]$Spmod,[string]$OutputDir)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
. (Join-Path $repo 'tools/jdk.ps1')
$jdk=Resolve-RepoJdk
if (-not $Spmod) {
    $project=Get-Content (Join-Path $repo 'project.json') -Raw | ConvertFrom-Json
    $Spmod=Join-Path $repo "build/dist/plugin-$($project.pluginId)-$($project.version).spmod"
}
if (-not $OutputDir) { $OutputDir=Join-Path $repo ('harness/work/playlist-schema-'+[guid]::NewGuid().ToString('N')) }
$out=[IO.Path]::GetFullPath($OutputDir)
New-Item -ItemType Directory -Force $out | Out-Null
[IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path -LiteralPath $Spmod).Path,(Join-Path $out 'plugin'))
$hostJar='C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll'
$cp=(@($out,(Join-Path $out 'plugin/classes'),$hostJar)+@(Get-ChildItem (Join-Path $out 'plugin/lib') -Filter '*.jar' | ForEach-Object FullName)) -join ';'
& $jdk.Javac -encoding UTF-8 -cp $cp -d $out (Join-Path $PSScriptRoot 'nativeui/PlaylistSchemaProbe.java')
if($LASTEXITCODE -ne 0){throw 'Schema probe compilation failed'}
$oldAppData=$env:APPDATA
$oldLocalData=$env:LOCALAPPDATA
try {
    $env:APPDATA=Join-Path $out 'sandbox/roaming'
    $env:LOCALAPPDATA=Join-Path $out 'sandbox/local'
    New-Item -ItemType Directory -Force $env:APPDATA,$env:LOCALAPPDATA,(Join-Path $out 'sandbox/home') | Out-Null
    & $jdk.Java '--enable-native-access=ALL-UNNAMED' ("-Duser.home="+(Join-Path $out 'sandbox/home')) -cp $cp com.example.netease.svc.PlaylistSchemaProbe (Join-Path $PSScriptRoot 'nativeui/schema-1191.sql')
    if($LASTEXITCODE -ne 0){throw 'Schema probe failed'}
    & $jdk.Java '--enable-native-access=ALL-UNNAMED' ("-Duser.home="+(Join-Path $out 'sandbox/home')) -cp $cp com.example.netease.svc.PlaylistSchemaProbe (Join-Path $PSScriptRoot 'nativeui/schema-1191.sql') legacy
    if($LASTEXITCODE -ne 0){throw 'Legacy schema probe failed'}
} finally {$env:APPDATA=$oldAppData;$env:LOCALAPPDATA=$oldLocalData}
Write-Host "Playlist schema evidence: $out"
