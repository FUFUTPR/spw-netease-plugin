<#
.SYNOPSIS
  用 UI Automation 读取（并可按名字点击）宿主 Salt Player for Windows 的界面元素，供真机验收取证。

.DESCRIPTION
  Lead 真机会话专用工具。没有视觉模型时的替代取证手段：把宿主界面的元素树（类型/名字/类名/自动化 id）
  以文本形式 dump 出来 —— 例如证明「网易云音乐」歌单在左侧可见、点开后主区列出了曲目行。

.PARAMETER Mode
  dump（缺省）= 打印元素树；list = 只打印「看起来像列表行」的元素（List/ListItem/DataItem/Text）；click = 按名字点击。

.PARAMETER Name
  Mode=click 时按名字（支持包含匹配、忽略大小写）找元素并尝试 Invoke / SelectionItem / 单击。

.PARAMETER MaxDepth
  遍历深度（缺省 12）。

.PARAMETER Max
  最多打印多少行（缺省 400）。

.EXAMPLE
  pwsh -NoProfile -File harness\ua-dump.ps1 -Mode dump -Max 120
  pwsh -NoProfile -File harness\ua-dump.ps1 -Mode click -Name '入眠'
  pwsh -NoProfile -File harness\ua-dump.ps1 -Mode list
#>
[CmdletBinding()]
param(
    [ValidateSet('dump', 'list', 'click')]
    [string] $Mode = 'dump',
    [string] $Name,
    [int]    $MaxDepth = 12,
    [int]    $Max = 400
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0
$OutputEncoding = [System.Text.Encoding]::UTF8

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms

function Get-HostRoot {
    $proc = Get-Process -Name 'Salt Player for Windows' -ErrorAction SilentlyContinue |
            Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
    if (-not $proc) { throw '宿主未运行（找不到带主窗口的进程）' }
    return [System.Windows.Automation.AutomationElement]::FromHandle($proc.MainWindowHandle)
}

$root = Get-HostRoot
if (-not $root) { throw '拿不到宿主主窗口的 AutomationElement（可能被 UWP/自绘界面挡住）' }

if ($Mode -eq 'click') {
    if (-not $Name) { throw 'Mode=click 需要 -Name' }
    $all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants,
                         [System.Windows.Automation.Condition]::TrueCondition)
    $hit = $null
    foreach ($e in $all) {
        $n = ''
        try { $n = $e.Current.Name } catch { }
        if ($n -and $n.ToLowerInvariant().Contains($Name.ToLowerInvariant())) { $hit = $e; break }
    }
    if (-not $hit) { "click: 没找到名字包含「$Name」的元素"; exit 2 }
    "click: 命中 [$($hit.Current.ControlType.ProgrammaticName)] name=[$($hit.Current.Name)] class=[$($hit.Current.ClassName)]"
    $done = $false
    foreach ($pt in @([System.Windows.Automation.InvokePattern]::Pattern,
                      [System.Windows.Automation.SelectionItemPattern]::Pattern,
                      [System.Windows.Automation.ScrollItemPattern]::Pattern)) {
        try {
            if ($hit.TryGetCurrentPattern($pt, [ref]$null)) {
                if ($pt -eq [System.Windows.Automation.InvokePattern]::Pattern) {
                    ([System.Windows.Automation.InvokePattern]$hit.GetCurrentPattern($pt)).Invoke(); $done = $true
                } elseif ($pt -eq [System.Windows.Automation.SelectionItemPattern]::Pattern) {
                    ([System.Windows.Automation.SelectionItemPattern]$hit.GetCurrentPattern($pt)).Select(); $done = $true
                } else {
                    ([System.Windows.Automation.ScrollItemPattern]$hit.GetCurrentPattern($pt)).ScrollIntoView(); $done = $true
                }
                "click: 已用 $($pt.ProgrammaticName) 触发"
                break
            }
        } catch { "click: $($pt.ProgrammaticName) 失败：$($_.Exception.Message)" }
    }
    if (-not $done) {
        # 兜底：取 BoundingRectangle 中心，用鼠标点
        $r = $hit.Current.BoundingRectangle
        if ($r.Width -gt 0 -and $r.Height -gt 0) {
            [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point([int]($r.X + $r.Width / 2), [int]($r.Y + $r.Height / 2))
            Start-Sleep -Milliseconds 200
            Add-Type -Namespace '' -Name 'UAMouse' -MemberDefinition @'
[DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, int e);
'@
            [UAMouse]::mouse_event(0x0002, 0, 0, 0, 0)
            [UAMouse]::mouse_event(0x0004, 0, 0, 0, 0)
            "click: 无 pattern，已用鼠标点中心 ($([int]($r.X + $r.Width / 2)), $([int]($r.Y + $r.Height / 2)))"
            $done = $true
        }
    }
    if (-not $done) { 'click: 未能触发（元素不可交互？）'; exit 3 }
    exit 0
}

$walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
$script:count = 0
$script:out = New-Object System.Collections.Generic.List[string]

function Walk($el, $depth) {
    if ($script:count -ge $Max -or $depth -gt $MaxDepth) { return }
    try { $c = $el.Current } catch { return }
    $ct = ''
    try { $ct = $c.ControlType.ProgrammaticName -replace '^ControlType\.', '' } catch { }
    $nm = ''
    try { $nm = $c.Name } catch { }
    $cl = ''
    try { $cl = $c.ClassName } catch { }
    $id = ''
    try { $id = $c.AutomationId } catch { }
    $show = $true
    if ($Mode -eq 'list') {
        $show = ($ct -in @('List', 'ListItem', 'DataItem', 'Tree', 'TreeItem', 'Table', 'Custom')) -or ($nm -and $nm.Length -gt 0 -and $ct -in @('Text', 'Button'))
    }
    if ($show) {
        $pad = ' ' * (2 * $depth)
        $line = "$pad$ct | $nm"
        if ($cl) { $line += "  <$cl>" }
        if ($id) { $line += "  #$id" }
        $script:out.Add($line)
        $script:count++
    }
    $child = $walker.GetFirstChild($el)
    while ($child -ne $null) {
        Walk $child ($depth + 1)
        $child = $walker.GetNextSibling($child)
    }
}

Walk $root 0
"### UIA dump  mode=$Mode  title=[$($root.Current.Name)]  depth<=$MaxDepth  lines=$($script:count)"
$script:out -join "`n"
