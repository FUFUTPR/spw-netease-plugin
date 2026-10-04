// 椒盐插件 · 内嵌安全验证宿主（0.11.34）
// ---------------------------------------------------------------
// 把网易云的行为验证页用 WebView2 渲染出来，并作为【子窗口】塞进插件自绘的验证窗，
// 用户全程不离开本软件、不出现任何浏览器外壳。
//
// 协议（对面 Java 读 stdout 逐行解析；URL 只报长度，值不进日志）：
//   verify-host: start pid=<pid>
//   verify-host: selftest ok webview2=<版本>            （--selftest：只探运行时，不开窗）
//   verify-host: selftest fail <原因>
//   verify-host: parent <hwnd> class=<类名>             （找到宿主窗）
//   verify-host: parent-timeout                          （超时未找到 ⇒ Java 走兜底）
//   verify-host: adopted rect=x,y,w,h                    （已 SetParent 成子窗口）
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
// 关键点：子窗口坐标是【相对宿主窗客户区】的，SetParent 之后宿主窗移动它自动跟随，
// 因此 Java 侧不需要任何原生调用（不用 JNA/FFM），只需摆放自己的 Swing 窗。

using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
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

        private const int GWL_STYLE = -16;
        private const int GWL_EXSTYLE = -20;
        private const int WS_CHILD = 0x40000000;
        private const int WS_POPUP = unchecked((int)0x80000000);
        private const int WS_VISIBLE = 0x10000000;
        private const int WS_CLIPCHILDREN = 0x02000000;
        private const int WS_CLIPSIBLINGS = 0x04000000;
        private const int WS_EX_NOACTIVATE = 0x08000000;

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
                        Console.WriteLine("verify-host: ignore " + a);
                        break;
                }
            }
            return o;
        }

        private static void Say(string s)
        {
            Console.WriteLine("verify-host: " + s);
            Console.Out.Flush();
        }

        // ---------------- 入口 ----------------
        [STAThread]
        private static int Main(string[] args)
        {
            try { Console.OutputEncoding = Encoding.UTF8; } catch { /* 无控制台 */ }
            var opt = Parse(args);
            Say("start pid=" + Environment.ProcessId);

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

            var web = new WebView2 { Dock = DockStyle.Fill };
            web.DefaultBackgroundColor = opt.BackgroundColor;
            form.Controls.Add(web);

            IntPtr parent = IntPtr.Zero;
            string parentClass = "";
            bool ready = false;

            form.Load += async (s, e) =>
            {
                // 1) 认领宿主窗（重试到超时）
                var sw = Stopwatch.StartNew();
                while (sw.ElapsedMilliseconds < opt.ParentTimeoutMs)
                {
                    if (TryFindParent(opt, out parent, out parentClass)) break;
                    await System.Threading.Tasks.Task.Delay(200);
                }
                if (parent == IntPtr.Zero)
                {
                    Say("parent-timeout");
                    Application.Exit();
                    return;
                }
                Say("parent " + parent.ToInt64() + " class=" + parentClass);

                // 2) 变成宿主窗的子窗口（跨进程 SetParent；去掉 WS_POPUP、加 WS_CHILD）
                try
                {
                    SetParent(form.Handle, parent);
                    long style = GetWindowLongPtrW(form.Handle, GWL_STYLE).ToInt64();
                    style &= ~(long)WS_POPUP;
                    style |= WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN | WS_CLIPSIBLINGS;
                    SetWindowLongPtrW(form.Handle, GWL_STYLE, new IntPtr(style));
                    long pstyle = GetWindowLongPtrW(parent, GWL_STYLE).ToInt64();
                    SetWindowLongPtrW(parent, GWL_STYLE, new IntPtr(pstyle | WS_CLIPCHILDREN));
                    MoveWindow(form.Handle, opt.X, opt.Y, opt.W, opt.H, true);
                    Say("adopted rect=" + opt.X + "," + opt.Y + "," + opt.W + "," + opt.H);
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
                        Say("nav-done " + ev.IsSuccess + " " + ev.WebErrorStatus);
                        _ = DumpCookies(core, opt);        // 验证页写了什么 cookie（Java 侧按名合并回 jar）
                    };

                    Say("nav-start len=" + opt.Url.Length);
                    core.Navigate(opt.Url);
                    ready = true;
                    Say("ready");
                }
                catch (Exception ex)
                {
                    Say("fail webview2 初始化失败：" + ex.GetType().Name + " " + ex.Message);
                    Application.Exit();
                }
            };

            // 4) 宿主窗没了就自杀（子窗口会随宿主销毁，进程要主动退出）
            var watchdog = new System.Windows.Forms.Timer { Interval = 500 };
            watchdog.Tick += (s, e) =>
            {
                if (!ready) return;
                if (parent != IntPtr.Zero && !IsWindow(parent))
                {
                    Say("parent-gone exit");
                    watchdog.Stop();
                    Application.Exit();
                }
            };
            watchdog.Start();

            Application.Run(form);
            return 0;
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

        /// <summary>找宿主窗：优先「类名 SunAwtDialog + 标题精确命中」；退一步取该 pid 最新建立的 SunAwtDialog。</summary>
        private static bool TryFindParent(Options opt, out IntPtr hwnd, out string cls)
        {
            cls = "";
            hwnd = IntPtr.Zero;
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
                    return true;
                }
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
