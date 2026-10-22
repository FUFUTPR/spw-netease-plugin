package com.example.netease.ui;

import javax.swing.SwingUtilities;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Bounded background work; only the newest request can publish, including after timeout/close. */
public final class LatestTask implements AutoCloseable {
    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService timer;
    private final Consumer<Runnable> dispatch;
    private final long timeoutMs;
    private long sequence;
    private boolean closed;
    private Future<?> pending;
    private Future<?> deadline;

    public LatestTask(String name) { this(name, 20_000L, SwingUtilities::invokeLater); }
    public LatestTask(String name, long timeoutMs, Consumer<Runnable> dispatch) {
        this.timeoutMs = timeoutMs;
        this.dispatch = dispatch;
        ThreadFactory factory = task -> { Thread t = new Thread(task, name); t.setDaemon(true); return t; };
        pool = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),factory,
                new ThreadPoolExecutor.DiscardOldestPolicy());
        timer = Executors.newSingleThreadScheduledExecutor(factory);
    }
    public synchronized long version() { return sequence; }
    public synchronized boolean isCurrent(long id) { return !closed && sequence == id; }
    public synchronized <T> void run(Callable<T> work, Consumer<T> success, Consumer<Throwable> failure) {
        if (closed) { return; }
        long id = ++sequence;
        if (pending != null) { pending.cancel(true); }
        if (deadline != null) { deadline.cancel(false); }
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        pending = pool.submit(() -> {
            try {
                T value = work.call();
                if (finished.compareAndSet(false,true)) { publish(id, () -> {
                    try { success.accept(value); }
                    catch (Throwable applyError) { failure.accept(applyError); }
                }); }
            } catch (Throwable error) {
                if (finished.compareAndSet(false,true)) { publish(id, () -> failure.accept(error)); }
            }
        });
        Future<?> task = pending;
        deadline = timer.schedule(() -> {
            if (finished.compareAndSet(false,true)) {
                task.cancel(true);
                publish(id, () -> failure.accept(new TimeoutException("请求超时，请重试")));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }
    private void publish(long id, Runnable apply) {
        dispatch.accept(() -> {
            synchronized (this) {
                if (!isCurrent(id)) { return; }
                try { apply.run(); }
                catch (Throwable callbackError) { com.example.netease.core.PluginLog.e("ui.async", "窗口结果回调失败", callbackError); }
            }
        });
    }
    public synchronized void cancelCurrent() {
        sequence++;
        if (pending != null) { pending.cancel(true); }
        if (deadline != null) { deadline.cancel(false); }
    }
    @Override public synchronized void close() {
        closed = true; cancelCurrent();
        pool.shutdownNow(); timer.shutdownNow();
    }
}
