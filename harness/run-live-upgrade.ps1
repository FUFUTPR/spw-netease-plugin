[CmdletBinding()]
param([string] $Spmod)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$repo = Split-Path -Parent $PSScriptRoot
. (Join-Path $repo 'tools/jdk.ps1')
$jdk = Resolve-RepoJdk
& (Join-Path $repo 'tools/build-live-upgrade.ps1')
$project = Get-Content (Join-Path $repo 'project.json') -Raw | ConvertFrom-Json
if (-not $Spmod) { $Spmod = Join-Path $repo "build/dist/plugin-$($project.pluginId)-$($project.version).spmod" }
$work = Join-Path $repo ('harness/work/live-upgrade-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work | Out-Null
$plugin = Join-Path $work 'plugin'
[IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path -LiteralPath $Spmod).Path, $plugin)
$classes = Join-Path $work 'probe'
New-Item -ItemType Directory -Force $classes | Out-Null
$agent = Join-Path $repo 'build/dist/netease-live-upgrade-helper.jar'
$cp = $classes + ';' + (Join-Path $plugin 'classes') + ';' + $agent
& $jdk.Javac -encoding UTF-8 --add-modules jdk.attach -cp $cp -d $classes (Join-Path $PSScriptRoot 'nativeui/LiveUpgradeProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Live upgrade probe compilation failed' }
$sqlite = (Get-ChildItem -LiteralPath (Join-Path $plugin 'lib') -Filter 'sqlite-jdbc-*.jar').FullName
& $jdk.Java --add-modules jdk.attach -cp $cp spw.upgrade.LiveUpgradeProbe $agent $sqlite $work
if ($LASTEXITCODE -ne 0) { throw "Live upgrade probe failed. Evidence: $work" }
