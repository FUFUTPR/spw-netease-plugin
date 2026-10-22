import com.example.netease.net.Dto;
import com.example.netease.net.NeteaseApi;
import com.example.netease.svc.AccountService;
import com.example.netease.ui.controller.LoginController;
import com.example.netease.ui.controller.LoginController.Mode;
import com.example.netease.ui.controller.LoginController.Phase;
import com.example.netease.ui.controller.LoginController.State;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * LoginController 离线探针（批次 3，契约 build/_tmp/batch3-contracts.md「队友 1」）。
 *
 * <p>默认包、纯 main：不依赖 JUnit / Android / 宿主环境。全部断言通过时打印
 * {@code LOGIN_CONTROLLER_RESULT=<n> PASS} 并以 0 退出；任一断言失败打印 {@code FAIL ...}
 * 并以 1 退出。</p>
 *
 * <p>覆盖 15 组：构造初值 / open 幂等 / tick 节流 / 二维码映射 / 旧登录态 / 扫码成功 /
 * 风控迁移 / 切档 / 刷新去重 / 发码 / 短信登录 / 验证页与重试 / 关闭 / 异步通道 /
 * 每次打开的登录判据（REVIEW issue 3 回归）。</p>
 */
public final class LoginControllerProbe {

    private static int passed;

    public static void main(String[] args) {
        try {
            group1Constructor();
            group2Open();
            group3TickThrottle();
            group4QrMapping();
            group5AlreadyLoggedIn();
            group6QrLoginSuccess();
            group7RiskMigration();
            group8SwitchTo();
            group9RefreshQr();
            group10SendCode();
            group11SubmitCode();
            group12VerifyAndRetry();
            group13Close();
            group14AsyncChannel();
            group15OpenSessionVerdict();
        } catch (Throwable t) {
            System.out.println("FAIL " + t.getMessage());
            t.printStackTrace(System.out);
            System.exit(1);
            return;
        }
        System.out.println("LOGIN_CONTROLLER_RESULT=" + passed + " PASS");
    }

    // ------------------------------------------------------------------ 断言基建

    private static void check(String label, boolean ok) {
        if (!ok) {
            throw new AssertionError(label);
        }
        passed++;
        System.out.println("PASS " + label);
    }

    private static void eq(String label, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + "：期望 [" + expected + "]，实际 [" + actual + "]");
        }
        passed++;
        System.out.println("PASS " + label);
    }

    private static AccountService.LoginStatus status(AccountService.State state, String nickname) {
        return new AccountService.LoginStatus(state, "", nickname == null ? "" : nickname, 0L, false, "", 0L);
    }

    private static NeteaseApi.LoginOutcome outcome(boolean ok, int code, String message, String verifyUrl) {
        return new NeteaseApi.LoginOutcome(ok, code, message, verifyUrl);
    }

    // ------------------------------------------------------------------ G1 构造

    private static void group1Constructor() {
        Fixture f = new Fixture();
        State s = f.s();
        eq("G1-01 构造 phase=IDLE", Phase.IDLE, s.phase());
        eq("G1-02 构造 mode=QR", Mode.QR, s.mode());
        eq("G1-03 构造 message 为空串", "", s.message());
        check("G1-04 构造 qrPng=null", s.qrPng() == null);
        eq("G1-05 构造 cooldownLeft=0", 0, s.cooldownLeft());
        eq("G1-06 构造 riskUrl 为空串", "", s.riskUrl());
        eq("G1-07 构造 loginVia 为空串", "", s.loginVia());
        check("G1-08 构造 loggedIn=false", !s.loggedIn());
        check("G1-09 构造 wasLoggedInAtOpen=false", !s.wasLoggedInAtOpen());
        check("G1-10 构造后未打开", !f.c.isOpen());
        check("G1-11 构造时不通知 Listener", f.events.isEmpty());
        eq("G1-12 构造读 1 次 status", 1, f.ports.statusCalls);
        eq("G1-13 构造读 1 次 wasLoggedInAtOpen", 1, f.ports.wasLoggedInCalls);

        FakePorts p = new FakePorts();
        p.statusValue = status(AccountService.State.LOGGED_IN, "旧账号");
        p.wasLoggedIn = true;
        Fixture g = new Fixture(p);
        check("G1-14 status=LOGGED_IN ⇒ loggedIn=true", g.s().loggedIn());
        check("G1-15 端口 wasLoggedInAtOpen=true ⇒ State 快照为 true", g.s().wasLoggedInAtOpen());
    }

    // ------------------------------------------------------------------ G2 open

    private static void group2Open() {
        Fixture f = new Fixture();
        f.c.open();
        State s = f.s();
        check("G2-01 open 后 isOpen=true", f.c.isOpen());
        eq("G2-02 open 后 phase=WAITING_QR", Phase.WAITING_QR, s.phase());
        eq("G2-03 open 后 mode=QR", Mode.QR, s.mode());
        eq("G2-04 open 文案", "正在获取二维码…", s.message());
        check("G2-05 open 后 qrPng=null", s.qrPng() == null);
        check("G2-06 open 后 busy=false", !s.busy());
        eq("G2-07 open 调 startQr 恰好 1 次", 1, f.ports.startQrCalls);
        check("G2-08 open 的 startQr(force=false)", !f.ports.lastStartQrForce);
        eq("G2-09 open 通知 Listener 1 次", 1, f.events.size());
        eq("G2-10 通知快照 phase=WAITING_QR", Phase.WAITING_QR, f.events.get(0).phase());

        f.c.open();
        eq("G2-11 重复 open 不再取码", 1, f.ports.startQrCalls);
        eq("G2-12 重复 open 再通知 1 次", 2, f.events.size());
        eq("G2-13 重复 open 后 phase 不变", Phase.WAITING_QR, f.s().phase());
        check("G2-14 重复 open 后仍 isOpen", f.c.isOpen());

        Fixture g = new Fixture();
        g.ports.startQrError = new IllegalStateException("boom");
        g.c.open();
        eq("G2-15 取码抛异常不穿出 ⇒ phase=ERROR", Phase.ERROR, g.s().phase());
        eq("G2-16 取码失败文案", "二维码获取失败，请重试", g.s().message());
        check("G2-17 取码失败仍算已打开", g.c.isOpen());
        eq("G2-18 取码失败通知 Listener", 1, g.events.size());
    }

    // ------------------------------------------------------------------ G3 tick 节流

    private static void group3TickThrottle() {
        Fixture f = new Fixture();
        f.ports.now = 0L;
        f.ports.statusValue = status(AccountService.State.NONE, "");
        int base = f.ports.statusCalls;
        f.c.open();
        f.c.tick();
        eq("G3-01 首次 tick 读 status", base + 1, f.ports.statusCalls);
        f.c.tick();
        eq("G3-02 同毫秒重复 tick 不重复读", base + 1, f.ports.statusCalls);
        f.ports.now = 500L;
        f.c.tick();
        eq("G3-03 500ms 内仍不读", base + 1, f.ports.statusCalls);
        f.ports.now = 999L;
        f.c.tick();
        eq("G3-04 999ms 内仍不读", base + 1, f.ports.statusCalls);
        f.ports.now = 1000L;
        f.c.tick();
        eq("G3-05 满 1000ms 读第 2 次", base + 2, f.ports.statusCalls);

        Fixture idle = new Fixture();
        idle.c.tick();
        eq("G3-06 未打开 tick 不读 status", 1, idle.ports.statusCalls);
        eq("G3-07 未打开 tick 不读 qrImage", 0, idle.ports.qrCalls);
    }

    // ------------------------------------------------------------------ G4 二维码映射

    private static void group4QrMapping() {
        Fixture f = new Fixture();
        f.ports.now = 0L;
        f.ports.statusValue = status(AccountService.State.WAITING_SCAN, "");
        byte[] png1 = new byte[] {1, 2, 3};
        f.ports.qr = new Dto.QrSession("k1", png1);
        f.c.open();
        f.c.tick();
        State s = f.s();
        eq("G4-01 WAITING_SCAN+图 ⇒ QR_READY", Phase.QR_READY, s.phase());
        eq("G4-02 扫码文案", "用网易云音乐 App 扫一下这个码", s.message());
        eq("G4-03 qrKey=k1", "k1", s.qrKey());
        check("G4-04 qrPng 内容一致", Arrays.equals(png1, s.qrPng()));
        check("G4-05 有图时 qrExpired=false", !s.qrExpired());

        f.ports.now = 1000L;
        f.c.tick();
        eq("G4-06 同 key 不重复换图（key 稳定）", "k1", f.s().qrKey());
        check("G4-07 同 key 图仍在", Arrays.equals(png1, f.s().qrPng()));

        byte[] png2 = new byte[] {9, 8};
        f.ports.qr = new Dto.QrSession("k2", png2);
        f.ports.now = 2000L;
        f.c.tick();
        eq("G4-08 key 变 ⇒ qrKey 更新", "k2", f.s().qrKey());
        check("G4-09 key 变 ⇒ qrPng 更新", Arrays.equals(png2, f.s().qrPng()));

        f.ports.qr = null;
        f.ports.now = 3000L;
        f.c.tick();
        eq("G4-10 无图 ⇒ WAITING_QR", Phase.WAITING_QR, f.s().phase());
        eq("G4-11 无图文案", "正在获取二维码…", f.s().message());

        f.ports.statusValue = status(AccountService.State.WAITING_CONFIRM, "");
        f.ports.now = 4000L;
        f.c.tick();
        eq("G4-12 WAITING_CONFIRM ⇒ SCANNED", Phase.SCANNED, f.s().phase());
        eq("G4-13 已扫码文案", "已扫码，请在手机上确认", f.s().message());

        f.ports.statusValue = status(AccountService.State.EXPIRED, "");
        f.ports.now = 5000L;
        f.c.tick();
        eq("G4-14 EXPIRED ⇒ phase=EXPIRED", Phase.EXPIRED, f.s().phase());
        check("G4-15 EXPIRED ⇒ qrExpired=true", f.s().qrExpired());
        eq("G4-16 过期文案", "二维码已过期，点「刷新二维码」", f.s().message());

        f.ports.statusValue = new AccountService.LoginStatus(AccountService.State.ERROR, "网络开小差了", "", 0L,
                false, "", 0L);
        f.ports.now = 6000L;
        f.c.tick();
        eq("G4-17 ERROR 用 status.message", "网络开小差了", f.s().message());
        eq("G4-18 ERROR ⇒ phase=ERROR", Phase.ERROR, f.s().phase());

        f.ports.statusValue = new AccountService.LoginStatus(AccountService.State.ERROR, null, "", 0L, false, "", 0L);
        f.ports.now = 7000L;
        f.c.tick();
        eq("G4-19 ERROR 空 message 回退固定文案", "二维码获取失败，请重试", f.s().message());

        Fixture g = new Fixture();
        g.ports.now = 0L;
        g.ports.statusValue = status(AccountService.State.NONE, "");
        g.ports.qr = new Dto.QrSession("n1", new byte[] {7});
        g.c.open();
        g.c.tick();
        eq("G4-20 NONE+图也进 QR_READY", Phase.QR_READY, g.s().phase());
    }

    // ------------------------------------------------------------------ G5 打开前已登录

    private static void group5AlreadyLoggedIn() {
        FakePorts p = new FakePorts();
        p.statusValue = status(AccountService.State.LOGGED_IN, "旧账号");
        p.wasLoggedIn = true;
        Fixture f = new Fixture(p);
        f.ports.now = 0L;
        f.c.open();
        f.c.tick();
        State s = f.s();
        eq("G5-01 旧登录态 phase=QR_READY", Phase.QR_READY, s.phase());
        eq("G5-02 旧登录态文案", "当前已经登录，无需重复扫码；如需换号请先退出登录", s.message());
        check("G5-03 旧登录态不关窗", f.c.isOpen());
        check("G5-04 旧登录态 loggedIn=true", s.loggedIn());
        eq("G5-05 旧登录态不调 pauseQrWatch", 0, f.ports.pauseCalls);
        eq("G5-06 旧登录态不调 closeVerify", 0, f.ports.closeVerifyCalls);
        eq("G5-07 旧登录态不判扫码成功（loginVia 空）", "", s.loginVia());
        eq("G5-08 open 仍取码 1 次", 1, f.ports.startQrCalls);
    }

    // ------------------------------------------------------------------ G6 扫码成功

    private static void group6QrLoginSuccess() {
        Fixture f = new Fixture();
        f.ports.now = 0L;
        f.ports.statusValue = status(AccountService.State.WAITING_SCAN, "");
        f.ports.qr = new Dto.QrSession("k1", new byte[] {1});
        f.c.open();
        f.c.tick();
        f.ports.statusValue = status(AccountService.State.LOGGED_IN, "小明");
        f.ports.now = 1000L;
        f.c.tick();
        State s = f.s();
        eq("G6-01 扫码成功 phase=LOGGED_IN", Phase.LOGGED_IN, s.phase());
        eq("G6-02 loginVia=扫码", "扫码", s.loginVia());
        eq("G6-03 成功文案带昵称", "登录成功：小明", s.message());
        check("G6-04 loggedIn=true", s.loggedIn());
        check("G6-05 成功后自动关窗", !f.c.isOpen());
        check("G6-06 成功后 busy=false", !s.busy());
        check("G6-07 成功后 qrPng 清空", s.qrPng() == null);
        eq("G6-08 成功后 pauseQrWatch 1 次", 1, f.ports.pauseCalls);
        eq("G6-09 成功后 closeVerify 1 次", 1, f.ports.closeVerifyCalls);
        eq("G6-10 Listener 收到最终态", Phase.LOGGED_IN, f.events.get(f.events.size() - 1).phase());
        int calls = f.ports.statusCalls;
        f.c.tick();
        eq("G6-11 成功后 tick 不再读 status", calls, f.ports.statusCalls);

        Fixture g = new Fixture();
        g.ports.now = 0L;
        g.ports.statusValue = status(AccountService.State.WAITING_SCAN, "");
        g.c.open();
        g.c.tick();
        g.ports.statusValue = status(AccountService.State.LOGGED_IN, "");
        g.ports.now = 1000L;
        g.c.tick();
        eq("G6-12 昵称为空用「当前账号」", "登录成功：当前账号", g.s().message());
    }

    // ------------------------------------------------------------------ G7 风控迁移

    private static void group7RiskMigration() {
        Fixture f = new Fixture();
        f.ports.now = 0L;
        f.ports.statusValue = status(AccountService.State.NONE, "");
        f.ports.risk = true;
        f.ports.verifyUrl = "https://verify.example/qr";
        f.c.open();
        f.c.tick();
        State s = f.s();
        eq("G7-01 风控 ⇒ mode=RISK", Mode.RISK, s.mode());
        eq("G7-02 风控 ⇒ phase=RISK", Phase.RISK, s.phase());
        check("G7-03 riskFromQr=true", s.riskFromQr());
        eq("G7-04 riskUrl 落库", "https://verify.example/qr", s.riskUrl());
        check("G7-05 verifyOpened=true", s.verifyOpened());
        eq("G7-06 自动 openVerify 1 次", 1, f.ports.openVerifyCalls);
        eq("G7-07 自动 openVerify 地址", "https://verify.example/qr", f.ports.lastVerifyUrl);
        check("G7-08 自动 openVerify fromQr=true", f.ports.lastVerifyFromQr);
        eq("G7-09 验证页已打开文案", "已打开验证页面，完成后点「我已完成，重试」", s.message());
        check("G7-10 风控迁移 busy=false", !s.busy());

        f.c.switchTo(Mode.QR);
        eq("G7-11 回扫码档重新取码", 2, f.ports.startQrCalls);
        f.ports.now = 2000L;
        f.c.tick();
        eq("G7-12 再次风控仍进 RISK", Phase.RISK, f.s().phase());
        eq("G7-13 已 verifyOpened 不重复 openVerify", 1, f.ports.openVerifyCalls);
        eq("G7-14 不重开时提示行为验证", "登录需要先完成行为验证", f.s().message());

        Fixture g = new Fixture();
        g.ports.now = 0L;
        g.ports.statusValue = status(AccountService.State.NONE, "");
        g.ports.risk = true;
        g.ports.verifyUrl = "";
        g.c.open();
        g.c.tick();
        eq("G7-15 验证地址为空不迁移（仍 QR）", Mode.QR, g.s().mode());
        eq("G7-16 验证地址为空不 openVerify", 0, g.ports.openVerifyCalls);
    }

    // ------------------------------------------------------------------ G8 切档

    private static void group8SwitchTo() {
        Fixture f = new Fixture();
        f.ports.now = 0L;
        f.ports.statusValue = status(AccountService.State.NONE, "");
        f.c.open();
        f.c.tick();
        int statusBase = f.ports.statusCalls;
        int startBase = f.ports.startQrCalls;

        f.c.switchTo(Mode.SMS);
        State s = f.s();
        eq("G8-01 切短信 mode=SMS", Mode.SMS, s.mode());
        eq("G8-02 切短信 phase=IDLE", Phase.IDLE, s.phase());
        eq("G8-03 切短信清空文案", "", s.message());
        check("G8-04 切短信清空 qrPng", s.qrPng() == null);
        eq("G8-05 切短信清空 qrKey", "", s.qrKey());
        eq("G8-06 切短信不取码", startBase, f.ports.startQrCalls);

        f.ports.now = 2000L;
        f.c.tick();
        eq("G8-07 短信档 tick 不读 status", statusBase, f.ports.statusCalls);

        f.c.switchTo(Mode.QR);
        eq("G8-08 切回扫码 mode=QR", Mode.QR, f.s().mode());
        eq("G8-09 切回扫码 phase=WAITING_QR", Phase.WAITING_QR, f.s().phase());
        eq("G8-10 切回扫码文案", "正在获取二维码…", f.s().message());
        eq("G8-11 切回扫码 startQr 1 次", startBase + 1, f.ports.startQrCalls);
        check("G8-12 切回扫码 force=false", !f.ports.lastStartQrForce);

        f.c.switchTo(Mode.QR);
        eq("G8-13 重复切同档不重复取码", startBase + 1, f.ports.startQrCalls);

        f.c.switchTo(Mode.RISK);
        eq("G8-14 切风控 mode=RISK", Mode.RISK, f.s().mode());
        eq("G8-15 切风控 phase=RISK", Phase.RISK, f.s().phase());
        check("G8-16 切风控清 qrPng", f.s().qrPng() == null);

        f.c.switchTo(null);
        eq("G8-17 switchTo(null) 忽略", Mode.RISK, f.s().mode());

        Fixture g = new Fixture();
        g.c.switchTo(Mode.SMS);
        g.c.close();
        g.c.switchTo(Mode.QR);
        eq("G8-18 未 open 时 switchTo(QR) 被忽略", Mode.SMS, g.s().mode());
        eq("G8-19 未 open 时 switchTo(QR) 不取码", 0, g.ports.startQrCalls);
    }

    // ------------------------------------------------------------------ G9 刷新去重

    private static void group9RefreshQr() {
        Fixture f = new Fixture();
        f.ports.now = 0L;
        f.ports.statusValue = status(AccountService.State.WAITING_SCAN, "");
        f.ports.qr = new Dto.QrSession("k1", new byte[] {1});
        f.c.open();
        f.c.tick();
        eq("G9-01 预置二维码就绪", Phase.QR_READY, f.s().phase());

        f.c.refreshQr();
        eq("G9-02 2 秒内刷新只提示", "刚刷新过，请稍候再试", f.s().message());
        eq("G9-03 2 秒内刷新不取码", 1, f.ports.startQrCalls);

        f.ports.now = 1999L;
        f.c.refreshQr();
        eq("G9-04 1999ms 仍不取码", 1, f.ports.startQrCalls);

        f.ports.now = 2000L;
        f.c.refreshQr();
        eq("G9-05 满 2 秒刷新取码 1 次", 2, f.ports.startQrCalls);
        check("G9-06 刷新 force=true", f.ports.lastStartQrForce);
        check("G9-07 新码清空旧图", f.s().qrPng() == null);
        eq("G9-08 刷新后 phase=WAITING_QR", Phase.WAITING_QR, f.s().phase());
        eq("G9-09 刷新后文案", "正在获取二维码…", f.s().message());

        int sc = f.ports.statusCalls;
        f.c.tick();
        eq("G9-10 刷新后下一拍立即读 status", sc + 1, f.ports.statusCalls);

        byte[] png2 = new byte[] {2, 4};
        f.ports.qr = new Dto.QrSession("k2", png2);
        f.ports.now = 3000L;
        f.c.tick();
        eq("G9-11 刷新后新图到位", "k2", f.s().qrKey());

        f.ports.startQrResult = false;
        f.ports.now = 4000L;
        f.c.refreshQr();
        eq("G9-12 复用路径也取码", 3, f.ports.startQrCalls);
        eq("G9-13 复用路径 phase=WAITING_QR", Phase.WAITING_QR, f.s().phase());
        check("G9-14 复用路径保留旧图", Arrays.equals(png2, f.s().qrPng()));

        f.ports.startQrError = new IllegalStateException("net down");
        f.ports.now = 6000L;
        f.c.refreshQr();
        eq("G9-15 刷新异常 ⇒ phase=ERROR", Phase.ERROR, f.s().phase());
        eq("G9-16 刷新异常文案", "二维码获取失败，请重试", f.s().message());
    }

    // ------------------------------------------------------------------ G10 发码

    private static void group10SendCode() {
        Fixture bad = new Fixture();
        bad.c.sendCode("12345");
        eq("G10-01 非法手机号文案", "请输入 11 位手机号", bad.s().message());
        eq("G10-02 非法手机号不发请求", 0, bad.ports.sendCodeCalls);
        eq("G10-03 非法手机号不排队异步", 0, bad.async.pending());
        bad.c.sendCode(null);
        eq("G10-04 null 手机号也拦截", "请输入 11 位手机号", bad.s().message());

        Fixture f = new Fixture();
        f.ports.now = 0L;
        f.c.open();
        f.c.switchTo(Mode.SMS);
        f.c.sendCode("13800138000");
        eq("G10-05 发送中 phase=SENDING", Phase.SENDING, f.s().phase());
        check("G10-06 发送中 busy=true", f.s().busy());
        eq("G10-07 发送中文案", "正在发送验证码…", f.s().message());
        eq("G10-08 异步通道排队 1 个", 1, f.async.pending());
        eq("G10-09 未 flush 前端口没被调用", 0, f.ports.sendCodeCalls);

        f.async.flush();
        State s = f.s();
        eq("G10-10 发送成功 phase=CODE_SENT", Phase.CODE_SENT, s.phase());
        check("G10-11 发送成功 busy=false", !s.busy());
        eq("G10-12 发送成功 cooldownLeft=60", 60, s.cooldownLeft());
        eq("G10-13 发送成功文案带掩码", "验证码已发送到 138****0000", s.message());
        eq("G10-14 端口记录手机号", "13800138000", f.ports.lastSendPhone);
        eq("G10-15 端口记录来源", "原生登录页", f.ports.lastSendSource);

        f.c.sendCode("13800138000");
        eq("G10-16 冷却期拦截文案", "请等待 60 秒后再获取验证码", f.s().message());
        eq("G10-17 冷却期不再发请求", 1, f.ports.sendCodeCalls);
        eq("G10-18 冷却期不排队", 0, f.async.pending());

        f.ports.now = 1000L;
        f.c.tick();
        eq("G10-19 冷却按 tick 递减 60→59", 59, f.s().cooldownLeft());
        f.c.sendCode("13800138000");
        eq("G10-20 冷却 59 秒时文案", "请等待 59 秒后再获取验证码", f.s().message());
        f.ports.now = 2000L;
        f.c.tick();
        eq("G10-21 冷却继续递减 59→58", 58, f.s().cooldownLeft());
        f.c.switchTo(Mode.QR);
        eq("G10-22 切换模式不清冷却", 58, f.s().cooldownLeft());

        Fixture g = new Fixture();
        g.ports.now = 0L;
        g.c.open();
        g.ports.smsResult = new Dto.SmsResult(400, "发送太频繁");
        g.c.sendCode("13800138000");
        g.async.flush();
        eq("G10-23 发送失败 phase=ERROR", Phase.ERROR, g.s().phase());
        eq("G10-24 发送失败用伺服端原话", "发送失败：发送太频繁", g.s().message());
        check("G10-25 发送失败 busy=false", !g.s().busy());
        eq("G10-26 发送失败不设冷却", 0, g.s().cooldownLeft());

        Fixture h = new Fixture();
        h.ports.now = 0L;
        h.c.open();
        h.ports.smsResult = new Dto.SmsResult(500, null);
        h.c.sendCode("13800138000");
        h.async.flush();
        eq("G10-27 失败无文案回退 code", "发送失败：code=500", h.s().message());

        Fixture i = new Fixture();
        i.ports.now = 0L;
        i.c.open();
        i.ports.smsError = new RuntimeException("boom");
        i.c.sendCode("13800138000");
        i.async.flush();
        eq("G10-28 发送异常文案", "发送验证码失败，请重试", i.s().message());
        eq("G10-29 发送异常 phase=ERROR", Phase.ERROR, i.s().phase());
        check("G10-30 发送异常 busy=false", !i.s().busy());

        Fixture j = new Fixture();
        j.ports.now = 0L;
        j.c.open();
        j.c.sendCode("13800138000");
        j.c.sendCode("13800138000");
        eq("G10-31 busy 时重复点击只排队 1 个", 1, j.async.pending());
        j.async.flush();
        eq("G10-32 busy 时重复点击只发 1 次", 1, j.ports.sendCodeCalls);
    }

    // ------------------------------------------------------------------ G11 短信登录

    private static void group11SubmitCode() {
        Fixture f = new Fixture();
        f.ports.now = 0L;
        f.c.submitCode("123", "1234");
        eq("G11-01 非法手机号文案", "请输入 11 位手机号", f.s().message());
        eq("G11-02 非法手机号不登录", 0, f.ports.loginCalls);
        f.c.submitCode("13800138000", "12");
        eq("G11-03 非法验证码文案", "请输入 4-6 位验证码", f.s().message());
        eq("G11-04 非法验证码不登录", 0, f.ports.loginCalls);
        f.c.submitCode(null, null);
        eq("G11-05 null 入参先拦手机号", "请输入 11 位手机号", f.s().message());

        Fixture g = new Fixture();
        g.ports.now = 0L;
        g.ports.statusValue = status(AccountService.State.LOGGED_IN, "小明");
        g.c.open();
        g.c.submitCode("13800138000", "1234");
        eq("G11-06 登录中 phase=LOGGING_IN", Phase.LOGGING_IN, g.s().phase());
        check("G11-07 登录中 busy=true", g.s().busy());
        eq("G11-08 登录中文案", "正在登录…", g.s().message());
        eq("G11-09 异步排队 1 个", 1, g.async.pending());
        eq("G11-10 未 flush 前端口没被调用", 0, g.ports.loginCalls);
        g.async.flush();
        State s = g.s();
        eq("G11-11 登录成功 phase=LOGGED_IN", Phase.LOGGED_IN, s.phase());
        eq("G11-12 loginVia=验证码", "验证码", s.loginVia());
        eq("G11-13 成功文案带昵称", "登录成功：小明", s.message());
        check("G11-14 loggedIn=true", s.loggedIn());
        check("G11-15 成功后 busy=false", !s.busy());
        check("G11-16 成功后关窗", !g.c.isOpen());
        eq("G11-17 成功后 pauseQrWatch 1 次", 1, g.ports.pauseCalls);
        eq("G11-18 成功后 closeVerify 1 次", 1, g.ports.closeVerifyCalls);
        eq("G11-19 端口记录手机号", "13800138000", g.ports.lastLoginPhone);
        eq("G11-20 端口记录验证码", "1234", g.ports.lastLoginCode);
        eq("G11-21 端口记录来源", "原生登录页", g.ports.lastLoginSource);

        Fixture r = new Fixture();
        r.ports.now = 0L;
        r.ports.loginOutcome = outcome(false, 8821, "需要验证", "");
        r.c.submitCode("13800138000", "1234");
        r.async.flush();
        eq("G11-22 风控无地址 mode=RISK", Mode.RISK, r.s().mode());
        eq("G11-23 风控无地址 phase=RISK", Phase.RISK, r.s().phase());
        check("G11-24 风控无地址 riskFromQr=false", !r.s().riskFromQr());
        eq("G11-25 风控无地址 riskUrl 为空", "", r.s().riskUrl());
        eq("G11-26 风控无地址文案", "需要完成行为验证", r.s().message());
        eq("G11-27 无地址不 openVerify", 0, r.ports.openVerifyCalls);

        Fixture r2 = new Fixture();
        r2.ports.now = 0L;
        r2.ports.loginOutcome = outcome(false, 8821, "需要验证", "https://verify.example/sms");
        r2.c.submitCode("13800138000", "1234");
        r2.async.flush();
        eq("G11-28 风控有地址自动 openVerify", 1, r2.ports.openVerifyCalls);
        check("G11-29 风控有地址 fromQr=false", !r2.ports.lastVerifyFromQr);
        eq("G11-30 风控有地址落库", "https://verify.example/sms", r2.s().riskUrl());
        eq("G11-31 风控有地址文案", "已打开验证页面，完成后点「我已完成，重试」", r2.s().message());

        Fixture b = new Fixture();
        b.ports.now = 0L;
        b.ports.loginOutcome = outcome(false, 501, "x", "");
        b.c.submitCode("13800138000", "1234");
        b.async.flush();
        eq("G11-32 账号/验证码错文案", "手机号或验证码不对", b.s().message());
        eq("G11-33 账号/验证码错 phase=ERROR", Phase.ERROR, b.s().phase());

        Fixture o = new Fixture();
        o.ports.now = 0L;
        o.ports.loginOutcome = outcome(false, 500, "账号异常，请稍后", "");
        o.c.submitCode("13800138000", "1234");
        o.async.flush();
        eq("G11-34 其它错误用伺服端原话", "账号异常，请稍后", o.s().message());

        Fixture n = new Fixture();
        n.ports.now = 0L;
        n.ports.loginOutcome = outcome(false, 500, null, "");
        n.c.submitCode("13800138000", "1234");
        n.async.flush();
        eq("G11-35 其它错误无文案回退", "登录失败，请重试", n.s().message());

        Fixture e = new Fixture();
        e.ports.now = 0L;
        e.ports.loginError = new RuntimeException("boom");
        e.c.submitCode("13800138000", "1234");
        e.async.flush();
        eq("G11-36 登录异常文案", "登录失败，请重试", e.s().message());
        check("G11-37 登录异常 busy=false", !e.s().busy());

        Fixture d = new Fixture();
        d.ports.now = 0L;
        d.c.submitCode("13800138000", "1234");
        d.c.submitCode("13800138000", "4321");
        eq("G11-38 busy 时重复提交只排队 1 个", 1, d.async.pending());
        d.async.flush();
        eq("G11-39 busy 时重复提交只登录 1 次", 1, d.ports.loginCalls);
    }

    // ------------------------------------------------------------------ G12 验证页 / 重试

    private static void group12VerifyAndRetry() {
        Fixture f = new Fixture();
        f.ports.now = 0L;
        f.ports.statusValue = status(AccountService.State.NONE, "");
        f.c.open();
        f.c.openVerify();
        eq("G12-01 空地址文案", "验证地址为空，请返回重新获取", f.s().message());
        eq("G12-02 空地址不调端口", 0, f.ports.openVerifyCalls);

        f.ports.loginOutcome = outcome(false, 8821, "", "https://verify.example/sms");
        f.c.submitCode("13800138000", "1234");
        f.async.flush();
        eq("G12-03 风控地址落库", "https://verify.example/sms", f.s().riskUrl());
        eq("G12-04 自动打开 1 次", 1, f.ports.openVerifyCalls);

        f.c.openVerify();
        eq("G12-05 用户再点仍允许打开", 2, f.ports.openVerifyCalls);
        eq("G12-06 再打开仍提示已打开", "已打开验证页面，完成后点「我已完成，重试」", f.s().message());

        f.c.retryAfterVerify();
        State s = f.s();
        eq("G12-07 重试调 retryQrAfterRisk", 1, f.ports.retryQrAfterCalls);
        eq("G12-08 重试回扫码档", Mode.QR, s.mode());
        check("G12-09 重试清 riskFromQr", !s.riskFromQr());
        eq("G12-10 重试清 riskUrl", "", s.riskUrl());
        check("G12-11 重试清 verifyOpened", !s.verifyOpened());
        eq("G12-12 重试 phase=WAITING_QR", Phase.WAITING_QR, s.phase());
        eq("G12-13 重试文案", "正在重新获取登录状态…", s.message());

        int sc = f.ports.statusCalls;
        f.ports.now = 1000L;
        f.c.tick();
        eq("G12-14 重试后下一拍立即读 status", sc + 1, f.ports.statusCalls);
    }

    // ------------------------------------------------------------------ G13 关闭

    private static void group13Close() {
        FakePorts p = new FakePorts();
        p.statusValue = status(AccountService.State.LOGGED_IN, "旧账号");
        p.wasLoggedIn = true;
        Fixture f = new Fixture(p);
        f.ports.now = 0L;
        f.c.open();
        int eventsAfterOpen = f.events.size();
        f.c.close();
        State s = f.s();
        check("G13-01 close 后 isOpen=false", !f.c.isOpen());
        eq("G13-02 close 后 phase=CLOSED", Phase.CLOSED, s.phase());
        check("G13-03 close 后 busy=false", !s.busy());
        check("G13-04 close 后 qrPng=null", s.qrPng() == null);
        eq("G13-05 close 调 pauseQrWatch 1 次", 1, f.ports.pauseCalls);
        eq("G13-06 close 调 closeVerify 1 次", 1, f.ports.closeVerifyCalls);
        check("G13-07 close 不是退出账号（loggedIn 仍 true）", s.loggedIn());
        eq("G13-08 close 保留最后一句文案", "正在获取二维码…", s.message());
        eq("G13-09 close 通知 Listener", eventsAfterOpen + 1, f.events.size());

        int events = f.events.size();
        f.c.close();
        eq("G13-10 重复 close 不再调 pauseQrWatch", 1, f.ports.pauseCalls);
        eq("G13-11 重复 close 不再调 closeVerify", 1, f.ports.closeVerifyCalls);
        eq("G13-12 重复 close 不再通知", events, f.events.size());

        int sc = f.ports.statusCalls;
        f.c.tick();
        eq("G13-13 close 后 tick 不再读 status", sc, f.ports.statusCalls);

        Fixture g = new Fixture();
        g.ports.now = 0L;
        g.ports.statusValue = status(AccountService.State.LOGGED_IN, "小明");
        g.ports.loginOutcome = outcome(true, 200, "ok", "");
        g.c.open();
        g.c.submitCode("13800138000", "1234");
        g.async.flush();
        eq("G13-14 登录成功已收尾 phase=LOGGED_IN", Phase.LOGGED_IN, g.s().phase());
        g.c.close();
        eq("G13-15 成功后再 close 不重复 pause", 1, g.ports.pauseCalls);
        eq("G13-16 成功后再 close 不重复 closeVerify", 1, g.ports.closeVerifyCalls);
        eq("G13-17 成功后再 close 不改变已登录态", Phase.LOGGED_IN, g.s().phase());

        Fixture h = new Fixture();
        h.ports.now = 0L;
        h.c.open();
        h.c.switchTo(Mode.SMS);
        h.c.sendCode("13800138000");
        h.async.flush();
        eq("G13-18 预置冷却 60", 60, h.s().cooldownLeft());
        h.c.close();
        eq("G13-19 close 保留冷却", 60, h.s().cooldownLeft());
        eq("G13-20 close 保留成功文案", "验证码已发送到 138****0000", h.s().message());
    }

    // ------------------------------------------------------------------ G14 异步通道

    private static void group14AsyncChannel() {
        Fixture f = new Fixture();
        f.ports.now = 0L;
        f.c.sendCode("13800138000");
        eq("G14-01 提交后端口尚未被调用（真异步）", 0, f.ports.sendCodeCalls);
        eq("G14-02 提交后排队 1 个任务", 1, f.async.pending());
        check("G14-03 未 flush 前 busy=true", f.s().busy());
        eq("G14-04 未 flush 前 phase=SENDING", Phase.SENDING, f.s().phase());
        f.async.flush();
        eq("G14-05 flush 后端口调用 1 次", 1, f.ports.sendCodeCalls);
        check("G14-06 flush 后 busy=false", !f.s().busy());
        eq("G14-07 flush 后 phase=CODE_SENT", Phase.CODE_SENT, f.s().phase());

        Fixture g = new Fixture();
        g.ports.now = 0L;
        g.ports.statusValue = status(AccountService.State.LOGGED_IN, "小明");
        g.c.submitCode("13800138000", "1234");
        eq("G14-08 登录未 flush 前端口未调用", 0, g.ports.loginCalls);
        check("G14-09 登录未 flush 前 busy=true", g.s().busy());
        g.async.flush();
        eq("G14-10 登录 flush 后端口调用 1 次", 1, g.ports.loginCalls);
        eq("G14-11 登录 flush 后 phase=LOGGED_IN", Phase.LOGGED_IN, g.s().phase());

        Fixture h = new Fixture();
        h.ports.now = 0L;
        h.c.open();
        h.c.sendCode("13800138000");
        eq("G14-12 预置在途任务", 1, h.async.pending());
        h.c.close();
        eq("G14-13 close 后 phase=CLOSED", Phase.CLOSED, h.s().phase());
        eq("G14-14 close 保留最后文案", "正在发送验证码…", h.s().message());
        h.async.flush();
        eq("G14-15 迟到回调不复活 phase", Phase.CLOSED, h.s().phase());
        eq("G14-16 迟到回调不设冷却", 0, h.s().cooldownLeft());
        check("G14-17 迟到回调不把 busy 卡住", !h.s().busy());
    }

    // ------------------------------------------------------------------ G15 每次打开的登录判据

    /**
     * 回归 REVIEW issue 3：成功判据不能冻结在构造时。
     *
     * <p>正确语义是「一次 open 一份会话」：{@code open} 时记快照，处理 {@code LOGGED_IN} 时<b>实时</b>读
     * {@code ports.wasLoggedInAtOpen()} —— true = 恢复出来的旧登录态（保持面板打开），false = 本次打开之后
     * 才真正登录成功（收尾关窗）。覆盖修正要求的四个场景：未登录后成功 / 已登录再打开恢复旧态 /
     * 已登录再完成新扫码 / 同一控制器关闭重开。</p>
     */
    private static void group15OpenSessionVerdict() {
        // ① 未登录后成功：实时判据 false ⇒ 本次打开之后的新登录，收尾关窗。
        Fixture a = new Fixture();
        a.ports.now = 0L;
        a.ports.statusValue = status(AccountService.State.WAITING_SCAN, "");
        a.c.open();
        a.c.tick();
        a.ports.statusValue = status(AccountService.State.LOGGED_IN, "小明");
        a.ports.now = 1000L;
        a.c.tick();
        State sa = a.s();
        eq("G15-01 未登录后成功 phase=LOGGED_IN", Phase.LOGGED_IN, sa.phase());
        check("G15-02 未登录后成功自动关窗", !a.c.isOpen());
        eq("G15-03 未登录后成功 loginVia=扫码", "扫码", sa.loginVia());
        eq("G15-04 未登录后成功文案", "登录成功：小明", sa.message());

        // ② 已登录、但旧登录态是打开之后才恢复的：处理 LOGGED_IN 时实时读数已翻成 true ⇒ 保持面板打开。
        FakePorts bp = new FakePorts();
        bp.statusValue = status(AccountService.State.WAITING_SCAN, "");
        Fixture b = new Fixture(bp);
        b.ports.now = 0L;
        b.c.open();
        eq("G15-05 open 时重新读一次判据（构造 1 次 + open 1 次）", 2, bp.wasLoggedInCalls);
        check("G15-06 打开时快照=未登录", !b.s().wasLoggedInAtOpen());
        int callsAtOpen = bp.wasLoggedInCalls;
        bp.wasLoggedIn = true;                        // 打开之后旧登录态恢复了
        bp.statusValue = status(AccountService.State.LOGGED_IN, "旧账号");
        b.ports.now = 1000L;
        b.c.tick();
        State sb = b.s();
        eq("G15-07 恢复旧登录态 phase=QR_READY", Phase.QR_READY, sb.phase());
        eq("G15-08 恢复旧登录态文案", "当前已经登录，无需重复扫码；如需换号请先退出登录", sb.message());
        check("G15-09 恢复旧登录态不关窗", b.c.isOpen());
        check("G15-10 恢复旧登录态 loggedIn=true", sb.loggedIn());
        eq("G15-11 恢复旧登录态不判扫码成功（loginVia 空）", "", sb.loginVia());
        eq("G15-12 恢复旧登录态不调 pauseQrWatch", 0, bp.pauseCalls);
        eq("G15-13 处理 LOGGED_IN 时重新问端口（不缓存）", callsAtOpen + 1, bp.wasLoggedInCalls);
        check("G15-14 State 快照仍是打开时的值（不随实时判据抖动）", !sb.wasLoggedInAtOpen());
        b.ports.now = 2000L;
        b.c.tick();
        eq("G15-15 下一拍继续实时读端口", callsAtOpen + 2, bp.wasLoggedInCalls);
        check("G15-16 下一拍仍保持打开", b.c.isOpen());

        // ③ 已登录再完成新扫码：打开时已登录，但本次打开之后真的登录成功（loginSerial 变了）⇒ 收尾关窗。
        FakePorts cp = new FakePorts();
        cp.statusValue = status(AccountService.State.LOGGED_IN, "旧账号");
        cp.wasLoggedIn = true;
        Fixture c = new Fixture(cp);
        c.ports.now = 0L;
        c.c.open();
        check("G15-17 打开时快照=已登录", c.s().wasLoggedInAtOpen());
        cp.wasLoggedIn = false;                       // 新登录成功：活判据翻成 false
        cp.statusValue = status(AccountService.State.LOGGED_IN, "小明");
        c.ports.now = 1000L;
        c.c.tick();
        State sc = c.s();
        eq("G15-18 已登录后新扫码 phase=LOGGED_IN", Phase.LOGGED_IN, sc.phase());
        check("G15-19 已登录后新扫码自动关窗", !c.c.isOpen());
        eq("G15-20 已登录后新扫码 loginVia=扫码", "扫码", sc.loginVia());
        eq("G15-21 已登录后新扫码文案", "登录成功：小明", sc.message());
        eq("G15-22 已登录后新扫码 pauseQrWatch 1 次", 1, cp.pauseCalls);
        eq("G15-23 已登录后新扫码 closeVerify 1 次", 1, cp.closeVerifyCalls);

        // ④ 同一控制器关闭重开：快照属于各自的打开会话，上一次的判定绝不带到下一次。
        FakePorts dp = new FakePorts();
        dp.statusValue = status(AccountService.State.LOGGED_IN, "旧账号");
        dp.wasLoggedIn = true;
        Fixture d = new Fixture(dp);
        d.ports.now = 0L;
        d.c.open();
        d.c.tick();
        eq("G15-24 第一次打开 phase=QR_READY", Phase.QR_READY, d.s().phase());
        check("G15-25 第一次打开保持打开", d.c.isOpen());
        check("G15-26 第一次打开快照=已登录", d.s().wasLoggedInAtOpen());
        d.c.close();

        dp.wasLoggedIn = false;                       // 重开时环境已变成「本次打开之后才登录」
        dp.statusValue = status(AccountService.State.WAITING_SCAN, "");
        d.ports.now = 2000L;
        d.c.open();
        check("G15-27 重开后 isOpen", d.c.isOpen());
        eq("G15-28 重开后 phase=WAITING_QR", Phase.WAITING_QR, d.s().phase());
        check("G15-29 重开的快照属于新会话（未登录）", !d.s().wasLoggedInAtOpen());
        eq("G15-30 重开重新取码（startQr 共 2 次）", 2, dp.startQrCalls);
        dp.statusValue = status(AccountService.State.LOGGED_IN, "新账号");
        d.ports.now = 3000L;
        d.c.tick();
        eq("G15-31 重开后新登录 phase=LOGGED_IN", Phase.LOGGED_IN, d.s().phase());
        check("G15-32 重开后新登录自动关窗", !d.c.isOpen());
        eq("G15-33 重开后新登录文案", "登录成功：新账号", d.s().message());

        dp.wasLoggedIn = true;                        // 再开：这次打开时本来就登录着
        dp.statusValue = status(AccountService.State.LOGGED_IN, "新账号");
        d.ports.now = 4000L;
        d.c.open();
        d.c.tick();
        eq("G15-34 第三次打开 phase=QR_READY", Phase.QR_READY, d.s().phase());
        check("G15-35 第三次打开保持打开", d.c.isOpen());
        check("G15-36 第三次打开快照=已登录", d.s().wasLoggedInAtOpen());
        eq("G15-37 第三次打开不判扫码成功（loginVia 清空）", "", d.s().loginVia());
        check("G15-38 第三次打开 loggedIn=true", d.s().loggedIn());

        // ⑤ 快照必须在 startQr 之后读：生产 AccountService 正是在 startQr 里刷新开窗快照。
        FakePorts ep = new FakePorts();
        ep.wasLoggedIn = false;
        ep.wasLoggedInAfterStartQr = Boolean.TRUE;
        Fixture e = new Fixture(ep);
        e.ports.now = 0L;
        e.c.open();
        check("G15-39 open 的快照在 startQr 之后读取（拿到刷新后的值）", e.s().wasLoggedInAtOpen());

        // ⑥ 判定端口读失败时的兜底：本次打开的快照是 true ⇒ 仍按旧登录态处理，绝不误关窗。
        FakePorts fp = new FakePorts();
        fp.statusValue = status(AccountService.State.LOGGED_IN, "旧账号");
        fp.wasLoggedIn = true;
        Fixture f = new Fixture(fp);
        f.ports.now = 0L;
        f.c.open();
        fp.wasLoggedInError = new IllegalStateException("判据端口读失败");
        f.ports.now = 1000L;
        f.c.tick();
        eq("G15-40 判据读取失败时兜底用本次快照（phase=QR_READY）", Phase.QR_READY, f.s().phase());
        check("G15-41 判据读取失败时不误关窗", f.c.isOpen());

        // ⑦ 兜底只在「本次打开」有效：快照是 false 时读失败照常收尾（不会把新登录当旧态）。
        FakePorts gp = new FakePorts();
        gp.statusValue = status(AccountService.State.WAITING_SCAN, "");
        Fixture g = new Fixture(gp);
        g.ports.now = 0L;
        g.c.open();
        gp.wasLoggedInError = new IllegalStateException("判据端口读失败");
        gp.statusValue = status(AccountService.State.LOGGED_IN, "小明");
        g.ports.now = 1000L;
        g.c.tick();
        eq("G15-42 快照=未登录且读失败 ⇒ 收尾关窗", Phase.LOGGED_IN, g.s().phase());
        check("G15-43 快照=未登录且读失败 ⇒ 不保持打开", !g.c.isOpen());
    }

    // ------------------------------------------------------------------ 夹具

    /** 控制器 + 假端口 + 可手动 flush 的假异步通道 + 状态记录器。 */
    private static final class Fixture {

        final FakePorts ports;
        final FakeAsync async = new FakeAsync();
        final List<State> events = new ArrayList<>();
        final LoginController c;

        Fixture() {
            this(new FakePorts());
        }

        Fixture(FakePorts ports) {
            this.ports = ports;
            this.c = new LoginController(ports, async, events::add);
        }

        State s() {
            return c.state();
        }
    }

    /** 可编程假端口：记录调用次数与入参，按需抛异常 / 返回预设值。 */
    private static final class FakePorts implements LoginController.Ports {

        long now;
        boolean startQrResult = true;
        RuntimeException startQrError;
        int startQrCalls;
        boolean lastStartQrForce;

        AccountService.LoginStatus statusValue = LoginControllerProbe.status(AccountService.State.NONE, "");
        int statusCalls;

        Dto.QrSession qr;
        int qrCalls;

        boolean risk;
        String verifyUrl = "";
        int riskCalls;
        int verifyUrlCalls;

        int retryQrAfterCalls;

        int pauseCalls;
        boolean pauseResult = true;

        boolean wasLoggedIn;
        int wasLoggedInCalls;
        RuntimeException wasLoggedInError;
        /** 非 null 时模拟生产 AccountService：{@code startQr} 内部刷新开窗快照。 */
        Boolean wasLoggedInAfterStartQr;

        Dto.SmsResult smsResult = new Dto.SmsResult(200, "ok");
        RuntimeException smsError;
        int sendCodeCalls;
        String lastSendPhone;
        String lastSendSource;

        NeteaseApi.LoginOutcome loginOutcome = new NeteaseApi.LoginOutcome(true, 200, "ok", "");
        RuntimeException loginError;
        int loginCalls;
        String lastLoginPhone;
        String lastLoginCode;
        String lastLoginSource;

        int openVerifyCalls;
        String lastVerifyUrl;
        boolean lastVerifyFromQr;
        Runnable lastVerifyOnRetry;

        int closeVerifyCalls;

        String maskResult = "138****0000";

        @Override
        public boolean startQr(boolean force) {
            startQrCalls++;
            lastStartQrForce = force;
            if (startQrError != null) {
                throw startQrError;
            }
            if (wasLoggedInAfterStartQr != null) {
                // 生产 startQr 里才刷新 statusAtOpen / loginSerialAtOpen，控制器必须在这之后读快照
                wasLoggedIn = wasLoggedInAfterStartQr;
            }
            return startQrResult;
        }

        @Override
        public AccountService.LoginStatus status() {
            statusCalls++;
            return statusValue;
        }

        @Override
        public Dto.QrSession qrImage() {
            qrCalls++;
            return qr;
        }

        @Override
        public boolean qrRiskRequired() {
            riskCalls++;
            return risk;
        }

        @Override
        public String qrVerifyUrl() {
            verifyUrlCalls++;
            return verifyUrl;
        }

        @Override
        public void retryQrAfterRisk() {
            retryQrAfterCalls++;
        }

        @Override
        public boolean pauseQrWatch() {
            pauseCalls++;
            return pauseResult;
        }

        @Override
        public boolean wasLoggedInAtOpen() {
            wasLoggedInCalls++;
            if (wasLoggedInError != null) {
                throw wasLoggedInError;
            }
            return wasLoggedIn;
        }

        @Override
        public Dto.SmsResult sendCode(String phone, String source) {
            sendCodeCalls++;
            lastSendPhone = phone;
            lastSendSource = source;
            if (smsError != null) {
                throw smsError;
            }
            return smsResult;
        }

        @Override
        public NeteaseApi.LoginOutcome loginSms(String phone, String code, String source) {
            loginCalls++;
            lastLoginPhone = phone;
            lastLoginCode = code;
            lastLoginSource = source;
            if (loginError != null) {
                throw loginError;
            }
            return loginOutcome;
        }

        @Override
        public void openVerify(String url, boolean fromQr, Runnable onRetry) {
            openVerifyCalls++;
            lastVerifyUrl = url;
            lastVerifyFromQr = fromQr;
            lastVerifyOnRetry = onRetry;
        }

        @Override
        public void closeVerify() {
            closeVerifyCalls++;
        }

        @Override
        public String maskPhone(String phone) {
            return maskResult;
        }

        @Override
        public long nowMs() {
            return now;
        }
    }

    /** 假异步通道：run 只排队，flush 才真正执行 —— 用来证明控制器不阻塞调用线程。 */
    private static final class FakeAsync implements LoginController.Async {

        private interface Job {
            void run();
        }

        private final List<Job> jobs = new ArrayList<>();

        @Override
        public <T> void run(LoginController.Fetcher<T> work, Consumer<T> success, Consumer<Throwable> failure) {
            jobs.add(() -> {
                T value;
                try {
                    value = work.fetch();
                } catch (Throwable t) {
                    failure.accept(t);
                    return;
                }
                success.accept(value);
            });
        }

        @Override
        public void cancel() {
            // 控制器不使用 cancel / close，留空
        }

        @Override
        public void close() {
            // 同上
        }

        int pending() {
            return jobs.size();
        }

        void flush() {
            List<Job> copy = new ArrayList<>(jobs);
            jobs.clear();
            for (Job job : copy) {
                job.run();
            }
        }
    }
}
