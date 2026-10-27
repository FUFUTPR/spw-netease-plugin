package com.example.netease.host;

/** One completion notice per explicit refresh; background repository emissions never create a ticket. */
public final class LibraryRefreshNoticePolicy {
    private static final long TIMEOUT_MS = 10 * 60_000L;
    private boolean manual;
    private long requestedAt, changedAt;
    private String pending;

    public synchronized void request(long now) {
        manual = true;
        requestedAt = changedAt = now;
        pending = null;
    }

    public synchronized void updated(String message, long now) {
        if (!manual) return;
        pending = message;
        changedAt = now;
    }

    public synchronized String poll(long now, boolean scanning, boolean syncing) {
        if (!manual) return null;
        if (now - requestedAt >= TIMEOUT_MS) { reset(); return null; }
        if (scanning || syncing) { changedAt = now; return null; }
        // Native sync state reaches the UI on its 250ms tick; then allow its 120ms exit.
        if (pending == null || now - changedAt < 600L) return null;
        String result = pending;
        reset();
        return result;
    }

    public synchronized void reset() { manual = false; pending = null; }
}
