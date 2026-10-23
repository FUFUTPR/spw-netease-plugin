[CmdletBinding()]
param([string] $Spmod)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
. (Join-Path $repo 'tools/jdk.ps1')
$jdk = Resolve-RepoJdk
$project = Get-Content (Join-Path $repo 'project.json') -Raw | ConvertFrom-Json
if (-not $Spmod) { $Spmod = Join-Path $repo "build/dist/plugin-$($project.pluginId)-$($project.version).spmod" }
$work = Join-Path $repo ('harness/work/upgrade-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work | Out-Null
$plugin = Join-Path $work 'plugin'
[IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path -LiteralPath $Spmod).Path, $plugin)
$classes = Join-Path $work 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$cp = $classes + ';' + (Join-Path $plugin 'classes')
& $jdk.Javac -encoding UTF-8 -cp $cp -d $classes (Join-Path $repo 'harness/nativeui/JdbcUnloadProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Upgrade probe compile failed' }
$jar = (Get-ChildItem -LiteralPath (Join-Path $plugin 'lib') -Filter 'sqlite-jdbc-*.jar').FullName
$baseline = Join-Path $work 'baseline.log'
& $jdk.Java '--enable-native-access=ALL-UNNAMED' -cp $cp JdbcUnloadProbe $jar $work *> $baseline
if ($LASTEXITCODE -eq 0 -or -not (Select-String -LiteralPath $baseline -Pattern 'AccessDeniedException')) {
    throw "Expected Windows cached-JAR baseline failure; inspect $baseline"
}
Write-Host 'UPGRADE_BASELINE_RESULT=PASS (reproduced old directory move failure after loader close)'
foreach ($cycle in 1..3) {
    $log = Join-Path $work "fixed-$cycle.log"
    & $jdk.Java '--enable-native-access=ALL-UNNAMED' -cp $cp JdbcUnloadProbe $jar $work fixed *> $log
    if ($LASTEXITCODE -ne 0) { Get-Content $log; throw "Upgrade cycle $cycle failed" }
    Write-Host "UPGRADE_FIXED_RESULT=PASS cycle=$cycle (archive ownership, JDBC reuse, loader close, directory move)"
}
Write-Host "Upgrade evidence: $work"
