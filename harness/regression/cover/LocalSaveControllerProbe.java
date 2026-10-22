import com.example.netease.net.Dto;
import com.example.netease.svc.LocalPlaylists;
import com.example.netease.ui.controller.LocalSaveController;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 批次 3 · 本地歌单命名控制器回归探针（headless，无 Swing/AWT，手写断言，不用 JUnit）。
 *
 * <p>按 build/_tmp/batch3-contracts.md「队友 2」的 10 组断言逐条覆盖：默认名、防御性捕获、
 * 空白名 / 空曲目拒绝、正常成功、快照隔离、key 为空 / 抛异常失败、连续提交去重、
 * 刷新 entries 成功与失败、cancelPending 不取消写盘。另加「投递被拒绝」的防御性收尾用例。</p>
 *
 * <p>全部通过打印 {@code LOCAL_SAVE_CONTROLLER_RESULT=<n> PASS} 并 exit 0；
 * 任一失败打印 {@code FAIL <编号> (<用例>): <说明>} 并 exit 1。</p>
 */
public final class LocalSaveControllerProbe {

    private static int passed;
    private static String currentCase = "";

    public static void main(String[] args) {
        // 统一 UTF-8 输出，避免 Windows 控制台 GBK 代码页把中文标签打成乱码。
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        try {
            run("构造初值", LocalSaveControllerProbe::initialState);
            run("默认名", LocalSaveControllerProbe::defaultNames);
            run("capture 防御性拷贝", LocalSaveControllerProbe::defensiveCapture);
            run("capture 从端口读取", LocalSaveControllerProbe::captureFromPorts);
            run("capture 读取失败", LocalSaveControllerProbe::captureReadFailure);
            run("空白名称拒绝", LocalSaveControllerProbe::blankNameRejected);
            run("无曲目拒绝", LocalSaveControllerProbe::noTracksRejected);
            run("正常保存成功", LocalSaveControllerProbe::successFlow);
            run("快照隔离", LocalSaveControllerProbe::snapshotIsolation);
            run("save 返回 null", LocalSaveControllerProbe::nullKeyFails);
            run("save 返回空串", LocalSaveControllerProbe::emptyKeyFails);
            run("save 抛异常", LocalSaveControllerProbe::saveThrows);
            run("failure 回调", LocalSaveControllerProbe::failureCallback);
            run("连续两次 submit", LocalSaveControllerProbe::doubleSubmit);
            run("refreshEntries", LocalSaveControllerProbe::refreshEntries);
            run("busy 时 refreshEntries", LocalSaveControllerProbe::refreshEntriesBusySkipped);
            run("cancelPending", LocalSaveControllerProbe::cancelPending);
            run("close 生命周期", LocalSaveControllerProbe::closeLifecycle);
            run("投递被拒绝", LocalSaveControllerProbe::dispatchRejected);
        } catch (Throwable t) {
            if (t instanceof AssertionError && t.getMessage() != null) {
                System.out.println(t.getMessage());
            } else {
                System.out.println("FAIL " + (passed + 1) + " (" + currentCase + "): " + t);
                t.printStackTrace(System.out);
            }
            System.exit(1);
        }
        System.out.println("LOCAL_SAVE_CONTROLLER_RESULT=" + passed + " PASS");
    }

    // ------------------------------------------------------------------ 用例

    /** 契约：构造时 entries=List.of()、message=""、saved=false；不自动读盘。 */
    private static void initialState() {
        Fixture f = new Fixture();
        LocalSaveController.State st = f.ctl.state();
        check(!st.hasTracks(), "构造 hasTracks=false");
        eq(0, st.trackCount(), "构造 trackCount=0");
        eq("", st.sourceName(), "构造 sourceName 为空串");
        eq(LocalSaveController.DEFAULT_NAME, st.defaultName(), "构造默认名为兜底名");
        eq("", st.message(), "构造 message 为空串");
        check(!st.busy(), "构造非 busy");
        check(!st.saved(), "构造 saved=false");
        check(st.entries().isEmpty(), "构造 entries 为空");
        eq(0, f.ports.tracksCalls, "构造不读曲目");
        eq(0, f.ports.listCalls, "构造不读盘（list 零调用）");
        eq(0, f.notes(), "构造不通知 Listener");
        eq(LocalSaveController.DEFAULT_NAME, f.ctl.defaultName(), "defaultName() 与 state 一致");
    }

    /** 契约组 1：默认名 = 来源 trim 后非空取 trim，否则兜底名；无曲目不影响默认名。 */
    private static void defaultNames() {
        Fixture f = new Fixture();
        f.ctl.capture(List.of(song(1), song(2)), "每日推荐");
        eq("每日推荐", f.ctl.state().defaultName(), "来源非空 → 默认名取来源");
        eq("每日推荐", f.ctl.defaultName(), "defaultName() 返回同一默认名");

        f.ctl.capture(List.of(song(1)), "   ");
        eq(LocalSaveController.DEFAULT_NAME, f.ctl.state().defaultName(), "来源全空白 → 兜底名");

        f.ctl.capture(List.of(), "  歌单来源  ");
        eq("歌单来源", f.ctl.state().defaultName(), "来源首尾空格 trim 后作默认名");
        eq("  歌单来源  ", f.ctl.state().sourceName(), "sourceName 原样保留（只做 null 归一）");
        check(!f.ctl.state().hasTracks(), "空曲目 hasTracks=false");
        eq(0, f.ctl.state().trackCount(), "空曲目 trackCount=0");

        f.ctl.capture(List.of(), "来源");
        eq("来源", f.ctl.state().defaultName(), "无曲目时默认名仍为来源");

        f.ctl.capture(List.of(), null);
        eq(LocalSaveController.DEFAULT_NAME, f.ctl.state().defaultName(), "来源 null → 兜底名");
        eq("", f.ctl.state().sourceName(), "来源 null 归一空串");
    }

    /** 契约组 2：capture 保存防御性拷贝，外部列表 add/clear 后 trackCount 与写盘快照都不变。 */
    private static void defensiveCapture() {
        Fixture f = new Fixture();
        List<Dto.Song> external = new ArrayList<>();
        external.add(song(1));
        external.add(song(2));
        f.ctl.capture(external, "来源");
        eq(2, f.ctl.state().trackCount(), "capture 后 2 首");
        external.add(song(3));
        eq(2, f.ctl.state().trackCount(), "外部 add 后 trackCount 不变");
        external.clear();
        eq(2, f.ctl.state().trackCount(), "外部 clear 后 trackCount 不变");
        eq(2, f.ctl.state().trackCount(), "重新读取 state 仍为 2 首");
        external.add(song(9));
        eq(true, f.ctl.submit("保存"), "捕获快照后可以提交");
        f.async.succeed(0);
        eq(2, f.ports.saveSongs.get(0).size(), "写盘用的是 capture 时的 2 首快照");
    }

    /** capture() 走端口：读一次 tracks / sourceName 并通知。 */
    private static void captureFromPorts() {
        Fixture f = new Fixture();
        f.ports.tracks = List.of(song(1), song(2), song(3));
        f.ports.sourceName = "我的歌单";
        f.ctl.capture();
        eq(1, f.ports.tracksCalls, "capture() 读曲目 1 次");
        eq(1, f.ports.sourceNameCalls, "capture() 读来源 1 次");
        eq(3, f.ctl.state().trackCount(), "capture() 得到 3 首");
        check(f.ctl.state().hasTracks(), "capture() hasTracks=true");
        eq("我的歌单", f.ctl.state().defaultName(), "capture() 默认名取端口来源");
        eq(1, f.notes(), "capture() 通知 Listener 1 次");

        f.ports.sourceName = " 带空格来源 ";
        f.ctl.capture();
        eq("带空格来源", f.ctl.state().defaultName(), "端口来源首尾空格 trim 后作默认名");
        eq(" 带空格来源 ", f.ctl.state().sourceName(), "端口来源原样保存（只 null 归一）");

        f.ports.sourceName = null;
        f.ctl.capture();
        eq("", f.ctl.state().sourceName(), "端口来源 null 归一空串");
        eq(LocalSaveController.DEFAULT_NAME, f.ctl.state().defaultName(), "端口来源 null → 兜底名");
    }

    /** 契约组：tracks() 抛异常 → 空列表 + 文案「读取当前曲目失败」。 */
    private static void captureReadFailure() {
        Fixture f = new Fixture();
        f.ports.tracks = List.of(song(1), song(2));
        f.ports.sourceName = "来源";
        f.ctl.capture();
        eq(2, f.ctl.state().trackCount(), "先正常捕获 2 首");

        f.ports.tracksError = new RuntimeException("读取曲目失败");
        f.ctl.capture();
        eq(0, f.ctl.state().trackCount(), "tracks 抛异常 → 视为空列表");
        check(!f.ctl.state().hasTracks(), "tracks 抛异常 hasTracks=false");
        eq(LocalSaveController.TEXT_READ_FAILED, f.ctl.state().message(), "读取失败文案逐字");
        eq("来源", f.ctl.state().defaultName(), "读取失败时默认名仍取来源");
        eq(false, f.ctl.submit("名字"), "读取失败后空快照不能保存");
        eq(LocalSaveController.TEXT_NO_TRACKS, f.ctl.state().message(), "空快照提交仍是无曲目文案");
        eq(0, f.ports.saveCalls, "读取失败后不写盘");

        f.ports.tracksError = null;
        f.ports.sourceName = "恢复来源";
        f.ctl.capture();
        eq(2, f.ctl.state().trackCount(), "端口恢复后可再次捕获");
        eq("", f.ctl.state().message(), "再次 capture 清空上一轮失败文案");

        Fixture g = new Fixture();
        g.ports.tracks = List.of(song(5));
        g.ports.sourceError = new RuntimeException("读取来源失败");
        g.ctl.capture();
        eq(1, g.ctl.state().trackCount(), "来源读取失败不影响曲目");
        eq("", g.ctl.state().sourceName(), "来源读取失败归一空串");
        eq(LocalSaveController.DEFAULT_NAME, g.ctl.state().defaultName(), "来源读取失败用兜底名");
        eq("", g.ctl.state().message(), "来源读取失败不覆盖文案");
    }

    /** 契约组 3：null / "" / "   " 名称一律拒绝，save 零调用，文案逐字。 */
    private static void blankNameRejected() {
        Fixture f = new Fixture();
        f.ctl.capture(List.of(song(1)), "来源");

        eq(false, f.ctl.submit(null), "null 名称拒绝");
        check(!f.ctl.state().busy(), "null 名称拒绝后不 busy");
        eq(0, f.ports.saveCalls, "null 名称不调用 save");
        eq(LocalSaveController.TEXT_EMPTY_NAME, f.ctl.state().message(), "null 名称文案逐字");
        eq(0, f.async.count(), "null 名称不投递后台任务");

        eq(false, f.ctl.submit(""), "空串名称拒绝");
        eq(LocalSaveController.TEXT_EMPTY_NAME, f.ctl.state().message(), "空串名称文案逐字");

        eq(false, f.ctl.submit("   "), "全空白名称拒绝");
        eq(0, f.ports.saveCalls, "空白名称 save 零调用");
        eq(4, f.notes(), "capture + 三次拒绝共 4 次通知");
    }

    /** 契约组 4：快照为空（即使名称合法）不写盘。 */
    private static void noTracksRejected() {
        Fixture f = new Fixture();
        f.ctl.capture(List.of(), "来源");
        eq(false, f.ctl.submit("名字"), "无曲目拒绝");
        eq(LocalSaveController.TEXT_NO_TRACKS, f.ctl.state().message(), "无曲目文案逐字");
        eq(0, f.ports.saveCalls, "无曲目 save 零调用");
        eq(0, f.async.count(), "无曲目不投递后台任务");
        check(!f.ctl.state().busy(), "无曲目拒绝后不 busy");
        eq("来源", f.ctl.state().defaultName(), "无曲目默认名仍取来源");
    }

    /** 契约组 5：正常成功 —— busy 在 flush 前、文案逐字、saved、entries 刷新；组 14 的「真异步」。 */
    private static void successFlow() {
        Fixture f = new Fixture();
        List<Dto.Song> src = new ArrayList<>(List.of(song(1, "第一首"), song(2, "第二首")));
        f.ctl.capture(src, "每日推荐");
        f.ports.saveKey = "l100";
        f.ports.listResult = List.of(entry("l100", "存单", 2), entry("l9", "旧歌单", 5));

        eq(true, f.ctl.submit("  我的存单  "), "submit 返回 true");
        LocalSaveController.State saving = f.ctl.state();
        check(saving.busy(), "flush 前 busy=true");
        eq(LocalSaveController.TEXT_SAVING, saving.message(), "flush 前文案逐字");
        check(!saving.saved(), "flush 前 saved=false");
        eq(0, f.ports.saveCalls, "flush 前 save 未被调用（证明异步不阻塞调用线程）");
        eq(1, f.async.count(), "投递 1 个后台任务");

        f.async.succeed(0);
        LocalSaveController.State done = f.ctl.state();
        check(!done.busy(), "flush 后 busy=false");
        check(done.saved(), "flush 后 saved=true");
        eq("已存为本地歌单「我的存单」（2 首）", done.message(), "成功文案逐字");
        eq(1, f.ports.saveCalls, "save 调用 1 次");
        eq("我的存单", f.ports.saveNames.get(0), "save 收到 trim 后名称");
        eq(2, f.ports.saveSongs.get(0).size(), "save 收到 2 首");
        eq(src, f.ports.saveSongs.get(0), "save 曲目与快照一致");
        eq(1, f.ports.listCalls, "同一次回调内刷新 entries（list 1 次）");
        eq(1, f.async.count(), "刷新条目不再另投后台任务");
        eq(2, done.entries().size(), "端口返回 2 条则 state.entries 2 条");
        eq("l100", done.entries().get(0).key(), "entries 内容来自端口");
        eq(3, f.notes(), "capture + 保存中 + 完成共 3 次通知");
    }

    /** 契约组 6：提交后改源列表，再 flush，save 收到的仍是 submit 时的快照。 */
    private static void snapshotIsolation() {
        Fixture f = new Fixture();
        List<Dto.Song> src = new ArrayList<>(List.of(song(1), song(2)));
        f.ctl.capture(src, "来源");
        f.ports.saveKey = "l1";

        eq(true, f.ctl.submit("名字"), "提交成功");
        src.clear();
        src.add(song(7));
        f.ctl.capture(List.of(song(7)), "另一个来源");
        f.async.succeed(0);

        eq(2, f.ports.saveSongs.get(0).size(), "submit 后改源列表，save 仍是旧快照 2 首");
        eq(song(1), f.ports.saveSongs.get(0).get(0), "旧快照第一首未被替换");
        eq("已存为本地歌单「名字」（2 首）", f.ctl.state().message(), "成功文案按旧快照首数");
        eq("另一个来源", f.ctl.state().defaultName(), "之后 capture 的默认名已生效");
        eq(1, f.ports.saveCalls, "仍只有一次写盘");
    }

    /** 契约组 7：save 返回 null → 失败文案、saved=false、entries 不刷新。 */
    private static void nullKeyFails() {
        Fixture f = new Fixture();
        f.ctl.capture(List.of(song(1)), "来源");
        f.ports.saveKey = null;
        eq(true, f.ctl.submit("名字"), "提交成功投递");
        f.async.succeed(0);
        LocalSaveController.State st = f.ctl.state();
        check(!st.busy(), "key=null 后 busy 复位");
        check(!st.saved(), "key=null 后 saved=false");
        eq(LocalSaveController.TEXT_SAVE_FAILED, st.message(), "key=null 失败文案逐字");
        eq(0, f.ports.listCalls, "key=null 不刷新 entries（list 零调用）");
        check(st.entries().isEmpty(), "key=null entries 保持为空");
        eq(3, f.notes(), "失败也通知（capture + 保存中 + 失败）");
    }

    /** 契约「key 非空才算成功」：空串 key 同样按失败处理。 */
    private static void emptyKeyFails() {
        Fixture f = new Fixture();
        f.ctl.capture(List.of(song(1)), "来源");
        f.ports.saveKey = "";
        eq(true, f.ctl.submit("名字"), "提交成功投递");
        f.async.succeed(0);
        check(!f.ctl.state().saved(), "key 为空串 saved=false");
        eq(LocalSaveController.TEXT_SAVE_FAILED, f.ctl.state().message(), "key 为空串 失败文案逐字");
        eq(0, f.ports.listCalls, "key 为空串 不刷新 entries");
        check(!f.ctl.state().busy(), "key 为空串 busy 复位");
    }

    /** 契约组 8：save 抛异常 → 与 null key 同样的失败收尾（异步通道把异常送到 failure）。 */
    private static void saveThrows() {
        Fixture f = new Fixture();
        f.ctl.capture(List.of(song(1)), "来源");
        f.ports.saveError = new RuntimeException("磁盘只读");
        eq(true, f.ctl.submit("名字"), "提交成功投递");
        f.async.succeed(0);
        LocalSaveController.State st = f.ctl.state();
        check(!st.busy(), "save 抛异常后 busy 复位");
        check(!st.saved(), "save 抛异常后 saved=false");
        eq(LocalSaveController.TEXT_SAVE_FAILED, st.message(), "save 抛异常失败文案逐字");
        eq(1, f.ports.saveCalls, "save 被调用 1 次后抛异常");
        eq(0, f.ports.listCalls, "save 抛异常不刷新 entries");
    }

    /** 通道直接回调 failure：同样失败收尾，且不补调 save。 */
    private static void failureCallback() {
        Fixture f = new Fixture();
        f.ctl.capture(List.of(song(1)), "来源");
        eq(true, f.ctl.submit("名字"), "提交成功投递");
        f.async.fail(0, new java.util.concurrent.TimeoutException("后台超时"));
        LocalSaveController.State st = f.ctl.state();
        check(!st.busy(), "failure 回调后 busy 复位");
        check(!st.saved(), "failure 回调后 saved=false");
        eq(LocalSaveController.TEXT_SAVE_FAILED, st.message(), "failure 回调文案逐字");
        eq(0, f.ports.saveCalls, "failure 回调不补调 save");
        eq(0, f.ports.listCalls, "failure 回调不刷新 entries");
    }

    /** 契约组 9：连续两次 submit，第二次被 busy 拦截，save 仅 1 次。 */
    private static void doubleSubmit() {
        Fixture f = new Fixture();
        f.ctl.capture(List.of(song(1), song(2)), "来源");
        f.ports.saveKey = "l1";
        eq(true, f.ctl.submit("第一次"), "第一次提交成功");
        eq(false, f.ctl.submit("第二次"), "busy 中第二次被拦截");
        eq(LocalSaveController.TEXT_BUSY, f.ctl.state().message(), "busy 拦截文案逐字");
        eq(1, f.async.count(), "两次 submit 只投递 1 个任务");
        check(f.ctl.state().busy(), "第二次提交后仍 busy");

        f.async.succeed(0);
        eq(1, f.ports.saveCalls, "save 仅被调用 1 次");
        eq("第一次", f.ports.saveNames.get(0), "save 用第一次的名称");
        check(f.ctl.state().saved(), "第一次保存成功");
        eq(4, f.notes(), "capture + 第一次 + 拦截 + 完成共 4 次通知");
    }

    /** 契约组 10 前半：refreshEntries 成功更新 entries、失败保留旧值并逐字文案。 */
    private static void refreshEntries() {
        Fixture f = new Fixture();
        f.ports.listResult = List.of(entry("l1", "甲", 1), entry("l2", "乙", 2));
        f.ctl.refreshEntries();
        eq(1, f.async.count(), "refreshEntries 投递 1 个任务");
        check(!f.ctl.state().busy(), "刷新不置 busy");
        f.async.succeed(0);
        eq(2, f.ctl.state().entries().size(), "刷新成功更新 2 条 entries");
        eq(1, f.ports.listCalls, "list 调用 1 次");
        eq("", f.ctl.state().message(), "刷新成功不改文案");
        eq(1, f.notes(), "刷新成功通知 1 次");

        f.ports.listError = new RuntimeException("目录不可读");
        f.ctl.refreshEntries();
        f.async.succeed(1);
        eq(2, f.ctl.state().entries().size(), "刷新失败保留旧 entries");
        eq(LocalSaveController.TEXT_REFRESH_FAILED, f.ctl.state().message(), "刷新失败文案逐字");
        eq(2, f.ports.listCalls, "失败分支 list 也只调用 1 次");
        check(!f.ctl.state().busy(), "刷新失败不置 busy");
    }

    /** busy（写盘进行中）时 refreshEntries 直接忽略，不投递任务。 */
    private static void refreshEntriesBusySkipped() {
        Fixture f = new Fixture();
        f.ctl.capture(List.of(song(1)), "来源");
        f.ctl.submit("名字");
        int jobs = f.async.count();
        f.ctl.refreshEntries();
        eq(jobs, f.async.count(), "busy 时 refreshEntries 不投递任务");
        check(f.ctl.state().busy(), "busy 保持不变");
        f.async.succeed(0);
        eq(1, f.ports.listCalls, "写盘成功后的刷新仍执行（不是被跳过的那个）");
    }

    /** 契约组 10 后半：cancelPending 调 async.cancel 1 次、busy 不变、不取消已提交写盘。 */
    private static void cancelPending() {
        Fixture f = new Fixture();
        f.ctl.capture(List.of(song(1)), "来源");
        eq(true, f.ctl.submit("名字"), "提交成功投递");
        int notesBefore = f.notes();
        check(f.ctl.state().busy(), "写盘中 busy=true");

        f.ctl.cancelPending();
        eq(1, f.async.cancelCount, "cancelPending 调 async.cancel 1 次");
        eq(notesBefore, f.notes(), "cancelPending 不发布状态");
        check(f.ctl.state().busy(), "cancelPending 不清 busy（写盘不可取消）");
        eq(1, f.async.count(), "cancelPending 不新增任务");
        eq(0, f.async.closeCount, "cancelPending 不调用 async.close（关闭走 close()）");

        f.async.succeed(0);
        check(f.ctl.state().saved(), "取消只读任务不影响已提交的写盘");
    }

    /** 生命周期：close() 取消在途刷新并关闭 Async；重复调用不抛异常；不撤销已提交的写盘。 */
    private static void closeLifecycle() {
        Fixture f = new Fixture();
        f.ctl.capture(List.of(song(1)), "来源");
        eq(true, f.ctl.submit("名字"), "close 前提交成功");
        f.ctl.close();
        eq(1, f.async.cancelCount, "close 调 async.cancel 1 次");
        eq(1, f.async.closeCount, "close 调 async.close 1 次");
        check(f.ctl.state().busy(), "close 不清 busy（写盘不可取消）");
        f.ctl.close();
        check(true, "重复 close 不抛异常");
        f.async.succeed(0);
        check(f.ctl.state().saved(), "关闭前已提交的写盘仍完成并发布");
    }

    /** 防御性扩展：async.run 本身抛异常（通道拒绝投递）时按保存失败收尾，不留永远 busy。 */
    private static void dispatchRejected() {
        Fixture f = new Fixture();
        f.ctl.capture(List.of(song(1)), "来源");
        f.async.rejectNext = true;
        eq(false, f.ctl.submit("名字"), "投递被拒 submit 返回 false");
        eq(LocalSaveController.TEXT_SAVE_FAILED, f.ctl.state().message(), "投递被拒按保存失败收尾");
        check(!f.ctl.state().busy(), "投递被拒不留 busy");
        eq(0, f.ports.saveCalls, "投递被拒 save 未被调用");
    }

    // ------------------------------------------------------------------ 断言

    private static void run(String name, Runnable body) {
        currentCase = name;
        body.run();
    }

    private static void check(boolean ok, String label) {
        passed++;
        if (!ok) {
            throw new AssertionError("FAIL " + passed + " (" + currentCase + "): " + label);
        }
        System.out.println("PASS " + passed + " " + label);
    }

    private static void eq(Object expected, Object actual, String label) {
        passed++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("FAIL " + passed + " (" + currentCase + "): " + label
                    + "（expected=[" + expected + "] actual=[" + actual + "]）");
        }
        System.out.println("PASS " + passed + " " + label);
    }

    // ------------------------------------------------------------------ 夹具

    private static Dto.Song song(long id) {
        return song(id, "曲目" + id);
    }

    private static Dto.Song song(long id, String name) {
        return new Dto.Song(id, name, "歌手", "专辑", 200_000L, 0, true, "");
    }

    private static LocalPlaylists.Entry entry(String key, String name, int trackCount) {
        return new LocalPlaylists.Entry(key, 0L, name, "本地", trackCount, 1_700_000_000_000L);
    }

    private static final class Fixture {
        final FakePorts ports = new FakePorts();
        final FakeAsync async = new FakeAsync();
        final List<LocalSaveController.State> states = new ArrayList<>();
        final LocalSaveController ctl;

        Fixture() {
            ctl = new LocalSaveController(ports, async, states::add);
        }

        int notes() {
            return states.size();
        }
    }

    private static final class FakePorts implements LocalSaveController.Ports {
        List<Dto.Song> tracks = List.of();
        String sourceName = "";
        RuntimeException tracksError;
        RuntimeException sourceError;
        String saveKey = "l1";
        RuntimeException saveError;
        RuntimeException listError;
        List<LocalPlaylists.Entry> listResult = List.of();

        int tracksCalls;
        int sourceNameCalls;
        int saveCalls;
        int listCalls;
        final List<String> saveNames = new ArrayList<>();
        final List<List<Dto.Song>> saveSongs = new ArrayList<>();

        @Override
        public List<Dto.Song> tracks() {
            tracksCalls++;
            if (tracksError != null) {
                throw tracksError;
            }
            return tracks;
        }

        @Override
        public String sourceName() {
            sourceNameCalls++;
            if (sourceError != null) {
                throw sourceError;
            }
            return sourceName;
        }

        @Override
        public String save(String name, List<Dto.Song> songs) {
            saveCalls++;
            saveNames.add(name);
            saveSongs.add(songs == null ? List.of() : new ArrayList<>(songs));
            if (saveError != null) {
                throw saveError;
            }
            return saveKey;
        }

        @Override
        public List<LocalPlaylists.Entry> list() {
            listCalls++;
            if (listError != null) {
                throw listError;
            }
            return listResult;
        }
    }

    /** 假异步通道：run 只登记任务，flush（succeed/fail）才真正执行，证明控制器不阻塞调用线程。 */
    private static final class FakeAsync implements LocalSaveController.Async {
        private final List<Object[]> jobs = new ArrayList<>();
        int cancelCount;
        int closeCount;
        boolean rejectNext;

        @Override
        public <T> void run(LocalSaveController.Fetcher<T> work, Consumer<T> success,
                            Consumer<Throwable> failure) {
            if (rejectNext) {
                rejectNext = false;
                throw new IllegalStateException("后台通道已关闭");
            }
            jobs.add(new Object[]{work, success, failure});
        }

        @Override
        public void cancel() {
            cancelCount++;
        }

        @Override
        public void close() {
            closeCount++;
        }

        int count() {
            return jobs.size();
        }

        /** 执行第 index 个任务的 work，并把结果/异常送给对应回调。 */
        @SuppressWarnings({"unchecked", "rawtypes"})
        void succeed(int index) {
            Object[] job = jobs.get(index);
            Object value;
            try {
                value = ((LocalSaveController.Fetcher) job[0]).fetch();
            } catch (Exception e) {
                ((Consumer<Throwable>) job[2]).accept(e);
                return;
            }
            ((Consumer) job[1]).accept(value);
        }

        @SuppressWarnings("unchecked")
        void fail(int index, Throwable error) {
            ((Consumer<Throwable>) jobs.get(index)[2]).accept(error);
        }
    }
}
