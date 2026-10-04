<#
.SYNOPSIS
    从 Salt Player for Windows 宿主内嵌 JAR 中抽取编译期所需的 API 类。

.DESCRIPTION
    宿主 jpackage 产物把整个 JVM 应用打成了单个 JAR：
        <安装目录>\app\ffmpeg-x64.dll        (magic = 50 4B 03 04，即 PK\x03\x04)
    插件编译只需要其中两组类：

        com/xuncorp/spw/workshop/**   ->  tools\.cache\spw-workshop-api-host.jar
        org/pf4j/**                   ->  tools\.cache\pf4j-3.12.0.jar

    ⚠️ 必须使用「宿主自身抽取」的 API jar。
       JitPack 上的 com.github.Moriafly:spw-workshop-api:0.1.0-dev21 是 25 个类，
       而宿主 1.18.5 只有 19 个类；用 dev21 编译 → 可能引用到 1.18.5 不存在的成员
       → 运行期 NoSuchMethodError / NoClassDefFoundError。

.PARAMETER HostJar
    宿主内嵌 JAR 的完整路径。缺省自动探测 Steam / Program Files / LOCALAPPDATA。

.PARAMETER OutDir
    产物目录，缺省 <仓库根>\tools\.cache。

.PARAMETER Force
    已存在产物时强制重新抽取。

.EXAMPLE
    pwsh -File tools\extract-api.ps1
    pwsh -File tools\extract-api.ps1 -HostJar 'D:\SPW\app\ffmpeg-x64.dll' -Force
#>
[CmdletBinding()]
param(
    [string] $HostJar,
    [string] $OutDir,
    [switch] $Force
)

$ErrorActionPreference = 'Stop'

if (-not $OutDir) { $OutDir = Join-Path $PSScriptRoot '.cache' }

# ---------------------------------------------------------------- 宿主 JAR 探测
function Resolve-HostJar {
    param([string] $Explicit)

    if ($Explicit) { return $Explicit }

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:SPW_HOME) { $candidates.Add((Join-Path $env:SPW_HOME 'app\ffmpeg-x64.dll')) }
    if ($env:ProgramFiles)          { $candidates.Add((Join-Path $env:ProgramFiles     'Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll')) }
    if (${env:ProgramFiles(x86)})   { $candidates.Add((Join-Path ${env:ProgramFiles(x86)} 'Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll')) }
    if ($env:ProgramFiles)          { $candidates.Add((Join-Path $env:ProgramFiles     'Salt Player for Windows\app\ffmpeg-x64.dll')) }
    if (${env:ProgramFiles(x86)})   { $candidates.Add((Join-Path ${env:ProgramFiles(x86)} 'Salt Player for Windows\app\ffmpeg-x64.dll')) }
    if ($env:LOCALAPPDATA)          { $candidates.Add((Join-Path $env:LOCALAPPDATA     'Programs\Salt Player for Windows\app\ffmpeg-x64.dll')) }
    # 兜底：Steam 库可能放在非默认盘
    $candidates.Add('C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll')
    $candidates.Add('D:\SteamLibrary\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll')

    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath $c)) { return $c }
    }
    throw '找不到宿主 JAR（app\ffmpeg-x64.dll）。请用 -HostJar 显式指定，或设置 SPW_HOME 环境变量。'
}

# ---------------------------------------------------------------- 抽取一个前缀
function Export-ZipPrefix {
    param(
        [Parameter(Mandatory)] [System.IO.Compression.ZipArchive] $Zip,
        [Parameter(Mandatory)] [string] $Prefix,
        [Parameter(Mandatory)] [string] $Destination
    )

    if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Force }

    $out = [System.IO.Compression.ZipFile]::Open($Destination, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $count = 0
        foreach ($entry in $Zip.Entries) {
            if (-not $entry.FullName.StartsWith($Prefix, [System.StringComparison]::Ordinal)) { continue }
            if ($entry.FullName.EndsWith('/')) { continue }          # 目录条目跳过
            if ($entry.Length -le 0) { continue }                    # 空条目跳过

            $dst = $out.CreateEntry($entry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
            $dstStream = $dst.Open()
            $srcStream = $entry.Open()
            try   { $srcStream.CopyTo($dstStream) }
            finally { $dstStream.Dispose(); $srcStream.Dispose() }
            $count++
        }
        return $count
    }
    finally { $out.Dispose() }
}

function Get-ClassCount {
    param([Parameter(Mandatory)] [string] $JarPath)
    $z = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try { return @($z.Entries | Where-Object { $_.FullName.EndsWith('.class') }).Count }
    finally { $z.Dispose() }
}

# ---------------------------------------------------------------- main
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue | Out-Null

$jar = Resolve-HostJar -Explicit $HostJar
if (-not (Test-Path -LiteralPath $jar)) { throw "宿主 JAR 不存在：$jar" }

# magic 校验：必须是 ZIP（PK\x03\x04）
$fs = [System.IO.File]::OpenRead($jar)
try {
    $magic = New-Object byte[] 4
    [void] $fs.Read($magic, 0, 4)
}
finally { $fs.Dispose() }

if (-not ($magic[0] -eq 0x50 -and $magic[1] -eq 0x4B -and $magic[2] -eq 0x03 -and $magic[3] -eq 0x04)) {
    throw "$jar 不是 ZIP/JAR（magic != PK\x03\x04）。"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$apiOut  = Join-Path $OutDir 'spw-workshop-api-host.jar'
$pf4jOut = Join-Path $OutDir 'pf4j-3.12.0.jar'

if ((-not $Force) -and (Test-Path -LiteralPath $apiOut) -and (Test-Path -LiteralPath $pf4jOut)) {
    $apiClasses = Get-ClassCount -JarPath $apiOut
    Write-Host "[skip] 产物已存在（加 -Force 可重新抽取）：$apiOut ($apiClasses class) ; $pf4jOut"
    exit 0
}

$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    $apiCount  = Export-ZipPrefix -Zip $zip -Prefix 'com/xuncorp/spw/workshop/' -Destination $apiOut
    $pf4jCount = Export-ZipPrefix -Zip $zip -Prefix 'org/pf4j/'                -Destination $pf4jOut
}
finally { $zip.Dispose() }

$apiClasses  = Get-ClassCount -JarPath $apiOut
$apiSize     = (Get-Item -LiteralPath $apiOut).Length
$pf4jSize    = (Get-Item -LiteralPath $pf4jOut).Length

Write-Host ''
Write-Host "宿主 JAR : $jar"
Write-Host "API  JAR : $apiOut"
Write-Host "           $apiClasses 个 class / $apiCount 条目 / $apiSize B"
Write-Host "PF4J JAR : $pf4jOut"
Write-Host "           $pf4jCount 条目 / $pf4jSize B"
Write-Host ''

if ($apiClasses -ne 19) {
    Write-Warning "宿主 API class 数 = $apiClasses，而 1.18.5 预期 19。宿主可能已升级：请重新核对 docs/02-Workshop-API-参考.md 的类清单与签名。"
}
if ($pf4jCount -lt 50) {
    Write-Warning "PF4J 条目数 = $pf4jCount，看起来偏少，宿主内嵌的 org.pf4j 可能已换版本。"
}

Write-Host '抽取完成。'
