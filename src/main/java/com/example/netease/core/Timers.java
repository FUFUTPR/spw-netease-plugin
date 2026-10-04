package com.example.netease.core;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定时器（1 条守护线程 {@code netease-timer}）：延迟任务、周期性清理。
 *
 * <p>铁律：任务体里**只能做**「投递 {@link HostBridgeWorker#submit}」或纯内部计算，
 * **绝不允许**直接触碰宿主 API —— 只有 {@code spw-netease-host} 线程能做那件事。</p>
 */
public final class Timers {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static volatile ScheduledExecutorService exec;

    private Timers() {
    }

    public static synchronized void start() {
        if (exec != null) {
            return;
        }
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "netease-timer-" + SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        exec = Executors.newScheduledThreadPool(1, factory);
        PluginLog.d("timer", "定时线程已启动");
    }

    public static synchronized void stop() {
        ScheduledExecutorService e = exec;
        exec = null;
        if (e != null) {
            try {
                e.shutdownNow();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    /** 延迟执行一次。 */
    public static void later(long delayMs, Runnable task) {
        ScheduledExecutorService e = exec;
        if (e == null || task == null) {
            PluginLog.w("timer", "定时线程未启动，延迟任务被丢弃");
            return;
        }
        try {
            e.schedule(() -> safeRun(task), Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            PluginLog.w("timer", "调度失败：" + t);
        }
    }

    /** 周期执行；返回句柄便于取消（可为 null）。 */
    public static ScheduledFuture<?> every(long initialDelayMs, long periodMs, Runnable task) {
        ScheduledExecutorService e = exec;
        if (e == null || task == null) {
            return null;
        }
        try {
            return e.scheduleWithFixedDelay(() -> safeRun(task),
                    Math.max(0L, initialDelayMs), Math.max(1000L, periodMs), TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            PluginLog.w("timer", "周期调度失败：" + t);
            return null;
        }
    }

    private static void safeRun(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            PluginLog.e("timer", "定时任务异常（已吞掉）", t);
        }
    }
}
