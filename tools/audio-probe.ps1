<#
音频会话峰值探针（CoreAudio IAudioMeterInformation）
────────────────────────────────────────────────────
用途：回答本项目反复出现的那个只有耳朵能答的问题——「宿主到底有没有在出声」。
原理：Windows 混音器按进程维护音频会话，IAudioMeterInformation.GetPeakValue()
      给的是该会话**送进混音器**的峰值（0..1）。峰值 > 0 = 该进程正在产出音频数据，
      与「播放器状态枚举里有没有 Playing」（宿主根本没有这个值）无关，也不受
      系统音量大小影响（注意：把该会话静音仍可能读到峰值，所以它证明的是
      「数据在往声卡送」，而不是「音箱一定在响」——音量/设备路由仍需耳朵）。

用法：
  pwsh -NoProfile -File tools\audio-probe.ps1 -ProcessName 'Salt Player for Windows'
  pwsh -NoProfile -File tools\audio-probe.ps1 -ProcessId 12940 -Seconds 6
#>
[CmdletBinding()]
param(
    [string]$ProcessName = 'Salt Player for Windows',
    [int]$ProcessId = 0,
    [int]$Seconds = 5,
    [int]$IntervalMs = 200,
    # 逐样本打印 + 波动统计：用来区分「音乐（峰值大幅波动）」与「恒定底噪（疑似假阳性）」
    [switch]$ShowSamples
)

$ErrorActionPreference = 'Stop'

if (-not ('SpwAudioProbe.Meter' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace SpwAudioProbe
{
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    internal class MMDeviceEnumeratorComObject { }

    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
        int GetDevice(string id, out IMMDevice device);
        int RegisterEndpointNotificationCallback(IntPtr client);
        int UnregisterEndpointNotificationCallback(IntPtr client);
    }

    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDevice
    {
        int Activate(ref Guid iid, int clsCtx, IntPtr activationParams,
                     [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        int OpenPropertyStore(int access, out IntPtr props);
        int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetState(out int state);
    }

    [Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioSessionManager2
    {
        int GetAudioSessionControl(ref Guid sessionGuid, int streamFlags, out IntPtr sessionControl);
        int GetSimpleAudioVolume(ref Guid sessionGuid, int streamFlags, out IntPtr audioVolume);
        int GetSessionEnumerator(out IAudioSessionEnumerator sessionEnum);
        int RegisterSessionNotification(IntPtr sessionNotification);
        int UnregisterSessionNotification(IntPtr sessionNotification);
        int RegisterDuckNotification(string sessionID, IntPtr duckNotification);
        int UnregisterDuckNotification(IntPtr duckNotification);
    }

    [Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioSessionEnumerator
    {
        int GetCount(out int sessionCount);
        int GetSession(int sessionIndex, out IAudioSessionControl2 session);
    }

    [Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioSessionControl2
    {
        int GetState(out int state);
        int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);
        int SetDisplayName(string value, ref Guid eventContext);
        int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);
        int SetIconPath(string value, ref Guid eventContext);
        int GetGroupingParam(out Guid groupingParam);
        int SetGroupingParam(ref Guid groupingParam, ref Guid eventContext);
        int RegisterAudioSessionNotification(IntPtr newNotifications);
        int UnregisterAudioSessionNotification(IntPtr newNotifications);
        int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetProcessId(out int pid);
        int IsSystemSoundsSession();
        int SetDuckingPreference(bool optOut);
    }

    [Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioMeterInformation
    {
        int GetPeakValue(out float peak);
        int GetMeteringChannelCount(out int count);
        int GetChannelsPeakValues(int count, [Out] float[] peaks);
        int QueryHardwareSupport(out int mask);
    }

    // 会话音量/静音：用来解释「峰值很小」到底是「没在放」还是「在放但被调得很小声」。
    // 注意：应用自己内部改音量（WASAPI 客户端自己的增益）这里读不出来，读到的只是混音器上的会话音量。
    [Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface ISimpleAudioVolume
    {
        int SetMasterVolume(float level, ref Guid eventContext);
        int GetMasterVolume(out float level);
        int SetMute(bool mute, ref Guid eventContext);
        int GetMute(out bool mute);
    }

    // 默认播放设备的**总音量**（系统喇叭音量）：解释「有数据但听不见」的第一嫌疑。
    // vtable 顺序必须与 Windows SDK 一致，前面用不到的方法也得占位，否则调用会串槽。
    [Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioEndpointVolume
    {
        int RegisterControlChangeNotify(IntPtr notify);
        int UnregisterControlChangeNotify(IntPtr notify);
        int GetChannelCount(out int count);
        int SetMasterVolumeLevel(float level, ref Guid eventContext);
        int SetMasterVolumeLevelScalar(float level, ref Guid eventContext);
        int GetMasterVolumeLevel(out float level);
        int GetMasterVolumeLevelScalar(out float level);
        int SetChannelVolumeLevel(int channel, float level, ref Guid eventContext);
        int SetChannelVolumeLevelScalar(int channel, float level, ref Guid eventContext);
        int GetChannelVolumeLevel(int channel, out float level);
        int GetChannelVolumeLevelScalar(int channel, out float level);
        int SetMute(bool mute, ref Guid eventContext);
        int GetMute(out bool mute);
    }

    public sealed class SessionInfo
    {
        public int ProcessId;
        public int State;          // 0=Inactive 1=Active 2=Expired
        public string DisplayName;
        public string InstanceId;
    }

    public static class Meter
    {
        /// <summary>枚举默认播放设备的所有音频会话（进程级）。</summary>
        public static SessionInfo[] Sessions()
        {
            var list = new List<SessionInfo>();
            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
            IMMDevice dev;
            // eRender=0, eMultimedia=1
            Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out dev));
            Guid iid = typeof(IAudioSessionManager2).GUID;
            object mgrObj;
            Marshal.ThrowExceptionForHR(dev.Activate(ref iid, 23 /*CLSCTX_ALL*/, IntPtr.Zero, out mgrObj));
            var mgr = (IAudioSessionManager2)mgrObj;
            IAudioSessionEnumerator sessions;
            Marshal.ThrowExceptionForHR(mgr.GetSessionEnumerator(out sessions));
            int count;
            Marshal.ThrowExceptionForHR(sessions.GetCount(out count));
            for (int i = 0; i < count; i++)
            {
                IAudioSessionControl2 ctl;
                if (sessions.GetSession(i, out ctl) != 0 || ctl == null) continue;
                var info = new SessionInfo();
                ctl.GetProcessId(out info.ProcessId);
                ctl.GetState(out info.State);
                try { ctl.GetDisplayName(out info.DisplayName); } catch { info.DisplayName = null; }
                try { ctl.GetSessionInstanceIdentifier(out info.InstanceId); } catch { info.InstanceId = null; }
                list.Add(info);
            }
            return list.ToArray();
        }

        /// <summary>某个进程当前的会话峰值（0..1）；没有会话时返回 -1。</summary>
        public static float PeakForProcess(int pid)
        {
            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
            IMMDevice dev;
            Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out dev));
            Guid iid = typeof(IAudioSessionManager2).GUID;
            object mgrObj;
            Marshal.ThrowExceptionForHR(dev.Activate(ref iid, 23, IntPtr.Zero, out mgrObj));
            var mgr = (IAudioSessionManager2)mgrObj;
            IAudioSessionEnumerator sessions;
            Marshal.ThrowExceptionForHR(mgr.GetSessionEnumerator(out sessions));
            int count;
            Marshal.ThrowExceptionForHR(sessions.GetCount(out count));
            for (int i = 0; i < count; i++)
            {
                IAudioSessionControl2 ctl;
                if (sessions.GetSession(i, out ctl) != 0 || ctl == null) continue;
                int sid;
                ctl.GetProcessId(out sid);
                if (sid != pid) continue;
                var meter = ctl as IAudioMeterInformation;
                if (meter == null) continue;
                float peak;
                if (meter.GetPeakValue(out peak) != 0) continue;
                return peak;
            }
            return -1f;
        }

        /// <summary>某个进程的会话音量/静音；取不到返回 null。</summary>
        public static float[] VolumeForProcess(int pid)
        {
            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
            IMMDevice dev;
            Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out dev));
            Guid iid = typeof(IAudioSessionManager2).GUID;
            object mgrObj;
            Marshal.ThrowExceptionForHR(dev.Activate(ref iid, 23, IntPtr.Zero, out mgrObj));
            var mgr = (IAudioSessionManager2)mgrObj;
            IAudioSessionEnumerator sessions;
            Marshal.ThrowExceptionForHR(mgr.GetSessionEnumerator(out sessions));
            int count;
            Marshal.ThrowExceptionForHR(sessions.GetCount(out count));
            for (int i = 0; i < count; i++)
            {
                IAudioSessionControl2 ctl;
                if (sessions.GetSession(i, out ctl) != 0 || ctl == null) continue;
                int sid;
                ctl.GetProcessId(out sid);
                if (sid != pid) continue;
                var vol = ctl as ISimpleAudioVolume;
                if (vol == null) continue;
                float level; bool mute;
                if (vol.GetMasterVolume(out level) != 0) continue;
                if (vol.GetMute(out mute) != 0) continue;
                return new float[] { level, mute ? 1f : 0f };
            }
            return null;
        }

        /// <summary>默认播放设备的总音量/静音：[scalar(0..1), mute(0/1)]；取不到返回 null。</summary>
        public static float[] EndpointVolume()
        {
            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
            IMMDevice dev;
            Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out dev));
            Guid iid = typeof(IAudioEndpointVolume).GUID;
            object volObj;
            Marshal.ThrowExceptionForHR(dev.Activate(ref iid, 23, IntPtr.Zero, out volObj));
            var vol = (IAudioEndpointVolume)volObj;
            float level; bool mute;
            if (vol.GetMasterVolumeLevelScalar(out level) != 0) return null;
            if (vol.GetMute(out mute) != 0) return null;
            return new float[] { level, mute ? 1f : 0f };
        }

        /// <summary>
        /// 某个进程的**全部**会话快照（关键：同一进程可能同时有多条会话，比如「正在放歌的那条」和
        /// 「保活的静音流」；只看第一条会读错对象，把有声音的进程判成静音）。
        /// 每行 = [state, peak, volume, mute]；peak/volume/mute 取不到时给 -1。
        /// </summary>
        public static float[][] SnapshotForProcess(int pid)
        {
            var rows = new System.Collections.Generic.List<float[]>();
            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
            IMMDevice dev;
            Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out dev));
            Guid iid = typeof(IAudioSessionManager2).GUID;
            object mgrObj;
            Marshal.ThrowExceptionForHR(dev.Activate(ref iid, 23, IntPtr.Zero, out mgrObj));
            var mgr = (IAudioSessionManager2)mgrObj;
            IAudioSessionEnumerator sessions;
            Marshal.ThrowExceptionForHR(mgr.GetSessionEnumerator(out sessions));
            int count;
            Marshal.ThrowExceptionForHR(sessions.GetCount(out count));
            for (int i = 0; i < count; i++)
            {
                IAudioSessionControl2 ctl;
                if (sessions.GetSession(i, out ctl) != 0 || ctl == null) continue;
                int sid;
                ctl.GetProcessId(out sid);
                if (sid != pid) continue;
                int state = -1;
                ctl.GetState(out state);
                float peak = -1f;
                var meter = ctl as IAudioMeterInformation;
                if (meter != null) { float pk; if (meter.GetPeakValue(out pk) == 0) peak = pk; }
                float level = -1f, mute = -1f;
                var vol = ctl as ISimpleAudioVolume;
                if (vol != null)
                {
                    float lv; bool mu;
                    if (vol.GetMasterVolume(out lv) == 0) level = lv;
                    if (vol.GetMute(out mu) == 0) mute = mu ? 1f : 0f;
                }
                rows.Add(new float[] { (float)state, peak, level, mute });
            }
            return rows.ToArray();
        }
    }
}
'@
}

$targets = @()
if ($ProcessId -gt 0) {
    $targets = @(Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)
} else {
    $targets = @(Get-Process -Name $ProcessName -ErrorAction SilentlyContinue)
}
if ($targets.Count -eq 0) {
    Write-Host "[audio-probe] 没找到目标进程（$ProcessName / PID=$ProcessId）" -ForegroundColor Yellow
    exit 2
}

Write-Host ("[audio-probe] 默认播放设备的音频会话：" ) -ForegroundColor DarkGray
$ep = [SpwAudioProbe.Meter]::EndpointVolume()
if ($ep -ne $null) {
    '  设备总音量={0:N3} 设备静音={1}（系统喇叭音量；这里也满不代表能听见，应用内部音量另算）' -f $ep[0], ($ep[1] -eq 1)
} else {
    '  设备总音量：取不到'
}
foreach ($s in [SpwAudioProbe.Meter]::Sessions()) {
    $nm = try { (Get-Process -Id $s.ProcessId -ErrorAction Stop).ProcessName } catch { '<已退出>' }
    Write-Host ("  pid={0,-7} state={1} proc={2}" -f $s.ProcessId, $s.State, $nm) -ForegroundColor DarkGray
}

$pidList = ($targets | ForEach-Object { $_.Id })
foreach ($p in $targets) {
    '--- PID {0} ({1}) 峰值采样 {2}s（逐会话） ---' -f $p.Id, $p.ProcessName, $Seconds
    $stats = @{}
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $rows = @([SpwAudioProbe.Meter]::SnapshotForProcess($p.Id))
        for ($i = 0; $i -lt $rows.Count; $i++) {
            $row = $rows[$i]
            if (-not $stats.ContainsKey($i)) {
                $stats[$i] = @{ max = -1.0; min = 2.0; samples = 0; nonzero = 0; sum = 0.0; jumps = 0; prev = -2.0 }
            }
            $s = $stats[$i]
            $s.state = $row[0]; $s.vol = $row[2]; $s.mute = $row[3]
            $peak = $row[1]
            if ($peak -ge 0) {
                $s.samples++
                if ($peak -gt 0.0001) { $s.nonzero++ }
                if ($peak -gt $s.max) { $s.max = $peak }
                if ($peak -lt $s.min) { $s.min = $peak }
                $s.sum += $peak
                # 波动次数：相邻样本变化幅度 > 0.001 即记一次（音乐几乎每拍都在变，底噪几乎不变）
                if ($s.prev -gt -1.5 -and [Math]::Abs($peak - $s.prev) -gt 0.001) { $s.jumps++ }
                $s.prev = $peak
                if ($ShowSamples) { '    会话#{0} {1:N5}' -f $i, $peak }
            }
        }
        Start-Sleep -Milliseconds $IntervalMs
    }
    if ($stats.Count -eq 0) {
        '  结论：没有音频会话（该进程尚未向混音器送过数据）'
        continue
    }
    $bestJumps = -1
    foreach ($k in ($stats.Keys | Sort-Object)) {
        $s = $stats[$k]
        $volTxt = if ($s.vol -ge 0) { '{0:N3}' -f $s.vol } else { '取不到' }
        $muteTxt = if ($s.mute -lt 0) { '取不到' } elseif ($s.mute -gt 0) { 'True' } else { 'False' }
        if ($s.samples -eq 0) {
            '  会话#{0} state={1} 会话音量={2} 静音={3}: 峰值取不到（无 meter）' -f $k, $s.state, $volTxt, $muteTxt
            continue
        }
        $avg = $s.sum / $s.samples
        '  会话#{0} state={1} 会话音量={2} 静音={3} | 采样 {4} 次，峰值 min={5:N4} max={6:N4} 均值={7:N4}，非零 {8} 次，波动 {9}/{10} 次' -f `
            $k, $s.state, $volTxt, $muteTxt, $s.samples, $s.min, $s.max, $avg, $s.nonzero, $s.jumps, ($s.samples - 1)
        if ($s.max -le 0.0001) {
            '        该会话：峰值恒为 0 → 没往混音器送数据（静音流/未播放）'
        } elseif ($s.jumps -le 1) {
            '        该会话：电平恒定（波动仅 {0} 次）⇒ 更像恒定底噪/静音流，不像音乐' -f $s.jumps
        } else {
            '        该会话：电平在波动 → **像在真实输出音频**'
        }
        if ($s.jumps -gt $bestJumps) { $bestJumps = $s.jumps }
    }
    if ($bestJumps -le 1) {
        '  结论：**没有任何会话在波动** ⇒ 混音器上看不到音乐；要么真没出声，要么宿主走了混音器测不到的输出路径（对照宿主日志 `logs.txt` 里的流开合周期）。'
    } else {
        '  结论：**至少一条会话在真实输出音频（在出声的路上）**。'
    }
}
