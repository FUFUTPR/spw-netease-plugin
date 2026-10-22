import com.example.netease.net.Dto;
import com.example.netease.ui.controller.PlaylistController;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 真实 {@link PlaylistController.DefaultAsync} / LatestTask 下的锁序回归（REVIEW issue 1）。
 *
 * <p>覆盖四件事：</p>
 * <ol>
 *   <li>外部持有 {@code synchronized(controller)} 时，后台结果仍能回传（锁已与对象监视器解耦）；</li>
 *   <li>「外部监视器 + 第二次 loadDaily」的原始复现不再死锁，且 JVM 检测不到死锁线程；</li>
 *   <li>请求在途时 {@code close()} 快速返回，迟到的结果不再通知；</li>
 *   <li>请求在途时账号 epoch 变化，{@code tick()} 快速取消并复位状态，迟到的结果不再通知。</li>
 * </ol>
 *
 * <p>只依赖插件类与 JDK，不依赖 Compose / 宿主 UI。</p>
 */
public final class PlaylistLockOrderProbe {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        try {
            testResultWhileExternalMonitorHeld();
            if (!testNoDeadlockWithRealLatestTask()) {
                summaryAndExit();
                return;
            }
            testCloseDuringRequest();
            testTickEpochChangeDuringRequest();
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL 探针异常：" + t);
            t.printStackTrace(System.out);
        }
        summaryAndExit();
    }

    private static void testResultWhileExternalMonitorHeld() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<PlaylistController.State> states = new CopyOnWriteArrayList<>();
        AtomicLong epoch = new AtomicLong(0L);
        PlaylistController c = new PlaylistController(states::add, realAsync(),
                api(epoch::get, entered, release, false, daily()),
                playback(), () -> "standard", System::currentTimeMillis);
        boolean observed;
        synchronized (c) {
            c.loadDaily();
            check("loadDaily 立即发布 busy 状态",
                    c.state().busy() && "每日推荐".equals(c.state().pendingLabel()));
            check("worker 已开始（外部监视器场景）", entered.await(2, TimeUnit.SECONDS));
            release.countDown();
            observed = await(3_000L, () -> !states.isEmpty()
                    && states.get(states.size() - 1).tracks().size() == 2);
        }
        check("持有外部 synchronized(controller) 时载入结果仍能回传", observed);
        c.close();
    }

    private static boolean testNoDeadlockWithRealLatestTask() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<PlaylistController.State> states = new CopyOnWriteArrayList<>();
        AtomicLong epoch = new AtomicLong(0L);
        PlaylistController c = new PlaylistController(states::add, realAsync(),
                api(epoch::get, entered, release, false, daily()),
                playback(), () -> "standard", System::currentTimeMillis);
        c.loadDaily();
        check("worker 已开始（死锁复现前置）", entered.await(2, TimeUnit.SECONDS));
        Thread ui = new Thread(() -> {
            synchronized (c) {
                release.countDown();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (System.nanoTime() < until) {
                    boolean blocked = Thread.getAllStackTraces().keySet().stream()
                            .anyMatch(t -> "native-playlist".equals(t.getName())
                                    && t.getState() == Thread.State.BLOCKED);
                    if (blocked) {
                        break;
                    }
                    try {
                        Thread.sleep(5L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                c.loadDaily();
            }
        }, "probe-ui-second-load");
        ui.setDaemon(true);
        ui.start();
        ui.join(8_000L);
        if (ui.isAlive()) {
            check("真实 LatestTask 下第二次 loadDaily 不死锁", false);
            check("JVM 未检测到死锁线程", noDeadlock());
            return false;
        }
        check("真实 LatestTask 下第二次 loadDaily 不死锁", true);
        check("JVM 未检测到死锁线程", noDeadlock());
        check("第二次载入结果最终生效", await(3_000L, () -> !states.isEmpty()
                && states.get(states.size() - 1).tracks().size() == 2));
        c.close();
        return true;
    }

    private static void testCloseDuringRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<PlaylistController.State> states = new CopyOnWriteArrayList<>();
        AtomicLong epoch = new AtomicLong(0L);
        PlaylistController c = new PlaylistController(states::add, realAsync(),
                api(epoch::get, entered, release, true, daily()),
                playback(), () -> "standard", System::currentTimeMillis);
        c.loadDaily();
        check("worker 已开始（close 前置）", entered.await(2, TimeUnit.SECONDS));
        long started = System.nanoTime();
        c.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        check("请求在途时 close 快速返回（" + elapsedMs + "ms）", elapsedMs < 2_000L);
        int before = states.size();
        release.countDown();
        Thread.sleep(700L);
        check("close 后迟到的载入结果不再通知", states.size() == before);
        try {
            c.close();
            check("close 可重复调用", true);
        } catch (Throwable t) {
            check("close 可重复调用", false);
        }
    }

    private static void testTickEpochChangeDuringRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<PlaylistController.State> states = new CopyOnWriteArrayList<>();
        AtomicLong epoch = new AtomicLong(0L);
        PlaylistController c = new PlaylistController(states::add, realAsync(),
                api(epoch::get, entered, release, false, daily()),
                playback(), () -> "standard", System::currentTimeMillis);
        c.loadDaily();
        check("worker 已开始（epoch 变化前置）", entered.await(2, TimeUnit.SECONDS));
        epoch.set(1L);
        long started = System.nanoTime();
        c.tick();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        check("请求在途时 tick 快速返回（" + elapsedMs + "ms）", elapsedMs < 2_000L);
        PlaylistController.State s = c.state();
        check("epoch 变化后 busy 复位", !s.busy());
        check("epoch 变化提示可见", s.message().contains("账号状态变化"));
        check("epoch 变化后可重新载入",
                s.canRetry() && "重新载入我的歌单".equals(s.retryLabel()));
        int before = states.size();
        release.countDown();
        Thread.sleep(700L);
        check("epoch 变化取消后的迟到结果不再通知", states.size() == before);
        c.close();
    }

    private static PlaylistController.Async realAsync() throws Exception {
        Class<?> type = Class.forName(
                "com.example.netease.ui.controller.PlaylistController$DefaultAsync");
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        return (PlaylistController.Async) ctor.newInstance();
    }

    private static PlaylistController.Api api(LongSupplier epoch, CountDownLatch entered,
                                              CountDownLatch release, boolean ignoreInterrupts,
                                              List<Dto.Song> daily) {
        return (PlaylistController.Api) Proxy.newProxyInstance(
                PlaylistLockOrderProbe.class.getClassLoader(),
                new Class<?>[]{PlaylistController.Api.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "accountEpoch":
                            return epoch.getAsLong();
                        case "loggedIn":
                            return true;
                        case "uid":
                            return 123L;
                        case "dailySongs":
                            if (entered != null) {
                                entered.countDown();
                            }
                            if (release != null) {
                                if (ignoreInterrupts) {
                                    while (true) {
                                        try {
                                            release.await();
                                            break;
                                        } catch (InterruptedException ignored) {
                                            // close 会中断池线程；这里故意继续等，验证迟到结果被丢弃。
                                        }
                                    }
                                } else {
                                    release.await();
                                }
                            }
                            return daily;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static PlaylistController.Playback playback() {
        return (PlaylistController.Playback) Proxy.newProxyInstance(
                PlaylistLockOrderProbe.class.getClassLoader(),
                new Class<?>[]{PlaylistController.Playback.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == String.class) {
                        return "";
                    }
                    if (method.getReturnType() == int.class) {
                        return 1;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == List.class) {
            return List.of();
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == boolean.class) {
            return false;
        }
        return null;
    }

    private static List<Dto.Song> daily() {
        return List.of(song(1), song(2));
    }

    private static Dto.Song song(long id) {
        return new Dto.Song(id, "song" + id, "artist", "album", 200_000L, 0, true, "");
    }

    private static boolean noDeadlock() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] ids = mx.findDeadlockedThreads();
        return ids == null || ids.length == 0;
    }

    private static boolean await(long millis, BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label);
        }
    }

    private static void summaryAndExit() {
        System.out.println("PlaylistLockOrderProbe: PASS=" + passed + " FAIL=" + failed);
        System.exit(failed == 0 ? 0 : 2);
    }
}
