package com.example.netease.ui;

import com.example.netease.core.PluginLog;

import javax.swing.SwingUtilities;
import java.awt.Window;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 插件窗口注册表。
 *
 * <p>宿主没有通用 UI 扩展点，插件只能用 Swing 自绘窗口。这些窗口必须在
 * {@code NeteasePlugin.stop()} 时全部销毁，否则插件停用后窗口会滞留在宿主进程里
 * （验收 A9：停用后无残留窗口/线程/文件句柄）。</p>
 *
 * <p>铁律：窗口的创建、显示、销毁**只能在 EDT**上做；本类的所有方法都能安全地
 * 从任意线程调用。</p>
 */
public final class UiRegistry {

    private static final Set<Window> WINDOWS = ConcurrentHashMap.newKeySet();

    private UiRegistry() {
    }

    /** 注册一个窗口，返回同一个对象，便于 {@code JFrame f = UiRegistry.track(new JFrame());}。 */
    public static <T extends Window> T track(T window) {
        if (window != null) {
            WINDOWS.add(window);
        }
        return window;
    }

    /** 窗口自行关闭时调用，从注册表摘除。 */
    public static void forget(Window window) {
        if (window != null) {
            WINDOWS.remove(window);
        }
    }

    /** 当前登记的窗口数（诊断用）。 */
    public static int openCount() {
        return WINDOWS.size();
    }

    /**
     * 销毁全部插件窗口。**非阻塞**：不在 EDT 时投递到 EDT 异步执行。
     *
     * <p>刻意不用 {@code invokeAndWait}：本方法会在 {@code stop()} 里被调用，而
     * {@code stop()} 可能运行在宿主线程上，阻塞它去等 EDT 会在 EDT 忙时拖住宿主关闭。</p>
     */
    public static void disposeAll() {
        if (SwingUtilities.isEventDispatchThread()) {
            disposeAllOnEdt();
        } else {
            try {
                SwingUtilities.invokeLater(UiRegistry::disposeAllOnEdt);
            } catch (Throwable t) {
                PluginLog.w("ui", "投递窗口销毁任务失败", t);
            }
        }
    }

    /** 阻塞版销毁，仅供离线 harness / 测试使用；宿主路径请用 {@link #disposeAll()}。 */
    public static boolean disposeAllBlocking(long timeoutMs) {
        if (SwingUtilities.isEventDispatchThread()) {
            disposeAllOnEdt();
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    disposeAllOnEdt();
                } finally {
                    latch.countDown();
                }
            });
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            PluginLog.w("ui", "等待窗口销毁失败", t);
            return false;
        }
    }

    private static void disposeAllOnEdt() {
        for (Window w : WINDOWS) {
            try {
                w.setVisible(false);
                w.dispose();
            } catch (Throwable t) {
                PluginLog.w("ui", "销毁窗口失败 " + w.getClass().getSimpleName(), t);
            }
        }
        WINDOWS.clear();
    }
}
