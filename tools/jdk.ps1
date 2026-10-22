<#
.SYNOPSIS
    解析本仓库统一使用的 JDK（宿主 1.19.0 起要求 JDK 25）。

.DESCRIPTION
    宿主 1.19.0 的 API class 文件版本为 69（Java 25），因此：

      * 用 JDK 21 的 javac 编译会报「类文件具有错误的版本 69.0」；
      * 用 JDK 21 的 java 跑 harness 会报 UnsupportedClassVersionError。

    本文件给 build.ps1 与 harness/*.ps1 共用同一个解析规则，保证「构建」与
    「运行测试」用的是同一个 JDK：

      1. $env:SPW_JDK_HOME（显式指定，优先）
      2. $env:JAVA_HOME
      3. 常见安装位置里第一个 major >= 25 的 JDK
      4. PATH 上的 javac（同样要求 major >= 25）

    找不到就抛错，绝不回退到 JDK 21 —— 静默降级会让基线结果不可信。

.EXAMPLE
    . (Join-Path $PSScriptRoot 'jdk.ps1')
    $jdk = Resolve-RepoJdk
    & $jdk.Javac -version
#>

function Get-JavacMajor {
    param([Parameter(Mandatory)] [string] $Javac)

    # javac -version 输出形如 "javac 25.0.4.1"；某些版本会写到 stderr
    $raw = (& $Javac -version 2>&1 | Out-String).Trim()
    if ($raw -match '(\d+)(\.(\d+))*') {
        return [int] $Matches[1]
    }
    return 0
}

function Resolve-RepoJdk {
    [CmdletBinding()]
    param(
        # 最低 major 版本，宿主 1.19.0 起固定 25
        [int] $MinimumMajor = 25
    )

    $candidates = New-Object System.Collections.Generic.List[string]

    if ($env:SPW_JDK_HOME) { $candidates.Add($env:SPW_JDK_HOME) }
    if ($env:JAVA_HOME)     { $candidates.Add($env:JAVA_HOME) }

    # 常见 JDK 25 安装位置（只做发现，不写死具体用户名）
    $roots = @()
    foreach ($pf in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if ($pf) {
            $roots += (Join-Path $pf 'Java')
            $roots += (Join-Path $pf 'Eclipse Adoptium')
            $roots += (Join-Path $pf 'Microsoft')
            $roots += (Join-Path $pf 'Zulu')
        }
    }
    if ($HOME) { $roots += (Join-Path $HOME 'tools') }
    if ($env:LOCALAPPDATA) { $roots += (Join-Path $env:LOCALAPPDATA 'Programs') }

    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        $dirs = @(Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
                  Where-Object { $_.Name -match '^(jdk[-_]?25|jdk-25|jdk25|temurin-25|zulu-25|microsoft-jdk-25)' })
        foreach ($d in ($dirs | Sort-Object Name -Descending)) { $candidates.Add($d.FullName) }
    }

    # PATH 上的 javac 作为最后兜底（仍然校验 major）
    $pathJavac = Get-Command javac -ErrorAction SilentlyContinue
    if ($pathJavac) { $candidates.Add((Split-Path -Parent (Split-Path -Parent $pathJavac.Source))) }

    $tried = New-Object System.Collections.Generic.List[string]
    foreach ($cand in $candidates) {
        if (-not $cand) { continue }
        $javac = Join-Path $cand 'bin/javac.exe'
        if (-not (Test-Path -LiteralPath $javac)) { $javac = Join-Path $cand 'bin/javac' }
        if (-not (Test-Path -LiteralPath $javac)) { continue }
        $major = Get-JavacMajor -Javac $javac
        $tried.Add("$cand (javac major=$major)")
        if ($major -lt $MinimumMajor) { continue }

        $java = Join-Path $cand 'bin/java.exe'
        if (-not (Test-Path -LiteralPath $java)) { $java = Join-Path $cand 'bin/java' }
        return [pscustomobject]@{
            Home    = (Resolve-Path -LiteralPath $cand).Path
            Javac   = (Resolve-Path -LiteralPath $javac).Path
            Java    = (Resolve-Path -LiteralPath $java).Path
            Version = (& $javac -version 2>&1 | Out-String).Trim()
            Major   = $major
        }
    }

    $detail = if ($tried.Count -gt 0) { $tried -join '; ' } else { '未发现任何候选 JDK' }
    throw @"
找不到 JDK $MinimumMajor+（宿主 1.19.0 起要求）。已尝试：$detail

请任选其一：
  1) 设置 JAVA_HOME 指向 JDK 25（当前 JAVA_HOME=$($env:JAVA_HOME)）
  2) 设置 SPW_JDK_HOME 指向 JDK 25
  3) 把 JDK 25 装到常见位置（如 %ProgramFiles%\Java\jdk-25*、$HOME\tools\jdk25）
"@
}
