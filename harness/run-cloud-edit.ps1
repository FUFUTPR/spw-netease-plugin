[CmdletBinding()]
param([Parameter(Mandatory)][string]$Spmod)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
. (Join-Path $repo 'tools/jdk.ps1')
$jdk=Resolve-RepoJdk
$work=Join-Path $PSScriptRoot ('work/cloud-edit-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work | Out-Null
[IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path -LiteralPath $Spmod).Path,(Join-Path $work 'plugin'))
$cp=(@((Join-Path $work 'classes'),(Join-Path $work 'plugin/classes'))+@(Get-ChildItem (Join-Path $work 'plugin/lib') -Filter '*.jar' | ForEach-Object FullName)) -join ';'
& $jdk.Javac -encoding UTF-8 -cp $cp -d (Join-Path $work 'classes') @(Get-ChildItem (Join-Path $PSScriptRoot 'cloudedit') -Filter '*.java' | ForEach-Object FullName)
if($LASTEXITCODE -ne 0){throw 'Cloud edit fixture compilation failed'}
$previous=$env:APPDATA
try {
    $env:APPDATA=Join-Path $work 'sandbox'
    New-Item -ItemType Directory -Force $env:APPDATA | Out-Null
    & $jdk.Java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp $cp com.example.netease.svc.CloudEditFixture 2>&1 | Tee-Object -FilePath (Join-Path $work 'result.txt')
    if($LASTEXITCODE -ne 0){throw 'Cloud edit pipeline failed'}
}finally{$env:APPDATA=$previous}
Write-Host "Cloud edit evidence: $work"
