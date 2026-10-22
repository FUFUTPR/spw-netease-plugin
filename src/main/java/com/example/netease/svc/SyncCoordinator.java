package com.example.netease.svc;

import com.example.netease.core.PluginLog;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/** Owns every sync launch; host SQL and network work remain in their existing services. */
public final class SyncCoordinator {
    public enum RequestResult { STARTED, BUSY, DISABLED, UNAUTHENTICATED, FAILED }
    private final BooleanSupplier enabled;
    private final LongSupplier uid;
    private final ToLongFunction<String> begin;
    private final LongPredicate valid;
    private final Consumer<List<String[]>> execute;
    private final Supplier<String> summary;
    private final Consumer<String> end;

    public SyncCoordinator(BooleanSupplier enabled, LongSupplier uid, ToLongFunction<String> begin,
                           LongPredicate valid, Consumer<List<String[]>> execute,
                           Supplier<String> summary, Consumer<String> end) {
        this.enabled = enabled;
        this.uid = uid;
        this.begin = begin;
        this.valid = valid;
        this.execute = execute;
        this.summary = summary;
        this.end = end;
    }

    public RequestResult request(List<String[]> list, String why) {
        List<String[]> snapshot = list == null ? List.of() : List.copyOf(list);
        String reason = why == null || why.isBlank() ? "未说明" : why;
        if (!enabled.getAsBoolean()) { return RequestResult.DISABLED; }
        if (snapshot.isEmpty() && uid.getAsLong() <= 0) { return RequestResult.UNAUTHENTICATED; }
        long generation = begin.applyAsLong(reason);
        if (generation < 0) { return RequestResult.BUSY; }
        try {
            Thread worker = new Thread(() -> {
                try {
                    if (valid.test(generation)) { execute.accept(snapshot); }
                } finally { end.accept(summary.get()); }
            }, "netease-native-sync");
            worker.setDaemon(true);
            worker.start();
            return RequestResult.STARTED;
        } catch (Throwable error) {
            end.accept("线程未能启动：" + error);
            PluginLog.e("sync", "原生同步线程启动失败（已放闸）", error);
            return RequestResult.FAILED;
        }
    }
}
