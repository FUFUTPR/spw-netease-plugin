package netease.smoke;

import com.example.netease.core.PluginLog;
import com.example.netease.svc.CookieVault;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * T2（svc 账号）离线冒烟：凭据保险柜 + 登录状态机 + 日志脱敏 + 线程生命周期。
 *
 * <p>用法：{@code java -cp &lt;classes&gt; netease.smoke.SmokeAccount [--offline] [--vault-only]}</p>
 *
 * <ul>
 *   <li>默认跑全部离线断言：**不需要宿主、不需要登录、不依赖联网**（只有一段「非阻塞返回耗时」测量会投递一次
 *       二维码创建任务，它异步执行、异常自兜，不影响断言结果）；</li>
 *   <li>{@code --vault-only}：只跑 {@link CookieVault} 部分（对应 {@code smoke-account.ps1 -VaultOnly} 只编译
 *       {@code core.PluginLog} + {@code svc.CookieVault} + 本文件的最小环境），{@code AccountService} 相关一律
 *       SKIP；</li>
 *   <li>{@code AccountService} 全程用反射访问：这样本文件在 {@code AccountService} 尚未编译出来时也能独立跑，
 *       证明保险柜自身可用。</li>
 * </ul>
 *
 * <p>退出码：0 = 全通过（SKIP 不算失败）；1 = 存在 FAIL。</p>
 */
public final class SmokeAccount {

    /** 金丝雀：只要它出现在日志/文件里，就说明脱敏或加密漏了。 */
    private static final String CANARY_COOKIE =
            "MUSIC_U=CANARY_SECRET_9F3A; __csrf=CANARY_CSRF_11; os=pc; appver=9.0.0; NMTID=CANARY_NMT_22";
    private static final String CANARY_COOKIE2 = "MUSIC_U=SECOND_SECRET_77; os=pc";
    /** 生产路径日志里绝不允许出现的片段（前两个是凭据，第三个是设备 id）。 */
    private static final String[] CANARY_SECRETS = {"CANARY_SECRET_9F3A", "CANARY_CSRF_11", "CANARY_NMT_22",
            "SECOND_SECRET_77"};
    /** 故意喂给 PluginLog 的假凭据：验证脱敏正则本身（纵深防御，与上面生产金丝雀分开计数）。 */
    private static final String PROBE_SECRETS_LINE =
            "MUSIC_U=PROBE_SECRET_55; __csrf=PROBE_CSRF_66; password=PROBE_PWD_77";
    private static final String[] PROBE_SECRETS = {"PROBE_SECRET_55", "PROBE_CSRF_66", "PROBE_PWD_77"};
    /** 脱敏规则必须覆盖的敏感键（值必须是 ****）。 */
    private static final String[] MASKED_KEYS = {"MUSIC_U=", "__csrf=", "encSecKey=", "password=", "cookie="};

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;
    private static final List<String> FAILED_ITEMS = new ArrayList<>();

    private static Path tmp;
    private static Path logDir;
    /** {@code --keep}：保留 %TEMP% 工作目录，供「脱敏 grep 证据」事后复检（默认跑完即清理）。 */
    private static boolean keepTmp = false;

    private SmokeAccount() {
    }

    public static void main(String[] args) throws Exception {
        boolean offline = false;
        boolean vaultOnly = false;
        for (String a : args) {
            if ("--offline".equals(a)) {
                offline = true;
            } else if ("--vault-only".equals(a)) {
                vaultOnly = true;
            } else if ("--keep".equals(a)) {
                keepTmp = true;
            }
        }
        System.out.println("=== svc 账号层冒烟（offline=" + offline + "，vaultOnly=" + vaultOnly + "）===");

        tmp = Files.createTempDirectory("spw-account-smoke-");
        logDir = tmp.resolve("logs");
        Files.createDirectories(logDir);
        PluginLog.init(logDir);
        PluginLog.setLevel("DEBUG");   // 越多日志面 = 越严的脱敏检验

        boolean svcAvailable = false;
        try {
            Class.forName("com.example.netease.svc.AccountService");
            svcAvailable = true;
        } catch (Throwable t) {
            svcAvailable = false;
        }

        try {
            vaultChecks();
            if (vaultOnly || !svcAvailable) {
                skip("AccountService 状态机 / 线程 / 退出登录",
                        vaultOnly ? "--vault-only 模式" : "AccountService 不在 classpath（只编了保险柜子集）");
            } else {
                stateMachineChecks();
                qrAbandonRollbackChecks();
                qrReuseAndExpiryChecks();
                afterLoginHookChecks();
                threadLifecycleChecks();
                nonBlockingChecks();
                logoutEndToEndChecks();
            }
            logAndFileRedactionChecks();
        } catch (Throwable t) {
            failed++;
            FAILED_ITEMS.add("未预期异常：" + t);
            System.out.println("  [FAIL] 未预期异常（真 bug，需要修）：" + t.getClass().getName() + " :: " + t.getMessage());
            for (StackTraceElement e : t.getStackTrace()) {
                System.out.println("         at " + e);
            }
        }

        finish();
    }

    // ------------------------------------------------------------------ 1. 保险柜

    private static void vaultChecks() throws Exception {
        System.out.println("--- 1) CookieVault：AES-256-GCM 落盘 ---");
        Path data = tmp.resolve("vault-a");
        CookieVault.init(data);

        Path keyFile = data.resolve("account.key");
        Path vaultFile = data.resolve("account.json");
        check("init 生成 account.key 且为 32B", Files.isRegularFile(keyFile) && Files.size(keyFile) == 32L,
                "exists=" + Files.isRegularFile(keyFile) + ", size=" + sizeOf(keyFile));
        byte[] key1 = Files.readAllBytes(keyFile);

        check("file() 指向 <data>\\account.json",
                CookieVault.file() != null && CookieVault.file().getFileName().toString().equals("account.json"),
                String.valueOf(CookieVault.file()));
        check("未保存时 exists()==false 且 load()==null",
                !CookieVault.exists() && CookieVault.load() == null, "exists=false, load=null");

        CookieVault.save(CANARY_COOKIE);
        String back = CookieVault.load();
        check("save→load 往返一致（逐字符相等）", CANARY_COOKIE.equals(back), "len=" + len(back));
        check("落盘内容以 v1: 开头", text(vaultFile).startsWith("v1:"), head(vaultFile, 12));
        check("account.json 内检索不到 cookie 明文（MUSIC_U= 0 命中）",
                !text(vaultFile).contains("MUSIC_U") && countAny(text(vaultFile), CANARY_SECRETS) == 0,
                "hits(MUSIC_U)=" + count(text(vaultFile), "MUSIC_U"));
        check("exists()==true（文件非空）", CookieVault.exists(), "exists=" + CookieVault.exists());

        // 每次保存新 IV
        String hex1 = ivHex(vaultFile);
        CookieVault.save(CANARY_COOKIE);
        String hex2 = ivHex(vaultFile);
        check("每次 save 生成新随机 IV", !hex1.equals(hex2), "iv1=" + hex1 + " iv2=" + hex2);
        check("两次密文不同但都能解回同一明文",
                !hex1.equals(hex2) && CANARY_COOKIE.equals(CookieVault.load()), "roundtrip=ok");

        // 篡改密文（tag 校验失败）
        byte[] blob = blob(vaultFile);
        blob[blob.length - 1] ^= 0x01;
        writeBlob(vaultFile, blob);
        check("篡改密文末字节 → load()==null（tag 校验失败）", CookieVault.load() == null, "null");
        check("篡改后 load() 不删除文件（P3 降级要求）", Files.isRegularFile(vaultFile), "file kept");

        // 篡改 IV
        CookieVault.save(CANARY_COOKIE);
        blob = blob(vaultFile);
        blob[0] ^= 0x01;
        writeBlob(vaultFile, blob);
        check("篡改 IV → load()==null", CookieVault.load() == null, "null");

        // 截断
        Files.writeString(vaultFile, "v1:AAAA", StandardCharsets.UTF_8);
        check("密文长度不足 → load()==null（不抛）", CookieVault.load() == null, "null");

        // 非 v1 内容（宿主配置串台场景）
        Files.writeString(vaultFile, "{\"auto_login\":true}", StandardCharsets.UTF_8);
        check("非 v1 内容 → load()==null（不抛、不删）",
                CookieVault.load() == null && Files.isRegularFile(vaultFile), "null + kept");
        CookieVault.save(CANARY_COOKIE2);
        Path bak = data.resolve("account.hostconfig.bak");
        check("save 覆盖非保险柜内容前先备份为 account.hostconfig.bak",
                Files.isRegularFile(bak) && text(bak).contains("auto_login"),
                "bak=" + (Files.isRegularFile(bak) ? text(bak) : "<缺失>"));
        check("备份后新凭据可正常解密", CANARY_COOKIE2.equals(CookieVault.load()), "roundtrip=ok");

        // 换机器（换密钥）
        Path data2 = tmp.resolve("vault-b");
        CookieVault.init(data2);
        Files.copy(data.resolve("account.json"), data2.resolve("account.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        check("换机器（换 account.key）后 load()==null", CookieVault.load() == null, "null");

        // 伪造垃圾输入：load() 永远不抛
        int junkOk = 0;
        Random rnd = new Random(20260928L);
        for (int i = 0; i < 100; i++) {
            byte[] raw = new byte[1 + rnd.nextInt(200)];
            rnd.nextBytes(raw);
            String body = (i % 3 == 0) ? "" : Base64.getEncoder().encodeToString(raw);
            Files.writeString(data2.resolve("account.json"), (i % 2 == 0 ? "v1:" : "v2:") + body, StandardCharsets.UTF_8);
            if (CookieVault.load() == null) {
                junkOk++;
            }
        }
        check("100 组伪造/垃圾密文 load() 全部返回 null 且不抛", junkOk == 100, junkOk + "/100");

        // init 幂等：复用同一把 key
        CookieVault.init(data);
        byte[] key2 = Files.readAllBytes(keyFile);
        check("重复 init 复用同一 account.key", Arrays.equals(key1, key2), "len=" + key2.length);
        CookieVault.save(CANARY_COOKIE);
        check("复用 key 后仍可解密", CANARY_COOKIE.equals(CookieVault.load()), "roundtrip=ok");

        // save(null/"") = clear
        CookieVault.save((String) null);
        check("save(null) 等价 clear（文件删除、key 保留、load()==null）",
                !Files.exists(vaultFile) && Files.isRegularFile(keyFile) && CookieVault.load() == null,
                "vaultGone=" + !Files.exists(vaultFile) + ", keyKept=" + Files.isRegularFile(keyFile));
        CookieVault.save(CANARY_COOKIE);
        CookieVault.save("   ");
        check("save(\"   \") 等价 clear（不抛）",
                !Files.exists(vaultFile) && CookieVault.load() == null, "cleared");

        // clear()
        CookieVault.save(CANARY_COOKIE);
        CookieVault.clear();
        check("clear() 删 account.json 但保留 account.key",
                !Files.exists(vaultFile) && Files.isRegularFile(keyFile) && Files.size(keyFile) == 32L,
                "vaultGone=" + !Files.exists(vaultFile) + ", keySize=" + sizeOf(keyFile));
        CookieVault.clear();
        check("clear() 幂等（第二次不抛）", true, "ok");
        CookieVault.save(CANARY_COOKIE);
        check("clear 后仍可继续保存/解密（key 复用）", CANARY_COOKIE.equals(CookieVault.load()), "roundtrip=ok");

        // 供日志/文件脱敏阶段复用的现场
        System.out.println("  [EVIDENCE] 密文样例：前 12 字符 = " + head(vaultFile, 12)
                + " ，总长 " + text(vaultFile).length() + " 字符（明文长度 " + CANARY_COOKIE.length() + "，明文不出现在任何断言输出里）");
    }

    // ------------------------------------------------------------------ 2. 状态机

    private static void stateMachineChecks() throws Exception {
        System.out.println("--- 2) AccountService：登录状态机（测试钩子驱动，全程离线） ---");
        Path data = dataDir();
        CookieVault.init(data);

        Svc.init(data, false);
        check("init(dir,false)：状态 NONE", "NONE".equals(Svc.state()), Svc.state());
        check("init 后 statusLine()==未登录", "未登录".equals(Svc.statusLine()), Svc.statusLine());
        check("init 后 loggedIn()==false", !Svc.loggedIn(), "false");
        check("file() == <data>\\account.json",
                data.resolve("account.json").equals(Svc.path("file")), String.valueOf(Svc.path("file")));

        Svc.feed(801, null, null, 0L, false);
        check("801 → WAITING_SCAN", "WAITING_SCAN".equals(Svc.state()), Svc.state());
        check("801 → statusLine 提示等待扫码", Svc.statusLine().contains("等待扫码"), Svc.statusLine());
        long exp = ((Number) Svc.field(Svc.status(), "expiresAtMs")).longValue();
        check("扫码态 expiresAtMs>0（倒计时可用）", exp > 0L, String.valueOf(exp));

        Svc.feed(802, null, null, 0L, false);
        check("802 → WAITING_CONFIRM", "WAITING_CONFIRM".equals(Svc.state()), Svc.state());
        check("802 → statusLine 提示手机确认", Svc.statusLine().contains("确认"), Svc.statusLine());

        Svc.feed(800, null, null, 0L, false);
        check("800 → EXPIRED（二维码过期）", "EXPIRED".equals(Svc.state()), Svc.state());
        check("800 → statusLine 含「过期」", Svc.statusLine().contains("过期"), Svc.statusLine());
        check("800 后 loggedIn()==false", !Svc.loggedIn(), "false");

        Svc.reset();
        Svc.feed(801, null, null, 0L, false);
        Svc.feed(803, CANARY_COOKIE, "冒烟昵称", 4242L, true);
        check("803 → LOGGED_IN", "LOGGED_IN".equals(Svc.state()), Svc.state());
        check("803 → nickname 落进快照", "冒烟昵称".equals(Svc.field(Svc.status(), "nickname")),
                String.valueOf(Svc.field(Svc.status(), "nickname")));
        check("803 → uid 落进快照", 4242L == ((Number) Svc.field(Svc.status(), "uid")).longValue(),
                String.valueOf(Svc.field(Svc.status(), "uid")));
        check("803 → vip 落进快照", Boolean.TRUE.equals(Svc.field(Svc.status(), "vip")),
                String.valueOf(Svc.field(Svc.status(), "vip")));
        check("803 → statusLine==已登录：冒烟昵称（VIP）",
                "已登录：冒烟昵称（VIP）".equals(Svc.statusLine()), Svc.statusLine());
        check("803 → loggedIn()==true", Svc.loggedIn(), "true");
        check("803 → 登录态 expiresAtMs==0", 0L == ((Number) Svc.field(Svc.status(), "expiresAtMs")).longValue(),
                String.valueOf(Svc.field(Svc.status(), "expiresAtMs")));

        String saved = CookieVault.load();
        check("803 时立刻 CookieVault.save(exportCookies())（凭据已落盘且可解密）",
                CANARY_COOKIE.equals(saved), "len=" + len(saved));
        check("落盘后 account.json 仍无明文（MUSIC_U 0 命中）",
                !text(data.resolve("account.json")).contains("MUSIC_U"), "0 hit");

        Svc.feed(803, CANARY_COOKIE2, null, 0L, false);
        check("803 无昵称 → statusLine 形如「已登录：<昵称未知>」",
                "已登录：<昵称未知>".equals(Svc.statusLine()), Svc.statusLine());

        Path vault = data.resolve("account.json");
        Svc.expired();
        check("凭据失效（301 路径）→ EXPIRED", "EXPIRED".equals(Svc.state()), Svc.state());
        check("凭据失效 → statusLine==登录已过期，请重新扫码",
                "登录已过期，请重新扫码".equals(Svc.statusLine()), Svc.statusLine());
        check("凭据失效 → loggedIn()==false", !Svc.loggedIn(), "false");
        check("凭据失效 → 本地文件保留（降级要求，不删坏文件）", Files.isRegularFile(vault), "kept");
        check("凭据失效 → 解出的明文不入日志（见第 5 阶段扫描）", true, "deferred");

        Svc.reset();
        check("__testReset() → NONE", "NONE".equals(Svc.state()), Svc.state());
        check("烟测线程不是 netease-account 线程", !Svc.booleanCall("__testOnAccountThread"),
                "onAccountThread=" + Svc.booleanCall("__testOnAccountThread"));

        // 并发读快照：UI/宿主线程真实用法
        AtomicInteger errors = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean(false);
        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                while (!stop.get()) {
                    try {
                        Svc.status();
                        Svc.statusLine();
                        Svc.loggedIn();
                        Svc.call("qrImage");
                    } catch (Throwable e) {
                        errors.incrementAndGet();
                    }
                }
            }, "smoke-reader-" + i);
            t.setDaemon(true);
            readers.add(t);
            t.start();
        }
        for (int i = 0; i < 200; i++) {
            Svc.feed((i % 2 == 0) ? 801 : 802, null, null, 0L, false);
        }
        stop.set(true);
        for (Thread t : readers) {
            t.join(2000L);
        }
        check("4 线程并发读 status()/statusLine()/loggedIn()/qrImage() 期间 0 异常",
                errors.get() == 0, "errors=" + errors.get());
        Svc.reset();
    }

    // ------------------------------------------------------------------ 2b. 关窗回滚（0.11.41）

    /**
     * 用户 m00221 ②「早已登录好，再点登录会把登录清掉」的离线回归：已登录 ⇒ 开登录窗（startQr 把状态压成
     * WAITING_SCAN）⇒ 没扫就关窗（pauseQrWatch）⇒ 必须回滚到原来的 LOGGED_IN；且**迟到的**二维码生成
     * 结果不许再把回滚冲掉（qrGen 闸门）。阴性对照：本来未登录 ⇒ 不回滚、不许凭空造出 LOGGED_IN。
     */
    private static void qrAbandonRollbackChecks() throws Exception {
        System.out.println("--- 2b) 关窗回滚：已登录点登录、没扫就关窗 ⇒ 恢复原登录态（0.11.41 / m00221②） ---");
        Svc.reset();
        Svc.feed(803, CANARY_COOKIE, "冒烟昵称", 4242L, true);
        importJarCookies(CANARY_COOKIE);   // 真机 803 时 jar 里已有 MUSIC_U；烟测喂进去对齐现场
        check("前置：LOGGED_IN 且 jar 内有 MUSIC_U", "LOGGED_IN".equals(Svc.state()) && jarLoggedIn(),
                Svc.state() + "/jar=" + jarLoggedIn());

        Svc.call("startQr");
        check("开登录窗（startQr）后乐观态 = WAITING_SCAN", "WAITING_SCAN".equals(Svc.state()), Svc.state());
        boolean restored = Svc.booleanCall("pauseQrWatch");
        check("关窗（pauseQrWatch）报告发生了回滚", restored, "restored=" + restored);
        check("关窗后状态回 LOGGED_IN（不再显示「当前未登录」）", "LOGGED_IN".equals(Svc.state()), Svc.state());
        check("关窗后昵称/uid/VIP 复原",
                "冒烟昵称".equals(Svc.field(Svc.status(), "nickname"))
                        && 4242L == ((Number) Svc.field(Svc.status(), "uid")).longValue()
                        && Boolean.TRUE.equals(Svc.field(Svc.status(), "vip")),
                Svc.statusLine());
        check("关窗后 loggedIn() 仍为 true（凭据没被清）", Svc.loggedIn(), "true");

        Svc.booleanCall("__testDrain", 20000L);   // 等在途二维码生成任务排空
        check("迟到的二维码生成结果不会冲掉回滚（qrGen 闸门）", "LOGGED_IN".equals(Svc.state()), Svc.state());

        // 真机追加缺陷（2026-10-03 21:07）：登录窗里「扫码 → 验证码登录 → 扫码」切回会**再调一次**
        // startQr（那一刻状态已是 WAITING_SCAN）；第二次不许把开窗时记下的登录态快照冲成 null，
        // 否则关窗回滚失效（用户点取消后「当前账号」又显示未登录）。
        Svc.reset();
        Svc.feed(803, CANARY_COOKIE, "冒烟昵称", 4242L, true);
        importJarCookies(CANARY_COOKIE);
        Svc.call("startQr");
        Svc.call("startQr");                       // 切档回来后的第二次取码
        boolean restored3 = Svc.booleanCall("pauseQrWatch");
        check("切档二次取码后关窗仍能回滚（0.11.42 真机缺陷回归）",
                restored3 && "LOGGED_IN".equals(Svc.state()), "restored=" + restored3 + "/" + Svc.state());
        Svc.booleanCall("__testDrain", 20000L);

        // 真机追加缺陷（2026-10-03 21:13:04）：关窗回滚完成后，**已经在网线上**的轮询结论（801）
        // 又落了一次状态 ⇒ 被压回等待态，下一次开窗再关就没有快照可回滚。这里让真实轮询先上网线、
        // 中途关窗回滚，断言迟到的结论被代号闸门丢弃。
        Svc.reset();
        Svc.feed(803, CANARY_COOKIE, "冒烟昵称", 4242L, true);
        importJarCookies(CANARY_COOKIE);
        Svc.call("startQr");
        Svc.booleanCall("__testDrain", 20000L);   // 等二维码真正生成（qrKey 就位，轮询才发得出去）
        Svc.call("pollNow");                      // 投递一次真实轮询（网络耗时可跨过回滚）
        Thread.sleep(120L);                       // 让轮询先进入网络调用
        boolean restored4 = Svc.booleanCall("pauseQrWatch");
        Svc.booleanCall("__testDrain", 20000L);   // 等迟到的轮询结论回来
        check("迟到轮询结论不冲掉回滚（0.11.43 真机缺陷回归）",
                restored4 && "LOGGED_IN".equals(Svc.state()), "restored=" + restored4 + "/" + Svc.state());

        Svc.reset();                               // 阴性对照：本来未登录
        Svc.call("startQr");
        boolean restored2 = Svc.booleanCall("pauseQrWatch");
        check("阴性对照：本来未登录 ⇒ 不回滚（false）、不凭空造 LOGGED_IN",
                !restored2 && !"LOGGED_IN".equals(Svc.state()), "restored=" + restored2 + "/" + Svc.state());
        Svc.booleanCall("__testDrain", 20000L);
        Svc.reset();
    }

    // ------------------------------------------------- 2c. 二维码复用 / 过期表现（0.11.44）

    /**
     * 0.11.44（用户裁定）：「切一下验证码登录就把码刷新了」「点取消也不要直接刷新二维码」「取消退出后
     * 只要码没过期，进来还是那张码」「到时间了就把码变黑并提示刷新」「窗口过段时间自己关了」。
     *
     * <p>离线回归这五条的状态机一侧：保鲜期内复用同一把 key（零请求）、超过保鲜期 / 网易云 800 后
     * 必定重新取码、800 只判死这张码（不把窗口骗成「扫码成功」）、关窗回滚照旧。</p>
     */
    private static void qrReuseAndExpiryChecks() throws Exception {
        System.out.println("--- 2c) 二维码复用 / 过期表现 / 窗口不自关（0.11.44） ---");
        long now = System.currentTimeMillis();

        // ① 保鲜期内（本地码 5s 前生成）⇒ 第二次 startQr 复用，不投递新任务、状态回到扫码态
        Svc.reset();
        String fresh = "smoke-key-fresh-0000000000000001";
        Svc.call("__testSeedQr", fresh, now - 5000L);
        Object reused = Svc.call("startQr", false);
        check("保鲜期内：startQr(false) 复用（返回 false = 没有新取码任务）", Boolean.FALSE.equals(reused),
                "reused=" + reused);
        check("保鲜期内：key 原样保留（用户「进来还是那张码」）", fresh.equals(qrKeyNow()), qrKeyNow());
        check("保鲜期内：状态回到扫码态（不再是上一轮回滚的 LOGGED_IN）", "WAITING_SCAN".equals(Svc.state()),
                Svc.state());

        // ② 超过保鲜期 ⇒ 必须重新取码（旧码立刻作废，界面会显示「正在获取二维码…」）
        Svc.reset();
        Svc.call("__testSeedQr", "smoke-key-stale-0000000000000001", now - 200_000L);
        Object started = Svc.call("startQr", false);
        check("超保鲜期：startQr(false) 投递新取码任务（返回 true）", Boolean.TRUE.equals(started),
                "started=" + started);
        check("超保鲜期：旧码立即作废（qrImage()==null，界面不会磨蹭着展示死码）",
                Svc.call("qrImage") == null, String.valueOf(Svc.call("qrImage")));
        Svc.booleanCall("__testDrain", 20000L);   // 等在途取码排空（联网则拿到新码，离线则 ERROR，都不影响上面的断言）

        // ③ 网易云回 800：只判死这张码（EXPIRED + 过期文案），不再当场回滚成 LOGGED_IN（窗口不许自己关）
        Svc.reset();
        Svc.feed(803, CANARY_COOKIE, "冒烟昵称", 4242L, true);
        importJarCookies(CANARY_COOKIE);
        Svc.call("startQr");
        Svc.booleanCall("__testDrain", 20000L);
        Svc.call("__testSeedQr", "smoke-key-800-000000000000000001", now);
        Svc.feed(800, null, null, 0L, false);
        check("网易云 800 ⇒ EXPIRED（不把「扫码前已登录」当场放回来）", "EXPIRED".equals(Svc.state()), Svc.state());
        check("800 的文案 = 二维码已过期，请刷新二维码",
                String.valueOf(Svc.field(Svc.status(), "message")).contains("二维码已过期"), Svc.statusLine());
        check("开窗时已登录 ⇒ wasLoggedInAtOpen()=true（界面据此不自动关窗）",
                Svc.booleanCall("wasLoggedInAtOpen"), "true");
        boolean restored = Svc.booleanCall("pauseQrWatch");
        check("800 后关窗仍回滚到扫码前登录态（0.11.41 不回归）",
                restored && "LOGGED_IN".equals(Svc.state()), "restored=" + restored + "/" + Svc.state());
        Object after800 = Svc.call("startQr", false);
        check("网易云判过期的码不许复用：重开窗直接投递新取码任务", Boolean.TRUE.equals(after800),
                "started=" + after800);
        Svc.booleanCall("__testDrain", 20000L);
        Svc.reset();

        // ④ 取消（关窗回滚）后重开：同一把未过期码复用；再取消仍能回滚
        Svc.reset();
        Svc.feed(803, CANARY_COOKIE, "冒烟昵称", 4242L, true);
        importJarCookies(CANARY_COOKIE);
        Svc.call("startQr");
        Svc.booleanCall("__testDrain", 20000L);
        String keep = "smoke-key-keep-00000000000000001";
        Svc.call("__testSeedQr", keep, System.currentTimeMillis() - 3000L);
        boolean rolled = Svc.booleanCall("pauseQrWatch");
        check("取消关窗：回滚到 LOGGED_IN 且二维码保留在内存",
                rolled && "LOGGED_IN".equals(Svc.state()) && keep.equals(qrKeyNow()),
                "rolled=" + rolled + "/" + Svc.state() + "/key=" + qrKeyNow());
        Object again = Svc.call("startQr", false);
        check("重开窗：复用同一把码（返回 false、key 不变、状态回扫码态）",
                Boolean.FALSE.equals(again) && keep.equals(qrKeyNow()) && "WAITING_SCAN".equals(Svc.state()),
                "again=" + again + "/key=" + qrKeyNow() + "/" + Svc.state());
        boolean rolled2 = Svc.booleanCall("pauseQrWatch");
        check("复用后再取消：快照没被冲掉，仍回滚到 LOGGED_IN",
                rolled2 && "LOGGED_IN".equals(Svc.state()), "rolled=" + rolled2 + "/" + Svc.state());

        // ⑤ 窗口不自关（登录成功代数）：开窗时未登录 ⇒ 扫码成功照旧自动关窗；已登录 ⇒ 恢复态不算成功
        Svc.reset();
        Svc.call("__testSeedQr", "smoke-key-anon-000000000000000001", System.currentTimeMillis());
        Svc.call("startQr", false);
        check("开窗时未登录 ⇒ wasLoggedInAtOpen()=false（扫码成功仍会自动关窗）",
                !Svc.booleanCall("wasLoggedInAtOpen"), "false");
        Svc.reset();
        Svc.feed(803, CANARY_COOKIE, "冒烟昵称", 4242L, true);
        importJarCookies(CANARY_COOKIE);
        Svc.call("startQr");
        check("开窗时已登录 ⇒ wasLoggedInAtOpen()=true（恢复态不会把窗关掉）",
                Svc.booleanCall("wasLoggedInAtOpen"), "true");
        Svc.feed(803, CANARY_COOKIE, "冒烟昵称", 4242L, true);
        check("开窗后真的扫码成功 ⇒ wasLoggedInAtOpen()=false（该关窗时照常关）",
                !Svc.booleanCall("wasLoggedInAtOpen"), "false");
        Svc.booleanCall("pauseQrWatch");
        Svc.reset();
    }

    /** 当前 {@code AccountService.qrImage()} 的 key（没有码则空串；烟测不直接引用 net 层类型）。 */
    private static String qrKeyNow() throws Exception {
        Object qr = Svc.call("qrImage");
        return qr == null ? "" : String.valueOf(Svc.field(qr, "key"));
    }

    /** 反射调 {@code NeteaseApi.importCookies}（本文件在 VaultOnly 模式也要能编译 ⇒ 不直接引用该类）。 */
    private static void importJarCookies(String cookie) throws Exception {
        Class<?> api = Class.forName("com.example.netease.net.NeteaseApi");
        api.getMethod("importCookies", String.class).invoke(null, cookie);
    }

    /** 反射调 {@code NeteaseApi.isLoggedIn()}（同上）。 */
    private static boolean jarLoggedIn() throws Exception {
        Class<?> api = Class.forName("com.example.netease.net.NeteaseApi");
        return Boolean.TRUE.equals(api.getMethod("isLoggedIn").invoke(null));
    }

    // ------------------------------------------------------------------ 3. 线程生命周期

    private static void threadLifecycleChecks() throws Exception {
        System.out.println("--- 3) 线程生命周期（P3-A8：stop() 后不留线程） ---");
        check("账号线程存在且为守护线程、名字 = netease-account",
                accountThread() != null && accountThread().isDaemon(), describeAccountThread());
        check("__testDrain(3000) 排空成功（账号线程可正常工作）", Svc.booleanCall("__testDrain", 3000L), "drained");
        check("drain 期间账号线程仍在（未意外退出）", accountThread() != null, describeAccountThread());

        Svc.stop();
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline && accountThread() != null) {
            Thread.sleep(50L);
        }
        check("stop() 后 5s 内 netease-account 线程消失（不残留）", accountThread() == null,
                describeAccountThread());
        check("stop() 幂等（重复调用不抛）", idempotentStop(), "ok");
    }

    private static boolean idempotentStop() {
        try {
            Svc.stop();
            Svc.stop();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 4. 非阻塞返回

    private static void nonBlockingChecks() throws Exception {
        System.out.println("--- 4) 立即返回（UI 线程不可被阻塞） ---");
        Svc.reset();
        check("pollNow() 在 NONE 状态是 no-op 且立即返回（<50ms）",
                millis(() -> Svc.call("pollNow")) < 50L, "elapsed<50ms");

        long qrMs = millis(() -> Svc.call("startQr"));
        check("startQr() 立即返回（<200ms，真活儿交给账号线程）", qrMs < 200L, qrMs + "ms");
        check("startQr() 后 status() 立刻是扫码态（乐观快照，UI 无需等待网络）",
                "WAITING_SCAN".equals(Svc.state()), Svc.state());

        long pollMs = millis(() -> Svc.call("pollNow"));
        check("pollNow() 扫码态立即返回（<200ms）", pollMs < 200L, pollMs + "ms");
        Svc.booleanCall("__testDrain", 20000L);   // 等异步二维码任务排空（结果不影响断言：联网与否都行）
    }

    // ------------------------------------------------------------------ 5. 退出登录端到端

    private static void logoutEndToEndChecks() throws Exception {
        System.out.println("--- 5) logout 端到端（P3-A6：内存态清空 + account.json 删除） ---");
        Path data = dataDir();
        Path vault = data.resolve("account.json");
        Svc.reset();
        Svc.feed(803, CANARY_COOKIE, "冒烟昵称", 7L, false);
        check("登出前：LOGGED_IN + 凭据文件存在", Svc.loggedIn() && Files.isRegularFile(vault),
                Svc.state() + "/file=" + Files.isRegularFile(vault));

        long ms = millis(() -> Svc.call("logout"));
        check("logout() 立即返回（<200ms）", ms < 200L, ms + "ms");
        check("logout() 后内存状态立刻 NONE + loggedIn()==false（UI 立刻刷新）",
                "NONE".equals(Svc.state()) && !Svc.loggedIn(), Svc.state());
        Svc.booleanCall("__testDrain", 20000L);
        check("logout() 落盘：account.json 被删除", !Files.exists(vault), "vaultGone=" + !Files.exists(vault));
        check("logout() 保留 account.key（下次登录复用）",
                Files.isRegularFile(data.resolve("account.key")), "key kept");
        check("logout() 后 load()==null 且 exists()==false",
                CookieVault.load() == null && !CookieVault.exists(), "null/false");
        Svc.booleanCall("__testDrain", 2000L);
    }

    // ------------------------------------------------------ 2b. 登录后回调契约与负对照

    /** 登录后回调的日志标记（与 {@code AccountService} 里的字面量一致；那边改了这里要同步）。 */
    private static final String HOOK_FIRE_LOG = "登录成功 → 触发登录后回调（一轮同步）";
    private static final String HOOK_FAIL_LOG = "登录后回调失败（登录态不受影响";
    private static final String HOOK_INJECT_LOG = "登录后回调：已注入（登录成功后触发一轮同步）";
    private static final String HOOK_UNLOAD_LOG = "登录后回调：已卸载";

    /**
     * 登录后回调（{@code setAfterLoginHook} → {@code fireAfterLogin}）契约与**负对照**。
     *
     * <p>被验证的三条硬要求（docs/51 §8）：①回调在触发线程上**同步**执行，但异常**不得逃逸**
     * （登录已经成功，不能被同步失败拖成失败）；②回调失败必须 {@code PluginLog.e} 可见
     * （用户据此点「立即重新同步」重试）；③同步失败**不得破坏登录态**。</p>
     *
     * <p>负对照走产品自身的公开注入点 {@code setAfterLoginHook}（不反射改私有状态、不改产品代码）：
     * 注入一个**故意抛异常**的回调，证明「异常被 fire 吞下 + 落日志 + 状态逐字不变」。</p>
     */
    private static void afterLoginHookChecks() throws Exception {
        System.out.println("--- 2b) 登录后回调契约（setAfterLoginHook → fireAfterLogin）与负对照 ---");

        String stateBefore = Svc.state();
        String lineBefore = Svc.statusLine();

        // ① 未注入：触发必须是无副作用的 no-op
        int fire0 = count(logText(), HOOK_FIRE_LOG);
        boolean noHookThrow = true;
        try {
            Svc.call("__testFireAfterLogin");
        } catch (Throwable t) {
            noHookThrow = false;
        }
        check("未注入回调时触发是 no-op（不抛、不记「触发」日志）",
                noHookThrow && count(logText(), HOOK_FIRE_LOG) == fire0,
                "noThrow=" + noHookThrow + "，触发日志=" + count(logText(), HOOK_FIRE_LOG) + " 次");

        // ② 正常注入：同步执行、每次触发恰好一次
        int[] calls = {0};
        Thread[] seenOn = {null};
        Svc.call("setAfterLoginHook", new Class<?>[]{Runnable.class}, (Runnable) () -> {
            calls[0]++;
            seenOn[0] = Thread.currentThread();
        });
        check("注入回调本身可见（日志「" + HOOK_INJECT_LOG + "」）",
                logText().contains(HOOK_INJECT_LOG), "logged=ok");

        long ms = millis(() -> Svc.call("__testFireAfterLogin"));
        check("触发一次 → 回调恰好被调用 1 次", calls[0] == 1, "calls=" + calls[0]);
        check("回调在触发线程上同步执行（不是另投线程）",
                seenOn[0] == Thread.currentThread(),
                "thread=" + (seenOn[0] == null ? "<未调用>" : seenOn[0].getName()));
        check("触发本身立即返回（<50ms；回调只投递、不做活儿）", ms < 50L, ms + "ms");
        check("触发写入可见日志「" + HOOK_FIRE_LOG + "」", logText().contains(HOOK_FIRE_LOG), "logged=ok");

        Svc.call("__testFireAfterLogin");
        check("再触发一次 → 累计 2 次（每次登录成功各触发一轮，不合并/不抑制）",
                calls[0] == 2, "calls=" + calls[0]);

        // ③ 负对照：回调抛异常
        int fail0 = count(logText(), HOOK_FAIL_LOG);
        Svc.call("setAfterLoginHook", new Class<?>[]{Runnable.class}, (Runnable) () -> {
            calls[0]++;
            throw new IllegalStateException("负对照：hook 故意抛异常");
        });
        boolean boomEscaped = false;
        try {
            Svc.call("__testFireAfterLogin");
        } catch (Throwable t) {
            boomEscaped = true;
        }
        String afterBoom = logText();
        check("负对照：回调抛异常时触发不向外抛（异常被 fire 吞下）",
                !boomEscaped && calls[0] == 3, "向外抛=" + boomEscaped + " calls=" + calls[0]);
        check("负对照：回调失败落日志（PluginLog.e + 堆栈，可据此手动重试同步）",
                count(afterBoom, HOOK_FAIL_LOG) == fail0 + 1 && afterBoom.contains("IllegalStateException"),
                "失败日志 +1 且含异常类型");
        check("负对照：登录态未被同步失败破坏（state / statusLine 逐字不变）",
                Svc.state().equals(stateBefore) && Svc.statusLine().equals(lineBefore),
                Svc.state() + " / " + Svc.statusLine());
        System.out.println("  [EVIDENCE] 负对照日志原文：" + lineOf(afterBoom, HOOK_FAIL_LOG));

        // ④ 失败后回调仍在位（下次登录仍会尝试触发，不被自动卸载）
        try {
            Svc.call("__testFireAfterLogin");
        } catch (Throwable ignored) {
            // 期望不抛；真抛了会在下一条断言里体现
        }
        check("负对照：抛异常后回调仍在位（不被自动卸载）", calls[0] == 4, "calls=" + calls[0]);

        // ⑤ 恢复：换回正常回调
        Svc.call("setAfterLoginHook", new Class<?>[]{Runnable.class}, (Runnable) () -> calls[0]++);
        Svc.call("__testFireAfterLogin");
        check("恢复注入后可继续触发", calls[0] == 5, "calls=" + calls[0]);

        // ⑥ 卸载：传 null
        Svc.call("setAfterLoginHook", new Class<?>[]{Runnable.class}, (Object) null);
        int fireAtUnload = count(logText(), HOOK_FIRE_LOG);
        Svc.call("__testFireAfterLogin");
        check("卸载（null）后触发不再调用回调、也不再记「触发」日志",
                calls[0] == 5 && logText().contains(HOOK_UNLOAD_LOG)
                        && count(logText(), HOOK_FIRE_LOG) == fireAtUnload,
                "calls=" + calls[0] + "，已卸载日志=" + logText().contains(HOOK_UNLOAD_LOG));
    }

    /** 当前插件日志全文（未 init / 未落盘时返回空串）。 */
    private static String logText() {
        try {
            Path p = PluginLog.file();
            return (p != null && Files.isRegularFile(p)) ? text(p) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 取包含 {@code needle} 的首行（截断 240 字符），用于把日志原文抄进证据文档。 */
    private static String lineOf(String text, String needle) {
        for (String line : text.split("\\R")) {
            if (line.contains(needle)) {
                return line.length() > 240 ? line.substring(0, 240) + "…" : line;
            }
        }
        return "<未找到 " + needle + ">";
    }

    // ------------------------------------------------------------------ 6. 脱敏扫描

    private static void logAndFileRedactionChecks() throws Exception {
        System.out.println("--- 6) 日志与数据文件脱敏（A10：全日志无 cookie 明文） ---");
        CookieVault.save(CANARY_COOKIE);       // 生产路径：保险柜只记长度，不记内容
        PluginLog.i("smoke", "生产路径标记：凭据已保存（" + CANARY_COOKIE.length() + " 字符，内容不落日志）");
        // 纵深防御探针：故意把假凭据交给 PluginLog，验证它自己的脱敏正则
        PluginLog.i("smoke", "脱敏探针（故意喂假凭据）：" + PROBE_SECRETS_LINE);

        Path logFile = PluginLog.file();
        check("日志文件存在且非空", logFile != null && Files.isRegularFile(logFile) && Files.size(logFile) > 0L,
                String.valueOf(logFile) + " / " + sizeOf(logFile) + "B");
        String log = logFile == null ? "" : text(logFile);

        int prodHits = countAny(log, CANARY_SECRETS);
        check("生产路径：cookie 的任一字段（MUSIC_U 值 / csrf / 设备 id）在日志中 0 命中",
                prodHits == 0, "hits=" + prodHits + "（金丝雀 " + CANARY_SECRETS.length + " 个）");
        int probeHits = countAny(log, PROBE_SECRETS);
        int unmasked = unmaskedHits(log, MASKED_KEYS);
        check("纵深防御：PluginLog 把「键=值」一律脱敏成 ****（探针值 0 命中）",
                probeHits == 0 && unmasked == 0,
                "探针命中=" + probeHits + "，未脱敏键=" + unmasked + "，MUSIC_U= 出现 "
                        + count(log, "MUSIC_U=") + " 次（值恒为 ****）");
        check("日志确实写入过（防「空日志 = 假通过」）",
                log.contains("脱敏探针") && log.contains("凭据已加密保存"), "markers=ok");
        System.out.println("  [NOTE] 已知缺口：PluginLog.SECRETS 覆盖 MUSIC_U/__csrf/encSecKey/password/token/cookie，"
                + "不含 NMTID=（设备 id，非凭据）；本插件生产路径从不打印整条 cookie，故 A10 不受影响（见 docs/15）");

        // 数据目录里所有落盘文件都不得出现凭据明文，也不得有未脱敏的敏感键
        int bad = 0;
        StringBuilder where = new StringBuilder();
        try (Stream<Path> s = Files.walk(tmp)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                String c = text(p);
                int h = countAny(c, CANARY_SECRETS) + countAny(c, PROBE_SECRETS) + unmaskedHits(c, MASKED_KEYS);
                if (h > 0) {
                    bad += h;
                    where.append(p.getFileName()).append('(').append(h).append(") ");
                }
            }
        }
        check("数据目录内所有文件（account.json / 日志 / 备份 / key）明文与未脱敏键 0 命中",
                bad == 0, "hits=" + bad + (bad > 0 ? " @ " + where : ""));
        check("account.key 是 32B CSPRNG，不是明文 cookie",
                sizeOf(tmp.resolve("vault-a").resolve("account.key")) == 32L, "32B");

        System.out.println("  [EVIDENCE] account.json（末态）前 12 字符 = " + shortFile() + "，日志文件大小 = "
                + sizeOf(PluginLog.file()) + "B");
    }

    // ------------------------------------------------------------------ 反射门面

    /** AccountService 的反射门面：让本烟测在 AccountService 缺席时也能编译/运行。 */
    private static final class Svc {
        private static Class<?> cls() throws Exception {
            return Class.forName("com.example.netease.svc.AccountService");
        }

        static Object call(String name, Object... args) throws Exception {
            Class<?>[] sig = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                Class<?> t = args[i].getClass();
                sig[i] = (t == Integer.class) ? int.class
                        : (t == Long.class) ? long.class
                        : (t == Boolean.class) ? boolean.class
                        : t;
            }
            return call(name, sig, args);
        }

        static Object call(String name, Class<?>[] sig, Object... args) throws Exception {
            Method m = cls().getDeclaredMethod(name, sig);
            m.setAccessible(true);
            try {
                return m.invoke(null, args);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("AccountService." + name + "() 抛异常："
                        + e.getCause(), e.getCause());
            }
        }

        static void init(Path dir, boolean autoLogin) throws Exception {
            call("init", new Class<?>[]{Path.class, boolean.class}, dir, autoLogin);
        }

        static void stop() throws Exception {
            call("stop");
        }

        static Object status() throws Exception {
            return call("status");
        }

        static String state() throws Exception {
            return String.valueOf(field(status(), "state"));
        }

        static String statusLine() throws Exception {
            return String.valueOf(call("statusLine"));
        }

        static boolean loggedIn() throws Exception {
            return Boolean.TRUE.equals(call("loggedIn"));
        }

        static Path path(String name) throws Exception {
            return (Path) call(name);
        }

        static boolean booleanCall(String name, Object... args) throws Exception {
            return Boolean.TRUE.equals(call(name, args));
        }

        static void reset() throws Exception {
            call("__testReset");
        }

        static void feed(int code, String cookies, String nick, long uid, boolean vip) throws Exception {
            call("__testFeedQrCode", new Class<?>[]{int.class, String.class, String.class, long.class, boolean.class},
                    code, cookies, nick, uid, vip);
        }

        static void expired() throws Exception {
            call("__testFeedSessionExpired");
        }

        static Object field(Object target, String name) throws Exception {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        }
    }

    // ------------------------------------------------------------------ 工具

    private static Path dataDir() {
        return tmp.resolve("svc-data");
    }

    private static String text(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static long sizeOf(Path p) {
        try {
            return (p == null || !Files.isRegularFile(p)) ? -1L : Files.size(p);
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static int count(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    /** 多关键词命中计数（用于金丝雀扫描）。 */
    private static int countAny(String haystack, String[] needles) {
        int n = 0;
        for (String s : needles) {
            n += count(haystack, s);
        }
        return n;
    }

    /**
     * 统计「敏感键后面跟着真值」的次数：{@code MUSIC_U=****} 是脱敏合格，
     * {@code MUSIC_U=真值} 才算泄漏（键名本身留在日志里是允许的，见 docs/00 §9）。
     */
    private static int unmaskedHits(String text, String[] keys) {
        int n = 0;
        for (String k : keys) {
            int i = text.indexOf(k);
            while (i >= 0) {
                if (!text.startsWith("****", i + k.length())) {
                    n++;
                }
                i = text.indexOf(k, i + k.length());
            }
        }
        return n;
    }

    private static String head(Path p, int chars) {
        String t = text(p);
        return t.isEmpty() ? "<空>" : t.substring(0, Math.min(chars, t.length()));
    }

    private static String shortFile() {
        Path p = dataDir().resolve("account.json");
        return Files.isRegularFile(p) ? head(p, 12) + "（长度 " + text(p).length() + "）" : "<已删除>";
    }

    private static int len(String s) {
        return s == null ? -1 : s.length();
    }

    private static byte[] blob(Path vaultFile) throws Exception {
        String t = text(vaultFile);
        return Base64.getDecoder().decode(t.substring("v1:".length()));
    }

    private static void writeBlob(Path vaultFile, byte[] b) throws Exception {
        Files.writeString(vaultFile, "v1:" + Base64.getEncoder().encodeToString(b), StandardCharsets.UTF_8);
    }

    private static String ivHex(Path vaultFile) throws Exception {
        byte[] b = blob(vaultFile);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(String.format("%02x", b[i]));
        }
        return sb.toString();
    }

    private static Thread accountThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("netease-account".equals(t.getName()) && t.isAlive()) {
                return t;
            }
        }
        return null;
    }

    private static String describeAccountThread() {
        Thread t = accountThread();
        return t == null ? "<不存在>" : ("alive=true, daemon=" + t.isDaemon() + ", state=" + t.getState());
    }

    private static long millis(ThrowingRunnable r) throws Exception {
        long t0 = System.nanoTime();
        r.run();
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------ 断言/汇报

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + what + "  → " + detail);
        } else {
            failed++;
            FAILED_ITEMS.add(what + " → " + detail);
            System.out.println("  [FAIL] " + what + "  → " + detail);
        }
    }

    private static void skip(String what, String reason) {
        skipped++;
        System.out.println("  [SKIP] " + what + "  → " + reason);
    }

    private static void finish() {
        System.out.println("=== 汇总：PASS " + passed + " / FAIL " + failed + " / SKIP " + skipped + " ===");
        // ASCII 哨兵行：脚本用它取计数，不受控制台编码影响
        System.out.println("[SMOKE] SUMMARY PASS=" + passed + " FAIL=" + failed + " SKIP=" + skipped);
        if (!FAILED_ITEMS.isEmpty()) {
            System.out.println("失败项：");
            for (String s : FAILED_ITEMS) {
                System.out.println("  - " + s);
            }
        }
        if (keepTmp) {
            System.out.println("[EVIDENCE] --keep：工作目录保留在 " + tmp + "（供 grep 脱敏证据，用完请自行删除）");
        } else {
            try {
                // 清掉临时工作目录（key/日志/密文都在 %TEMP% 下的独立目录里）
                deleteRecursively(tmp);
            } catch (Throwable ignored) {
                // 清理失败不影响结论
            }
        }
        if (failed > 0) {
            System.out.println("结论：存在失败断言（exit 1）");
            System.exit(1);
        }
        System.out.println("结论：全部通过（exit 0）");
        System.exit(0);
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            List<Path> list = s.sorted(Comparator.reverseOrder()).toList();
            for (Path p : list) {
                Files.deleteIfExists(p);
            }
        }
    }
}
