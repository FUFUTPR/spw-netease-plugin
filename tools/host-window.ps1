<#
.SYNOPSIS
  把 Salt Player for Windows 的主窗口放到指定显示器（缺省 = 副屏），并可按需先启动宿主。

.DESCRIPTION
  用户偏好（m05962 起，长期有效）：插件开发/真机验收期间启动宿主，**一律开在副屏幕**，
  不要占用主屏——用户常在同一台机器上玩游戏。

  本机当前显示器布局（EnumScreen 实测）：
    \\.\DISPLAY1  Primary   2048x1152 @ (0,0)       ← 主屏，别占
    \\.\DISPLAY2  Secondary 1920x1080 @ (-1920,0)   ← 副屏（在主屏左侧）

  注：AskUserQuestion/宿主窗口位置这类偏好优先用本脚本落地，不要在别处硬编码坐标。

.PARAMETER Screen
  Secondary（缺省）| Primary —— 目标显示器。

.PARAMETER Start
  宿主未运行时先启动它。

.PARAMETER WaitSeconds
  等待主窗口句柄出现的秒数（缺省 30）。

.PARAMETER HostExe
  宿主可执行文件路径，缺省为 Steam 安装根下的 Salt Player for Windows.exe。

.EXAMPLE
  pwsh -File tools\host-window.ps1                 # 把已在运行的宿主主窗口挪到副屏
  pwsh -File tools\host-window.ps1 -Screen Primary # 挪回主屏（临时用）
  pwsh -File tools\host-window.ps1 -Start          # 启动宿主并挪到副屏
#>
[CmdletBinding()]
param(
    [ValidateSet('Secondary', 'Primary')]
    [string] $Screen = 'Secondary',
    [switch] $Start,
    [int]    $WaitSeconds = 30,
    [string] $HostExe = 'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows\Salt Player for Windows.exe'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

$ProcessName = 'Salt Player for Windows'

if (-not ('HostWin32' -as [type])) {
    Add-Type -Namespace '' -Name 'HostWin32' -MemberDefinition @'
[DllImport("user32.dll", SetLastError=true)] public static extern bool MoveWindow(System.IntPtr hWnd, int X, int Y, int nWidth, int nHeight, bool bRepaint);
[DllImport("user32.dll")] public static extern bool ShowWindow(System.IntPtr hWnd, int nCmdShow);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(System.IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool IsIconic(System.IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool GetWindowRect(System.IntPtr hWnd, out RECT lpRect);
[StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
'@
}
Add-Type -AssemblyName System.Windows.Forms

function Get-TargetScreen {
    param([string] $Which)
    $screens = [System.Windows.Forms.Screen]::AllScreens
    $want = if ($Which -eq 'Primary') { $true } else { $false }
    $hit = @($screens | Where-Object { $_.Primary -eq $want })
    if ($hit.Count -eq 0) {
        Write-Warning "$Which 屏不存在（只找到 $($screens.Count) 块），退回主屏"
        $hit = @($screens | Where-Object { $_.Primary })
    }
    return $hit[0]
}

function Get-HostWindow {
    param([int] $WaitSec)
    $deadline = (Get-Date).AddSeconds($WaitSec)
    do {
        $procs = @(Get-Process -Name $ProcessName -ErrorAction SilentlyContinue |
                   Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero })
        if ($procs.Count -gt 0) { return $procs[0] }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $null
}

$target = Get-TargetScreen -Which $Screen
$area   = $target.WorkingArea
Write-Host ("目标显示器 {0} primary={1} 工作区={2}" -f $target.DeviceName, $target.Primary, $area)

$proc = @(Get-Process -Name $ProcessName -ErrorAction SilentlyContinue)
if ($proc.Count -eq 0) {
    if (-not $Start) { Write-Host '宿主未运行（加 -Start 可先启动）'; exit 3 }
    if (-not (Test-Path -LiteralPath $HostExe)) { Write-Error "找不到宿主：$HostExe"; exit 4 }
    Start-Process -FilePath $HostExe | Out-Null
    Write-Host '宿主已启动，等主窗口…'
    Start-Sleep -Seconds 5
} elseif ($Start) {
    Write-Host '宿主已在运行，直接挪窗口'
}

$w = Get-HostWindow -WaitSec $WaitSeconds
if (-not $w) { Write-Warning "等不到主窗口句柄（$WaitSeconds 秒），宿主可能还在启动"; exit 5 }

$hwnd = $w.MainWindowHandle
if ([HostWin32]::IsIconic($hwnd)) { [void][HostWin32]::ShowWindow($hwnd, 9) }   # SW_RESTORE
Start-Sleep -Milliseconds 300

$rect = New-Object HostWin32+RECT
[void][HostWin32]::GetWindowRect($hwnd, [ref]$rect)
$winW = $rect.Right - $rect.Left
$winH = $rect.Bottom - $rect.Top
if ($winW -le 0 -or $winH -le 0) { $winW = [int]($area.Width * 0.8); $winH = [int]($area.Height * 0.8) }
if ($winW -gt $area.Width)  { $winW = $area.Width }
if ($winH -gt $area.Height) { $winH = $area.Height }

$ok = [HostWin32]::MoveWindow($hwnd, $area.X, $area.Y, $winW, $winH, $true)
Write-Host ("MoveWindow hwnd={0} -> ({1},{2}) {3}x{4} ok={5}" -f $hwnd, $area.X, $area.Y, $winW, $winH, $ok)
[void][HostWin32]::SetForegroundWindow($hwnd)
exit 0
