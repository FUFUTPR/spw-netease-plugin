<# Read-only byte comparison of the installed plugin and the exact delivery package.
   Version text alone is insufficient: older local builds reused the same version.
   preference_config.json is intentionally rewritten by the runtime dev-mode switch. #>
[CmdletBinding()]
param([string] $Spmod, [string] $InstalledDir)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$repo = Split-Path -Parent $PSScriptRoot
$project = Get-Content (Join-Path $repo 'project.json') -Raw | ConvertFrom-Json
if (-not $Spmod) { $Spmod = Join-Path $repo "build/dist/plugin-$($project.pluginId)-$($project.version).spmod" }
$Spmod = (Resolve-Path -LiteralPath $Spmod).Path
if (-not $InstalledDir) {
    $InstalledDir = Join-Path $env:APPDATA "Salt Player for Windows/workshop/plugins/plugin-$($project.pluginId)-$($project.version)"
}
if (-not (Test-Path -LiteralPath $InstalledDir -PathType Container)) { throw "Expected installed version not found: $InstalledDir" }
$InstalledDir = (Resolve-Path -LiteralPath $InstalledDir).Path
$zip = [IO.Compression.ZipFile]::OpenRead($Spmod)
$sha = [Security.Cryptography.SHA256]::Create()
$mismatches = [Collections.Generic.List[string]]::new()
$checked = 0
try {
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName.EndsWith('/') -or $entry.FullName -eq 'classes/preference_config.json') { continue }
        $relative = $entry.FullName.Replace('\','/')
        if ($relative.StartsWith('/') -or $relative.Split('/') -contains '..') { throw "Unsafe package path: $relative" }
        $path = Join-Path $InstalledDir $relative
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { $mismatches.Add("MISSING $relative"); continue }
        $expected = $entry.Open()
        try { $expectedHash = [Convert]::ToHexString($sha.ComputeHash($expected)) } finally { $expected.Dispose() }
        $actual = [IO.File]::OpenRead($path)
        try { $actualHash = [Convert]::ToHexString($sha.ComputeHash($actual)) } finally { $actual.Dispose() }
        $checked++
        if ($expectedHash -ne $actualHash) { $mismatches.Add("DIFFERENT $relative") }
    }
} finally { $sha.Dispose(); $zip.Dispose() }
$archive = Join-Path (Split-Path -Parent $InstalledDir) ([IO.Path]::GetFileName($InstalledDir) + '.spmod')
if (Test-Path -LiteralPath $archive -PathType Leaf) {
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $Spmod -Algorithm SHA256).Hash) {
        $mismatches.Add('DIFFERENT installed .spmod archive')
    }
}
if ($mismatches.Count -gt 0) {
    $mismatches | Select-Object -First 12 | Write-Host
    Write-Host "INSTALLED_PACKAGE_RESULT=FAIL checked=$checked mismatches=$($mismatches.Count)"
    exit 1
}
Write-Host "INSTALLED_PACKAGE_RESULT=PASS staticFiles=$checked sha256=$((Get-FileHash -LiteralPath $Spmod -Algorithm SHA256).Hash.ToLowerInvariant())"
Write-Host 'Package bytes match. Runtime loading and visible interactions require separate verification.'
