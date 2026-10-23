[CmdletBinding()]
param([switch] $InspectOnly)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$repo = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'jdk.ps1')
$jdk = Resolve-RepoJdk
& (Join-Path $PSScriptRoot 'build-live-upgrade.ps1')
$agent = Join-Path $repo 'build/dist/netease-live-upgrade-helper.jar'
$evidence = Join-Path $repo ('build/evidence/live-upgrade-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $evidence | Out-Null
$mode = if ($InspectOnly) { 'inspect' } else { 'release' }
& $jdk.Java --add-modules jdk.attach -jar $agent $agent $evidence $mode
if ($LASTEXITCODE -ne 0) { throw "Live upgrade preparation failed. Evidence: $evidence" }
Write-Host "Evidence: $evidence"
