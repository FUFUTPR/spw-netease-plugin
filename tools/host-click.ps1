<#
.SYNOPSIS
  按「窗口截图坐标」点击 Salt Player for Windows（真机验收取证用）。

.DESCRIPTION
  宿主是 Compose/AWT 自绘界面，UIA 读不到元素（只有 SunAwtCanvas），
  所以只能靠 harness\shot-window.ps1 截图定位坐标，再用本脚本按坐标点击。

  坐标语义：harness\shot-window.ps1 生成的 PNG 的窗口内像素坐标（左上角 = 0,0）。

  重要：本机副屏有 125% 缩放，而 pwsh 进程是 DPI-unaware：
    · CopyFromScreen（截图）用物理像素，so 截图 PNG 里的像素 = 屏幕物理像素相对窗口矩形左上角；
    · GetWindowRect / SetCursorPos 用「虚拟化」坐标（物理 × 1.25）。
  实测（2026-10-22）：虚拟坐标 (-1852,320) 的点击，落点 = 截图 (918,256)，
  即 截图坐标 = 虚拟坐标 × 0.8 + 窗口矩形左 (-2400,0)。
  因此本脚本按 -Scale（缺省 1.25 = 125%）把截图坐标换算回 SetCursorPos 需要的虚拟坐标。

.PARAMETER X
  截图坐标 X（像素，窗口左上角为 0）。

.PARAMETER Y
  截图坐标 Y（像素，窗口左上角为 0）。

.PARAMETER Scale
  显示器缩放比（125% → 1.25）。缺省 1.25（本机 DISPLAY2）。

.PARAMETER Double
  双击。

.PARAMETER Right
  右键。

.EXAMPLE
  pwsh -NoProfile -File tools\host-click.ps1 -X 532 -Y 320
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][int] $X,
    [Parameter(Mandatory = $true)][int] $Y,
    [double] $Scale = 1.25,
    [switch] $Double,
    [switch] $Right
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

if (-not ('ClickWin32' -as [type])) {
    Add-Type -Namespace '' -Name 'ClickWin32' -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool GetWindowRect(System.IntPtr hWnd, out RECT lpRect);
[DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
[DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
[DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, System.IntPtr dwExtraInfo);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(System.IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool ShowWindow(System.IntPtr hWnd, int nCmdShow);
[StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
[StructLayout(LayoutKind.Sequential)] public struct POINT { public int X; public int Y; }
'@
}

$proc = Get-Process -Name 'Salt Player for Windows' -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $proc) { throw '宿主未运行（找不到带主窗口的进程）' }

$h = $proc.MainWindowHandle
[void][ClickWin32]::ShowWindow($h, 9)
Start-Sleep -Milliseconds 250
[void][ClickWin32]::SetForegroundWindow($h)
Start-Sleep -Milliseconds 350

$rect = New-Object ClickWin32+RECT
[void][ClickWin32]::GetWindowRect($h, [ref]$rect)
# 截图原点（物理像素空间）= 窗口矩形的 left/top（见 .DESCRIPTION 的实测换算）
$physX = $rect.Left + $X
$physY = $rect.Top + $Y
$virtX = [int][Math]::Round($physX * $Scale)
$virtY = [int][Math]::Round($physY * $Scale)

[void][ClickWin32]::SetCursorPos($virtX, $virtY)
Start-Sleep -Milliseconds 200
$p = New-Object ClickWin32+POINT
[void][ClickWin32]::GetCursorPos([ref]$p)

$MOUSEEVENTF_LEFTDOWN = 0x0002
$MOUSEEVENTF_LEFTUP   = 0x0004
$MOUSEEVENTF_RIGHTDOWN = 0x0008
$MOUSEEVENTF_RIGHTUP   = 0x0010
$down = if ($Right) { $MOUSEEVENTF_RIGHTDOWN } else { $MOUSEEVENTF_LEFTDOWN }
$up   = if ($Right) { $MOUSEEVENTF_RIGHTUP } else { $MOUSEEVENTF_LEFTUP }
$clicks = if ($Double) { 2 } else { 1 }
for ($i = 0; $i -lt $clicks; $i++) {
    [ClickWin32]::mouse_event($down, 0, 0, 0, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 60
    [ClickWin32]::mouse_event($up, 0, 0, 0, [IntPtr]::Zero)
    if ($i -lt $clicks - 1) { Start-Sleep -Milliseconds 120 }
}
"click: shot=($X,$Y) window=($($rect.Left),$($rect.Top)) physical=($physX,$physY) virtual=($virtX,$virtY) cursor=($($p.X),$($p.Y)) scale=$Scale double=$([bool]$Double) right=$([bool]$Right)"
