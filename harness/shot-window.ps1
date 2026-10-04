<#
.SYNOPSIS
  截取宿主（Salt Player for Windows）主窗口，落盘 PNG，供真机验收取证。

.DESCRIPTION
  Lead 真机会话专用工具（证据采集）。缺省按窗口矩形截图（不含窗口阴影），
  也可 -FullScreen 截整个副屏；窗口最小化时先还原。

.PARAMETER Out
  输出 PNG 路径（相对仓库根或绝对路径）。缺省 .session-inbox\host-<时间戳>.png

.PARAMETER FullScreen
  截整个显示器（窗口所在那块），而不是窗口矩形。

.EXAMPLE
  pwsh -NoProfile -File harness\shot-window.ps1
  pwsh -NoProfile -File harness\shot-window.ps1 -Out .session-inbox\a2-host.png
#>
[CmdletBinding()]
param(
    [string] $Out,
    [switch] $FullScreen
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

if (-not ('ShotWin32' -as [type])) {
    Add-Type -Namespace '' -Name 'ShotWin32' -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool GetWindowRect(System.IntPtr hWnd, out RECT lpRect);
[DllImport("user32.dll")] public static extern bool IsIconic(System.IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool ShowWindow(System.IntPtr hWnd, int nCmdShow);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(System.IntPtr hWnd);
[StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
'@
}

$proc = Get-Process -Name 'Salt Player for Windows' -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $proc) { throw '宿主未运行（找不到带主窗口的进程）' }

$h = $proc.MainWindowHandle
if ([ShotWin32]::IsIconic($h)) { [void][ShotWin32]::ShowWindow($h, 9) ; Start-Sleep -Milliseconds 800 }
[void][ShotWin32]::SetForegroundWindow($h)
Start-Sleep -Milliseconds 600

$rect = New-Object ShotWin32+RECT
[void][ShotWin32]::GetWindowRect($h, [ref]$rect)
$x = $rect.Left; $y = $rect.Top
$w = $rect.Right - $rect.Left; $hh = $rect.Bottom - $rect.Top

if ($FullScreen) {
    $scr = [System.Windows.Forms.Screen]::FromHandle($h)
    $b = $scr.Bounds
    $x = $b.X; $y = $b.Y; $w = $b.Width; $hh = $b.Height
}

if (-not $Out) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $Out = ".session-inbox\host-$stamp.png"
}
# 绝对路径直接用；相对路径按当前工作目录解析（否则会拼成 "CWD\C:\..." 的非法路径）
$Out = if ([System.IO.Path]::IsPathRooted($Out)) { [System.IO.Path]::GetFullPath($Out) }
       else { [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Out)) }
$dir = Split-Path -Parent $Out
if (-not (Test-Path $dir)) { [void](New-Item -ItemType Directory -Force -Path $dir) }

$bmp = New-Object System.Drawing.Bitmap($w, $hh)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($x, $y, 0, 0, (New-Object System.Drawing.Size($w, $hh)))
$g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

"shot: $Out  ($w x $hh)  window=$($proc.Id)  title=[$($proc.MainWindowTitle)]"
