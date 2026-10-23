[CmdletBinding()]
param([string] $Spmod, [string] $RuntimeJar, [ValidateSet('1.19.0','1.19.1','1.19.2')] [string] $HostVersion = '1.19.2')
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$repo = Split-Path -Parent $PSScriptRoot
. (Join-Path $repo 'tools/jdk.ps1')
$jdk = Resolve-RepoJdk
$project = Get-Content (Join-Path $repo 'project.json') -Raw | ConvertFrom-Json
if (-not $Spmod) { $Spmod = Join-Path $repo "build/dist/plugin-$($project.pluginId)-$($project.version).spmod" }
if (-not $RuntimeJar) { $RuntimeJar = 'C:/Program Files (x86)/Steam/steamapps/common/Salt Player for Windows/app/ffmpeg-x64.dll' }
$work = Join-Path $repo ('harness/work/business-abi-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work | Out-Null
$plugin = Join-Path $work 'plugin'
[IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path -LiteralPath $Spmod).Path, $plugin)
$classes = Join-Path $work 'probe'
New-Item -ItemType Directory -Force $classes | Out-Null
$cp = $classes + ';' + (Join-Path $plugin 'classes') + ';' + (Resolve-Path -LiteralPath $RuntimeJar).Path
& $jdk.Javac -encoding UTF-8 -cp $cp -d $classes (Join-Path $PSScriptRoot 'nativeui/HostBusinessAbiProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Business ABI probe compilation failed' }
& $jdk.Java '-Dstdout.encoding=UTF-8' -cp $cp HostBusinessAbiProbe $HostVersion
if ($LASTEXITCODE -ne 0) { throw "Business ABI probe failed. Evidence: $work" }
