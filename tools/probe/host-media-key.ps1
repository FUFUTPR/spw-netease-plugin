# host-media-key.ps1 —— 给宿主进程发一次「媒体键」（Windows 全局媒体控制键），并做**输出静音保护**。
#
# 为什么需要它（C 轮取证用）：
#   宿主 UI 是 Compose 画到 SunAwtCanvas，UIA 不接受合成鼠标事件 ⇒ 无法用点击驱动「下一首」。
#   但宿主注册了 Windows 媒体键（VK_MEDIA_NEXT_TRACK 等）⇒ 全局媒体键是**唯一不需要点界面**
#   就能触发一次真实换曲的通道；而真实换曲是 W4 判据 J4/J5（命中 / 邻曲保温）与 A10 的取样前提。
#
# 静音保护：先读**当前**静音状态与主音量（CoreAudio IAudioEndpointVolume），set-mute=true，
#   跑完再按**读到的原值**还原（不是无脑切换，避免把用户本来就静音的设备打开）。
#
# 用法：
#   pwsh -File tools\probe\host-media-key.ps1 -Key Next -WaitSeconds 25
#   pwsh -File tools\probe\host-media-key.ps1 -Key Next -DryRun      # 只读音量/静音，不发键

param(
  [ValidateSet('Next', 'Prev', 'PlayPause', 'Stop')][string]$Key = 'Next',
  [int]$WaitSeconds = 25,
  [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class SpwAudio {
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] private class MMDeviceEnumeratorComObject { }

    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator {
        int NotImpl1();                                    // EnumAudioEndpoints
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice ppDevice);
    }

    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice {
        int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams,
                     [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
    }

    // IAudioEndpointVolume 的 vtable 顺序（只用到 12=SetMute / 13=GetMute，前面用占位方法占住槽位）
    [Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioEndpointVolume {
        int _1(); int _2(); int _3(); int _4(); int _5(); int _6(); int _7();
        int _8(); int _9(); int _10(); int _11();
        int SetMute([MarshalAs(UnmanagedType.Bool)] bool bMute, Guid ctx);
        int GetMute(out bool pbMute);
    }

    private static IAudioEndpointVolume Endpoint() {
        var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
        IMMDevice dev;
        Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0 /*eRender*/, 1 /*eMultimedia*/, out dev));
        Guid iid = typeof(IAudioEndpointVolume).GUID;
        object o;
        Marshal.ThrowExceptionForHR(dev.Activate(ref iid, 23 /*CLSCTX_ALL*/, IntPtr.Zero, out o));
        return (IAudioEndpointVolume)o;
    }

    public static bool GetMute() {
        bool m; Marshal.ThrowExceptionForHR(Endpoint().GetMute(out m)); return m;
    }

    public static void SetMute(bool m) {
        Marshal.ThrowExceptionForHR(Endpoint().SetMute(m, Guid.Empty));
    }
}
'@

Write-Output ("默认输出设备：静音={0}" -f [SpwAudio]::GetMute())
if ($DryRun) { Write-Output 'DryRun：不发媒体键'; exit 0 }

$wasMuted = [SpwAudio]::GetMute()
if (-not $wasMuted) { [SpwAudio]::SetMute($true); Write-Output '已静音（原状态=不静音，跑完还原）' }
else { Write-Output '设备本来就是静音，保持不动' }

$VK = @{ Next = 0xB0; Prev = 0xB1; Stop = 0xB2; PlayPause = 0xB3 }[$Key]
Add-Type -Namespace SpwKey -Name Native -MemberDefinition @'
[DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, System.UIntPtr dwExtraInfo);
'@
[SpwKey.Native]::keybd_event([byte]$VK, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 60
[SpwKey.Native]::keybd_event([byte]$VK, 0, 2, [UIntPtr]::Zero)
Write-Output ("已发送媒体键 {0}（VK=0x{1:X2}），等待 {2}s" -f $Key, $VK, $WaitSeconds)

Start-Sleep -Seconds $WaitSeconds

if (-not $wasMuted) { [SpwAudio]::SetMute($false); Write-Output '已还原：不静音' }
Write-Output ("还原后：静音={0}" -f [SpwAudio]::GetMute())
