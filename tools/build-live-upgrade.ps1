[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'jdk.ps1')
$jdk = Resolve-RepoJdk
$classes = Join-Path $repo 'build/hot-upgrade/agent-classes'
$agent = Join-Path $repo 'build/dist/netease-live-upgrade-helper.jar'
New-Item -ItemType Directory -Force $classes, (Split-Path -Parent $agent) | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'hot-upgrade') -Filter '*.java' | ForEach-Object FullName)
& $jdk.Javac -encoding UTF-8 --release 25 --add-modules jdk.attach -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Live upgrade helper compilation failed' }
$manifest = Join-Path $repo 'build/hot-upgrade/agent-manifest.mf'
@('Manifest-Version: 1.0', 'Agent-Class: spw.upgrade.JdbcArchiveAgent', 'Main-Class: spw.upgrade.PrepareLiveUpgrade', '') | Set-Content -LiteralPath $manifest -Encoding ascii
& (Join-Path $jdk.Home 'bin/jar.exe') --create --file $agent --manifest $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Live upgrade helper packaging failed' }
Write-Host $agent
