// 椒盐插件 · 内嵌安全验证宿主（0.11.34；批次 4 扩展：父窗身份核对 + stdin 命令协议）
// ---------------------------------------------------------------
// 把网易云的行为验证页用 WebView2 渲染出来，并作为【子窗口】塞进宿主窗口的预留区域，
// 用户全程不离开本软件、不出现任何浏览器外壳。
//
// 两种父窗模式：
//   1) 旧模式（--title，无 --parent-class）：FindWindowW("SunAwtDialog", title)，兼容旧 JDialog 承载。
//   2) 批次 4 模式（--parent-class + --pid [+ --parent-title]）：按「进程身份 + 窗口身份」核对，
//      候选必须唯一；不唯一 ⇒ parent-ambiguous 并退出（绝不取第一个同名窗口）。
//
// 协议（对面 Java 读 stdout 逐行解析；URL 只报长度，值不进日志）：
//   verify-host: start pid=<pid> session=<sid>
//   verify-host: selftest ok webview2=<版本>            （--selftest：只探运行时，不开窗）
//   verify-host: selftest fail <原因>
//   verify-host: parent <hwnd> class=<类名> pid=<pid> title-match=<0|1> count=<n> session=<sid>
//   verify-host: parent-timeout                          （超时未找到 ⇒ Java 走面板内失败 + 重试）
//   verify-host: parent-ambiguous count=<n>              （候选不唯一 ⇒ 拒绝挂载）
//   verify-host: parent-invalid <原因>                    （--parent-hwnd 指定的句柄核对失败）
//   verify-host: parent-client-mismatch got=WxH expect=WxH（客户区尺寸与宿主 Compose 窗口不符）
//   verify-host: adopted rect=x,y,w,h                    （已 SetParent 成子窗口；父窗模式 2 下隐藏等待 show）
//   verify-host: client w=<w> h=<h>                       （父窗客户区物理尺寸，供 Java 校正缩放）
//   verify-host: webview2 <运行时版本>
//   verify-host: ready                                   （渲染面已就绪）
//   verify-host: nav-start len=<地址长度>
//   verify-host: nav-done <状态> <错误码>
//   verify-host: cookie-in <项数>                         （--cookie 灌进去几项）
//   verify-host: cookies <k=v; k2=v2>                     （每次导航完回报 cookie，供 Java 按名合并）
//   verify-host: cookie-dump-failed <异常类名>
//   verify-host: window-close-requested                   （页面自己调 window.close()）
//   verify-host: parent-gone exit                         （宿主窗没了 ⇒ 自杀）
//   verify-host: fail <原因>
//
// stdin 命令（Java 侧 UTF-8 逐行；坐标是相对父窗客户区的物理像素，助手不做任何缩放）：
//   rect <x> <y> <w> <h>   → MoveWindow，回 rect-ack x,y,w,h
//   show                   → ShowWindow(SW_SHOWNA)，回 shown
//   hide                   → ShowWindow(SW_HIDE)，回 hidden
//   close                  → 回 closing 后有界退出
//   info                   → 回 info rect=.. visible=.. adopted=.. session=..
//
// 关键点：子窗口坐标是【相对宿主窗客户区】的；本机进程是 PerMonitorV2 感知，Win32 拿到/接受的都是物理像素，
// 缩放只在 Java 侧换算一次（不能两边各转一次，否则 125% 下会缩两次）。

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace VerifyHost
{
    internal static class Program
    {
        // ---------------- Win32 ----------------
        private delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr FindWindowW(string lpClassName, string lpWindowName);

        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr FindWindowExW(IntPtr parent, IntPtr childAfter, string className, string windowName);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern IntPtr SetParent(IntPtr hWndChild, IntPtr hWndNewParent);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern IntPtr GetParent(IntPtr hWnd);

        [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", SetLastError = true)]
        private static extern IntPtr GetWindowLongPtrW(IntPtr hWnd, int nIndex);

        [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", SetLastError = true)]
        private static extern IntPtr SetWindowLongPtrW(IntPtr hWnd, int nIndex, IntPtr dwNewLong);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool MoveWindow(IntPtr hWnd, int X, int Y, int nWidth, int nHeight, bool bRepaint);

        [DllImport("user32.dll")]
        private static extern bool IsWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern bool EnumWindows(EnumProc lpEnumFunc, IntPtr lParam);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetWindowTextW(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetClassNameW(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        [DllImport("user32.dll")]
        private static extern bool GetClientRect(IntPtr hWnd, out RECT lpRect);

        [StructLayout(LayoutKind.Sequential)]
        private struct RECT { public int Left, Top, Right, Bottom; }

        private const int GWL_STYLE = -16;
        private const int WS_CHILD = 0x40000000;
        private const int WS_POPUP = unchecked((int)0x80000000);
        private const int WS_VISIBLE = 0x10000000;
        private const int WS_CLIPCHILDREN = 0x02000000;
        private const int WS_CLIPSIBLINGS = 0x04000000;
        private const int SW_HIDE = 0;
        private const int SW_SHOWNA = 8;

        // ---------------- 参数 ----------------
        private sealed class Options
        {
            public string Title = "";
            public string Url = "";
            public int X, Y, W = 560, H = 420;
            public string DataDir = "";
            public int Pid = -1;
            public bool Selftest;
            public int ParentTimeoutMs = 10000;
            public string Cookie = "";                            // 插件侧的 Cookie 头（"--cookie" 传进来，按名灌进 WebView2）
            public int BackgroundRgb = 0x262626;                  // #262626 = HostTheme.CARD_BG
            public string Session = "";
            public long ParentHwnd = 0;                           // 0 = 按 pid+class(+title) 唯一定位
            public string ParentClass = "";                       // 非空 ⇒ 批次 4 严格模式
            public int ExpectClientW = 0, ExpectClientH = 0;      // 0 = 不核对
            public bool Strict => ParentHwnd != 0 || !string.IsNullOrEmpty(ParentClass);
            public Color BackgroundColor => Color.FromArgb(0xFF,
                    (BackgroundRgb >> 16) & 0xFF, (BackgroundRgb >> 8) & 0xFF, BackgroundRgb & 0xFF);
        }

        private static Options Parse(string[] args)
        {
            var o = new Options();
            for (int i = 0; i < args.Length; i++)
            {
                string a = args[i];
                string Next() => (i + 1 < args.Length) ? args[++i] : "";
                switch (a)
                {
                    case "--title": o.Title = Next(); break;
                    case "--url": o.Url = Next(); break;
                    case "--data-dir": o.DataDir = Next(); break;
                    case "--pid": int.TryParse(Next(), out o.Pid); break;
                    case "--timeout-ms": int.TryParse(Next(), out o.ParentTimeoutMs); break;
                    case "--bg":
                        if (int.TryParse(Next(), System.Globalization.NumberStyles.HexNumber, null, out int bgRgb))
                        {
                            o.BackgroundRgb = bgRgb & 0xFFFFFF;   // 只取 RRGGBB，alpha 固定 FF（否则 WinForms 报透明色异常）
                        }
                        break;
                    case "--selftest": o.Selftest = true; break;
                    case "--cookie": o.Cookie = Next(); break;
                    case "--session": o.Session = Next(); break;
                    case "--parent-class": o.ParentClass = Next(); break;
                    case "--parent-title": o.Title = Next(); break;
                    case "--parent-hwnd": long.TryParse(Next(), out o.ParentHwnd); break;
                    case "--expect-client":
                        var cw = Next().Split(',');
                        if (cw.Length == 2)
                        {
                            int.TryParse(cw[0], out o.ExpectClientW);
                            int.TryParse(cw[1], out o.ExpectClientH);
                        }
                        break;
                    case "--rect":
                        var parts = Next().Split(',');
                        if (parts.Length == 4)
                        {
                            int.TryParse(parts[0], out o.X);
                            int.TryParse(parts[1], out o.Y);
                            int.TryParse(parts[2], out o.W);
                            int.TryParse(parts[3], out o.H);
                        }
                        break;
                    default:
                        Say("ignore " + a);
                        break;
                }
            }
            return o;
        }

        private static readonly object SayLock = new object();
        private static Options Opt;

        private static void Say(string s)
        {
            lock (SayLock)
            {
                Console.WriteLine("verify-host: " + s);
                Console.Out.Flush();
            }
        }

        // ---------------- 共享状态（命令线程 / UI 线程） ----------------
        private static readonly object Gate = new object();
        private static Form _form;
        private static IntPtr _parent = IntPtr.Zero;
        private static volatile bool _adopted;
        private static volatile bool _ready;
        private static volatile bool _closing;
        private static bool _haveRect;
        private static int _rx, _ry, _rw, _rh;
        private static bool _wantVisible = true;
        private static bool _appliedVisible;
        private static bool _visibilityCommanded;

        // ---------------- 入口 ----------------
        [STAThread]
        private static int Main(string[] args)
        {
            try { Console.OutputEncoding = Encoding.UTF8; } catch { /* 无控制台 */ }
            var opt = Parse(args);
            Opt = opt;
            _wantVisible = !opt.Strict;      // 严格模式默认隐藏，等 Java 在布局就绪后发 show
            Say("start pid=" + Environment.ProcessId + (string.IsNullOrEmpty(opt.Session) ? "" : " session=" + opt.Session));

            if (opt.Selftest)
            {
                try
                {
                    string ver = CoreWebView2Environment.GetAvailableBrowserVersionString();
                    Say("selftest ok webview2=" + ver);
                    return 0;
                }
                catch (Exception ex)
                {
                    Say("selftest fail webview2 不可用：" + ex.GetType().Name + " " + ex.Message);
                    return 3;
                }
            }

            if (string.IsNullOrEmpty(opt.Url))
            {
                Say("fail 缺少 --url");
                return 2;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            var form = new Form
            {
                Text = "spw-verify-host",
                FormBorderStyle = FormBorderStyle.None,
                ShowInTaskbar = false,
                StartPosition = FormStartPosition.Manual,
                Location = new Point(-4000, -4000),      // 认领前待在屏外，避免闪一个裸窗
                Size = new Size(opt.W, opt.H),
                BackColor = opt.BackgroundColor,
                MinimizeBox = false,
                MaximizeBox = false,
                ControlBox = false,
                TopMost = false
            };
            _form = form;

            // 宿主窗销毁会先把子窗口一起销毁，消息循环直接结束 —— 这时必须自己回报 parent-gone，
            // 否则 Java 侧只能看到「进程没了」，分不清是宿主关窗还是助手崩溃。
            form.FormClosed += (s, e) =>
            {
                IntPtr parent;
                lock (Gate) { parent = _parent; }
                if (_adopted && !_closing)
                {
                    // 没有收到 close 命令窗体就没了：这是宿主把子窗口连带销毁（不是我们主动关的）
                    Say("parent-gone exit");
                }
            };

            var web = new WebView2 { Dock = DockStyle.Fill };
            web.DefaultBackgroundColor = opt.BackgroundColor;
            form.Controls.Add(web);

            StartCommandReader();

            form.Load += async (s, e) =>
            {
                // 1) 认领宿主窗（重试到超时；严格模式核对进程身份 + 窗口身份 + 唯一性）
                var sw = Stopwatch.StartNew();
                bool titleMatched = false, uniqueByClass = false;
                int candidateCount = 0;
                while (sw.ElapsedMilliseconds < opt.ParentTimeoutMs)
                {
                    if (TryResolveParent(opt, out IntPtr parent, out string cls, out int count, out bool byTitle, out bool soleByClass, out string reason))
                    {
                        _parent = parent;
                        titleMatched = byTitle;
                        uniqueByClass = soleByClass;
                        candidateCount = count;
                        Say("parent " + parent.ToInt64() + " class=" + cls
                            + (opt.Pid > 0 ? " pid=" + opt.Pid : "")
                            + " title-match=" + (byTitle ? "1" : "0")
                            + " count=" + count
                            + (string.IsNullOrEmpty(opt.Session) ? "" : " session=" + opt.Session));
                        break;
                    }
                    if (count >= 2)
                    {
                        Say("parent-ambiguous count=" + count);
                        Application.Exit();
                        return;
                    }
                    if (!string.IsNullOrEmpty(reason))
                    {
                        Say("parent-invalid " + reason);
                        Application.Exit();
                        return;
                    }
                    await System.Threading.Tasks.Task.Delay(200);
                }
                if (_parent == IntPtr.Zero)
                {
                    Say("parent-timeout");
                    Application.Exit();
                    return;
                }

                // 2) 变成宿主窗的子窗口（跨进程 SetParent；去掉 WS_POPUP、加 WS_CHILD）
                try
                {
                    SetParent(form.Handle, _parent);
                    long style = GetWindowLongPtrW(form.Handle, GWL_STYLE).ToInt64();
                    style &= ~(long)WS_POPUP;
                    style |= WS_CHILD | WS_CLIPCHILDREN | WS_CLIPSIBLINGS;
                    if (!opt.Strict) style |= WS_VISIBLE;   // 严格模式等 Java 的 show 命令，避免未定位先闪
                    SetWindowLongPtrW(form.Handle, GWL_STYLE, new IntPtr(style));
                    long pstyle = GetWindowLongPtrW(_parent, GWL_STYLE).ToInt64();
                    SetWindowLongPtrW(_parent, GWL_STYLE, new IntPtr(pstyle | WS_CLIPCHILDREN));

                    RECT cr;
                    GetClientRect(_parent, out cr);
                    int clientW = cr.Right - cr.Left, clientH = cr.Bottom - cr.Top;
                    if (!opt.Strict)
                    {
                        MoveWindow(form.Handle, opt.X, opt.Y, opt.W, opt.H, true);
                    }
                    else
                    {
                        MoveWindow(form.Handle, 0, 0, 1, 1, false);
                    }
                    _adopted = true;
                    _appliedVisible = !opt.Strict;   // 旧模式采用时已置 WS_VISIBLE；严格模式仍隐藏
                    Say("adopted rect=" + (opt.Strict ? "0,0,1,1" : (opt.X + "," + opt.Y + "," + opt.W + "," + opt.H)));
                    Say("client w=" + clientW + " h=" + clientH);

                    if (opt.Strict && opt.ExpectClientW > 0 && opt.ExpectClientH > 0
                        && (Math.Abs(clientW - opt.ExpectClientW) > 12 || Math.Abs(clientH - opt.ExpectClientH) > 12))
                    {
                        Say("parent-client-mismatch got=" + clientW + "x" + clientH
                            + " expect=" + opt.ExpectClientW + "x" + opt.ExpectClientH);
                        Application.Exit();
                        return;
                    }
                    FlushPending();
                }
                catch (Exception ex)
                {
                    Say("fail SetParent 失败：" + ex.GetType().Name + " " + ex.Message);
                    Application.Exit();
                    return;
                }

                // 3) 起 WebView2（Chromium）并导航
                try
                {
                    string dataDir = string.IsNullOrEmpty(opt.DataDir)
                        ? Path.Combine(Path.GetTempPath(), "spw-verify-host")
                        : opt.DataDir;
                    Directory.CreateDirectory(dataDir);
                    var env = await CoreWebView2Environment.CreateAsync(null, dataDir);
                    await web.EnsureCoreWebView2Async(env);

                    var core = web.CoreWebView2;
                    Say("webview2 " + (core?.Environment?.BrowserVersionString ?? "?"));
                    core.Settings.AreDefaultContextMenusEnabled = true;
                    core.Settings.IsStatusBarEnabled = false;
                    core.Settings.AreDevToolsEnabled = false;
                    core.WindowCloseRequested += (_, __) => Say("window-close-requested");
                    core.NewWindowRequested += (_, ev) =>
                    {
                        ev.Handled = true;                 // 同窗打开，不弹新窗
                        try { core.Navigate(ev.Uri); } catch { }
                    };

                    // 插件登录态灌进来（网易云看到的必须与插件同源设备，否则验证页等于「另一台机器」）。
                    // 域名取 URL 自己的 host ⇒ 直接落在目标域上，不需要猜 .music.163.com 之类的后缀。
                    int seeded = SeedCookies(core, opt);
                    if (seeded > 0) Say("cookie-in " + seeded);

                    core.NavigationCompleted += (_, ev) =>
                    {
                        Say("nav-done " + (ev.IsSuccess ? "true" : "false") + " " + ev.WebErrorStatus);
                        _ = DumpCookies(core, opt);        // 验证页写了什么 cookie（Java 侧按名合并回 jar）
                    };

                    Say("nav-start len=" + opt.Url.Length);
                    core.Navigate(opt.Url);
                    _ready = true;
                    Say("ready");
                }
                catch (Exception ex)
                {
                    Say("fail webview2 初始化失败：" + ex.GetType().Name + " " + ex.Message);
                    Application.Exit();
                }
            };

            // 4) 宿主窗没了就自杀（子窗口会随宿主销毁，进程要主动退出）。
            //    用独立后台线程而不是 WinForms Timer：宿主窗销毁会连带销毁本窗的子窗口，
            //    消息循环可能先退出，Timer 再也收不到 tick —— 那就不会回报 parent-gone 了。
            var watch = new Thread(() =>
            {
                while (true)
                {
                    Thread.Sleep(200);
                    if (!_adopted) continue;
                    IntPtr parent;
                    lock (Gate) { parent = _parent; }
                    if (parent != IntPtr.Zero && !IsWindow(parent))
                    {
                        Say("parent-gone exit");
                        try { Environment.Exit(0); } catch { }
                    }
                }
            })
            { IsBackground = true, Name = "verify-host-parent-watch" };
            watch.Start();

            Application.Run(form);
            if (_adopted && !_closing)
            {
                // 消息循环被宿主销毁打断（子窗口随父窗销毁）——明确回报，别让 Java 猜
                Say("parent-gone exit");
            }
            return 0;
        }

        // ---------------- stdin 命令 ----------------
        private static void StartCommandReader()
        {
            var t = new Thread(() =>
            {
                try
                {
                    var stdin = new StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false));
                    string line;
                    while ((line = stdin.ReadLine()) != null)
                    {
                        HandleCommand(line.Trim());
                    }
                }
                catch
                {
                    // 读不到 stdin（无控制台）就静默；Java 侧仍能靠 stdout 工作
                }
                if (!_closing)
                {
                    Say("stdin-eof exit");
                    try { Environment.Exit(0); } catch { }
                }
            })
            { IsBackground = true, Name = "verify-host-cmd" };
            t.Start();
        }

        private static void HandleCommand(string line)
        {
            if (string.IsNullOrEmpty(line)) return;
            string[] p = line.Split(' ');
            switch (p[0])
            {
                case "rect":
                    if (p.Length == 5 && int.TryParse(p[1], out int x) && int.TryParse(p[2], out int y)
                        && int.TryParse(p[3], out int w) && int.TryParse(p[4], out int h))
                    {
                        lock (Gate)
                        {
                            _rx = x; _ry = y; _rw = w; _rh = h; _haveRect = true;
                        }
                        BeginUI(ApplyRect);
                    }
                    else
                    {
                        Say("rect-invalid " + line);
                    }
                    break;
                case "show":
                case "hide":
                    lock (Gate) { _wantVisible = p[0] == "show"; _visibilityCommanded = true; }
                    BeginUI(ApplyVisible);
                    break;
                case "close":
                    _closing = true;
                    Say("closing");
                    BeginUI(() => Application.Exit());
                    break;
                case "info":
                    Say("info rect=" + _rx + "," + _ry + "," + _rw + "," + _rh
                        + " visible=" + (_appliedVisible ? "true" : "false")
                        + " adopted=" + (_adopted ? "true" : "false")
                        + " ready=" + (_ready ? "true" : "false")
                        + (string.IsNullOrEmpty(Opt.Session) ? "" : " session=" + Opt.Session));
                    break;
                default:
                    Say("ignore-cmd " + p[0]);
                    break;
            }
        }

        private static void BeginUI(Action a)
        {
            var f = _form;
            if (f == null || !_adopted) return;            // 未认领前由 FlushPending 统一应用
            try
            {
                if (f.IsHandleCreated) f.BeginInvoke(a);
            }
            catch
            {
                // 窗口正在销毁：忽略
            }
        }

        /// <summary>认领成功后把早到的 rect/show/hide 命令补一次（布局驱动的最终状态）。</summary>
        private static void FlushPending()
        {
            bool have, commanded;
            lock (Gate)
            {
                have = _haveRect;
                commanded = _visibilityCommanded;
            }
            if (have) ApplyRect();
            if (commanded) ApplyVisible();        // 早到的 show/hide 补一次；没命令就保持默认（严格模式隐藏）
        }

        private static void ApplyRect()
        {
            int x, y, w, h;
            lock (Gate) { x = _rx; y = _ry; w = _rw; h = _rh; }
            if (!ValidateParent()) return;
            if (w <= 0 || h <= 0) return;
            try
            {
                MoveWindow(_form.Handle, x, y, w, h, true);
                Say("rect-ack " + x + "," + y + "," + w + "," + h);
            }
            catch (Exception ex)
            {
                Say("fail MoveWindow：" + ex.GetType().Name + " " + ex.Message);
            }
        }

        private static void ApplyVisible()
        {
            bool vis;
            lock (Gate) { vis = _wantVisible; }
            if (!ValidateParent()) return;
            if (_appliedVisible == vis) return;
            try
            {
                ShowWindow(_form.Handle, vis ? SW_SHOWNA : SW_HIDE);
                _appliedVisible = vis;
                Say(vis ? "shown" : "hidden");
            }
            catch (Exception ex)
            {
                Say("fail ShowWindow：" + ex.GetType().Name + " " + ex.Message);
            }
        }

        private static bool ValidateParent()
        {
            if (_parent != IntPtr.Zero && !IsWindow(_parent))
            {
                if (!_closing)
                {
                    _closing = true;
                    Say("parent-gone exit");
                    try { Application.Exit(); } catch { }
                }
                return false;
            }
            return true;
        }

        /// <summary>URL 的 host（灌/取 cookie 都用它当 domain）；解析失败给空串。</summary>
        private static string HostOf(string url)
        {
            try { return new Uri(url).Host; } catch { return ""; }
        }

        /// <summary>
        /// 把插件侧 Cookie 头按名灌进 WebView2。返回灌进去的项数（0 = 没带 cookie 或全失败）。
        /// 逐项解析，单项失败不影响其它项；值不打印（只有项数）。
        /// </summary>
        private static int SeedCookies(CoreWebView2 core, Options opt)
        {
            if (core == null || string.IsNullOrWhiteSpace(opt.Cookie)) return 0;
            string host = HostOf(opt.Url);
            if (string.IsNullOrEmpty(host)) return 0;
            int n = 0;
            foreach (string raw in opt.Cookie.Split(';'))
            {
                string item = raw.Trim();
                int eq = item.IndexOf('=');
                if (eq <= 0) continue;                       // 没名字的跳过（值可以为空）
                try
                {
                    string name = item.Substring(0, eq).Trim();
                    string value = item.Substring(eq + 1);
                    var c = core.CookieManager.CreateCookie(name, value, host, "/");
                    c.IsHttpOnly = false;                    // 插件 jar 里的 HttpOnly 只影响 JS 可见性，这里放开
                    c.IsSecure = true;                       // 验证页是 https
                    c.Expires = DateTime.Now.AddDays(30);    // 设了 Expires 就不再是会话 cookie（IsSession 只读，设完自动变 false）
                    core.CookieManager.AddOrUpdateCookie(c);
                    n++;
                }
                catch
                {
                    // 单项失败就跳过：网易云认不认这项由服务端决定，助手不因一项坏掉就整个失败
                }
            }
            return n;
        }

        /// <summary>把 WebView2 侧当前 cookie 回报给 Java（格式 "<cookies k=v; k2=v2>"），供按名合并回插件 jar。</summary>
        private static async System.Threading.Tasks.Task DumpCookies(CoreWebView2 core, Options opt)
        {
            try
            {
                if (core == null) return;
                string host = HostOf(opt.Url);
                if (string.IsNullOrEmpty(host)) return;
                var list = await core.CookieManager.GetCookiesAsync(host);
                var sb = new System.Text.StringBuilder();
                foreach (var c in list)
                {
                    if (sb.Length > 0) sb.Append("; ");
                    sb.Append(c.Name).Append('=').Append(c.Value);
                }
                Say("cookies " + sb);
            }
            catch (Exception ex)
            {
                Say("cookie-dump-failed " + ex.GetType().Name);
            }
        }

        /// <summary>
        /// 定位父窗。
        /// 严格模式：--parent-hwnd 精确核对；否则枚举该 pid 的可见顶层同 class 窗口，
        /// 先按标题唯一命中，其次「该 pid 只有这一个同 class 顶层窗」也算唯一；
        /// 出现 ≥2 个候选且标题无法唯一定位 ⇒ count 返回给调用方拒绝挂载。
        /// </summary>
        private static bool TryResolveParent(Options opt, out IntPtr hwnd, out string cls,
            out int count, out bool byTitle, out bool soleByClass, out string reason)
        {
            hwnd = IntPtr.Zero;
            cls = "";
            count = 0;
            byTitle = false;
            soleByClass = false;
            reason = "";

            if (opt.ParentHwnd != 0)
            {
                var h = new IntPtr(opt.ParentHwnd);
                if (!IsWindow(h)) { reason = "hwnd 不存在"; return false; }
                if (GetParent(h) != IntPtr.Zero) { reason = "hwnd 不是顶层窗口"; return false; }
                if (opt.Pid > 0)
                {
                    uint pid;
                    GetWindowThreadProcessId(h, out pid);
                    if (pid != (uint)opt.Pid) { reason = "hwnd 属于其它进程"; return false; }
                }
                string c0 = ClassOf(h);
                if (!string.IsNullOrEmpty(opt.ParentClass) && !string.Equals(c0, opt.ParentClass, StringComparison.Ordinal))
                {
                    reason = "窗口类名不是 " + opt.ParentClass;
                    return false;
                }
                if (!IsWindowVisible(h)) { reason = "hwnd 不可见"; return false; }
                hwnd = h;
                cls = c0;
                count = 1;
                byTitle = true;
                return true;
            }

            if (!opt.Strict)
            {
                // 旧模式：按标题找 SunAwtDialog，兼容旧 JDialog 承载
                if (!string.IsNullOrEmpty(opt.Title))
                {
                    IntPtr h = FindWindowW("SunAwtDialog", opt.Title);
                    if (h == IntPtr.Zero) h = FindWindowW(null, opt.Title);
                    if (h == IntPtr.Zero && !string.IsNullOrEmpty(opt.DataDir))
                    {
                        h = FindWindowExW(IntPtr.Zero, IntPtr.Zero, "SunAwtDialog", opt.Title);
                    }
                    if (h != IntPtr.Zero)
                    {
                        hwnd = h;
                        cls = ClassOf(h);
                        count = 1;
                        byTitle = true;
                        return true;
                    }
                }
                if (opt.Pid > 0)
                {
                    IntPtr best = IntPtr.Zero;
                    EnumWindows((h, l) =>
                    {
                        uint pid;
                        GetWindowThreadProcessId(h, out pid);
                        if (pid != (uint)opt.Pid) return true;
                        if (!IsWindowVisible(h)) return true;
                        if (!string.Equals(ClassOf(h), "SunAwtDialog", StringComparison.Ordinal)) return true;
                        best = h;          // EnumWindows 按 z 序自上而下 ⇒ 取第一个命中的
                        return false;
                    }, IntPtr.Zero);
                    if (best != IntPtr.Zero)
                    {
                        hwnd = best;
                        cls = ClassOf(best);
                        count = 1;
                        return true;
                    }
                }
                return false;
            }

            // 严格模式：pid + class 的顶层可见窗
            var all = new List<IntPtr>();
            var byName = new List<IntPtr>();
            EnumWindows((h, l) =>
            {
                if (GetParent(h) != IntPtr.Zero) return true;
                uint pid;
                GetWindowThreadProcessId(h, out pid);
                if (opt.Pid > 0 && pid != (uint)opt.Pid) return true;
                if (!IsWindowVisible(h)) return true;
                if (!string.Equals(ClassOf(h), opt.ParentClass, StringComparison.Ordinal)) return true;
                all.Add(h);
                if (!string.IsNullOrEmpty(opt.Title) && string.Equals(TitleOf(h), opt.Title, StringComparison.Ordinal))
                {
                    byName.Add(h);
                }
                return true;
            }, IntPtr.Zero);

            count = all.Count;
            if (byName.Count == 1)
            {
                hwnd = byName[0];
                cls = opt.ParentClass;
                byTitle = true;
                return true;
            }
            if (byName.Count >= 2)
            {
                count = byName.Count;
                return false;
            }
            if (all.Count == 1)
            {
                hwnd = all[0];
                cls = opt.ParentClass;
                soleByClass = true;
                return true;
            }
            return false;
        }

        private static string ClassOf(IntPtr h)
        {
            var sb = new StringBuilder(256);
            GetClassNameW(h, sb, sb.Capacity);
            return sb.ToString();
        }

        private static string TitleOf(IntPtr h)
        {
            var sb = new StringBuilder(512);
            GetWindowTextW(h, sb, sb.Capacity);
            return sb.ToString();
        }
    }
}
