import com.example.netease.core.DataPaths;
import com.example.netease.core.HostBridgeWorker;
import com.example.netease.core.PluginLog;
import com.example.netease.net.Dto;
import com.example.netease.svc.NativeLibrary;
import com.example.netease.svc.SyncCoordinator;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class BehaviorProbe {
    static int passed;
    static void check(boolean value, String label) {
        if (!value) { throw new AssertionError(label); }
        passed++; System.out.println("PASS " + label);
    }
    static void await(java.util.function.BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < end) { Thread.sleep(5); }
        check(ready.getAsBoolean(), "bounded task completion");
    }
    static void waitIgnoringInterrupt(CountDownLatch latch) {
        boolean done = false;
        while (!done) { try { latch.await(); done = true; } catch (InterruptedException ignored) { } }
    }
    static void coordinator() throws Exception {
        AtomicLong uid = new AtomicLong(10), session = new AtomicLong(), gen = new AtomicLong();
        AtomicBoolean gate = new AtomicBoolean(), enabled = new AtomicBoolean(true);
        AtomicInteger ends = new AtomicInteger();
        AtomicReference<SyncCoordinator.Ticket> ticket = new AtomicReference<>();
        AtomicReference<SyncCoordinator> ref = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        SyncCoordinator c = new SyncCoordinator(enabled::get, uid::get, session::get,
                reason -> gate.compareAndSet(false,true) ? gen.get() : -1,
                value -> gen.get() == value, list -> {
                    ticket.set(ref.get().ticket()); entered.countDown(); waitIgnoringInterrupt(release);
                    ref.get().check(ticket.get());
                }, () -> "done", text -> { gate.set(false); ends.incrementAndGet(); });
        ref.set(c);
        check(c.request(List.of(), "force:test") == SyncCoordinator.RequestResult.STARTED, "sync starts");
        check(entered.await(2,TimeUnit.SECONDS), "worker entered");
        check(c.request(List.of(), "force:duplicate") == SyncCoordinator.RequestResult.BUSY, "duplicate sync rejected");
        session.incrementAndGet(); uid.set(20);
        check(!c.isValid(ticket.get()), "account switch invalidates ticket");
        uid.set(10); session.incrementAndGet();
        check(!c.isValid(ticket.get()), "same account after logout cannot revive ticket");
        release.countDown(); await(() -> ends.get() == 1);
        check(c.status().phase() == SyncCoordinator.Phase.ABORTED, "stale sync is aborted, never success");
        c.stop();
        check(c.request(List.of(), "force:after stop") == SyncCoordinator.RequestResult.STOPPED, "stop is terminal");
        check(!gate.get(), "sync finally releases gate");
        c.beginLifecycle();
        check(!c.isValid(ticket.get()), "explicit re-enable never revives old sync ticket");
        check(c.request(List.of(), "force:re-enabled") == SyncCoordinator.RequestResult.STARTED, "explicit re-enable admits new synchronization");
        await(() -> ends.get() == 2);
        check(c.status().phase() == SyncCoordinator.Phase.COMPLETED, "re-enabled synchronization completes normally");
        c.stop();

        var drainRef = new AtomicReference<SyncCoordinator>();
        var drainTicket = new AtomicReference<SyncCoordinator.Ticket>();
        var drainEntered = new CountDownLatch(1); var drainRelease = new CountDownLatch(1);
        var drainEnds = new AtomicInteger();
        var draining = new SyncCoordinator(() -> true, () -> 1L, () -> 0L, r -> 0L, g -> true,
                list -> { drainTicket.set(drainRef.get().ticket()); drainEntered.countDown();
                    waitIgnoringInterrupt(drainRelease); drainRef.get().check(drainRef.get().ticket()); },
                () -> "done", text -> drainEnds.incrementAndGet());
        drainRef.set(draining); draining.request(List.of(), "old lifecycle");
        check(drainEntered.await(2, TimeUnit.SECONDS), "old lifecycle work entered");
        draining.stop(); draining.beginLifecycle();
        check(!draining.isValid(drainTicket.get()), "re-enable invalidates same-account old ticket even if I/O ignores interruption");
        check(draining.request(List.of(), "new lifecycle") == SyncCoordinator.RequestResult.BUSY,
                "new lifecycle waits for old worker to release the shared write gate");
        drainRelease.countDown(); await(() -> drainEnds.get() == 1 && draining.status().phase() == SyncCoordinator.Phase.IDLE);
        check(draining.status().phase() == SyncCoordinator.Phase.IDLE, "late old lifecycle completion cannot overwrite new sync status");
        check(draining.request(List.of(), "new lifecycle") == SyncCoordinator.RequestResult.STARTED,
                "new lifecycle synchronization starts after old worker exits");
        await(() -> drainEnds.get() == 2); draining.stop();

        AtomicReference<SyncCoordinator> timeout = new AtomicReference<>();
        AtomicInteger timeoutEnds = new AtomicInteger();
        SyncCoordinator slow = new SyncCoordinator(() -> true, () -> 1, () -> 0,
                r -> 0, v -> true, list -> {
                    try { Thread.sleep(40); } catch (InterruptedException ignored) { }
                    timeout.get().check(timeout.get().ticket());
                }, () -> "done", s -> timeoutEnds.incrementAndGet(), 10);
        timeout.set(slow); slow.request(List.of(), "timeout");
        await(() -> timeoutEnds.get() == 1);
        check(slow.status().phase() == SyncCoordinator.Phase.ABORTED, "timeout aborts sync");

        AtomicInteger failEnds = new AtomicInteger();
        SyncCoordinator failure = new SyncCoordinator(() -> true, () -> 1, () -> 0, r -> 0,
                v -> true, list -> { throw new IllegalStateException("injected"); }, () -> "", s -> failEnds.incrementAndGet());
        failure.request(List.of(), "failure"); await(() -> failEnds.get() == 1);
        check(failure.status().phase() == SyncCoordinator.Phase.FAILED, "exception is failed");
        check(failure.request(List.of(), "force:retry") == SyncCoordinator.RequestResult.STARTED, "failed task can retry");
        await(() -> failEnds.get() == 2); failure.stop(); slow.stop();
    }
    static long scalar(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + NativeLibrary.dbPath());
             Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { return r.getLong(1); }
    }
    static void database() throws Exception {
        Files.createDirectories(NativeLibrary.hostDataDir());
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + NativeLibrary.dbPath()); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE Track(id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, albumArtist TEXT NOT NULL, path TEXT NOT NULL, readable INT NOT NULL, size INT NOT NULL, modifiedTime INT NOT NULL, duration INT NOT NULL, `order` INT NOT NULL, number INT NOT NULL, addedTime INT NOT NULL, isFavorite INT NOT NULL, playCount INT NOT NULL)");
            s.execute("CREATE TABLE Playlist(id TEXT PRIMARY KEY, title TEXT NOT NULL, `order` INT NOT NULL)");
            s.execute("CREATE TABLE PlaylistTrack(playlistId TEXT NOT NULL,trackId TEXT NOT NULL,addedTime INT NOT NULL,`order` INT NOT NULL,PRIMARY KEY(playlistId,trackId),FOREIGN KEY(playlistId) REFERENCES Playlist(id),FOREIGN KEY(trackId) REFERENCES Track(id))");
            s.execute("INSERT INTO Track VALUES('local','local title','a','b','a','file:///local',1,10,1,100,0,1,1,1,9)");
            s.execute("INSERT INTO Playlist VALUES('local-list','local list',0)");
            s.execute("INSERT INTO PlaylistTrack VALUES('local-list','local',1,0)");
        }
        Dto.Song a = new Dto.Song(1,"online","artist","album",1000,0,true,"");
        Dto.Song b = new Dto.Song(2,"online2","artist","album",1000,0,true,"");
        List<NativeLibrary.Entry> entries = List.of(new NativeLibrary.Entry(1,"test",List.of(a)));
        AtomicInteger checks = new AtomicInteger();
        var cancelled = NativeLibrary.sync(entries,"http://127.0.0.1/",List.of(),Map.of(),() -> checks.incrementAndGet()==1);
        check(cancelled.error()!=null && scalar("SELECT count(*) FROM Track WHERE id LIKE 'netease-%'")==0,
                "cancel before commit rolls back inserted tracks");
        check(scalar("SELECT count(*) FROM Playlist WHERE id LIKE 'netease-%'")==0, "cancel rolls back playlist too");
        check(NativeLibrary.sync(entries,"http://127.0.0.1/",List.of(),Map.of()).error()==null,"database sync succeeds");
        check(NativeLibrary.sync(entries,"http://127.0.0.1/",List.of(),Map.of()).error()==null,"repeat sync succeeds");
        check(scalar("SELECT count(*) FROM PlaylistTrack WHERE playlistId='netease-pl-1'")==1,"repeat sync has no duplicate links");
        try (Connection c=DriverManager.getConnection("jdbc:sqlite:"+NativeLibrary.dbPath()); Statement s=c.createStatement()) {
            s.execute("INSERT INTO PlaylistTrack VALUES('local-list','netease-1',1,1)");
            s.execute("CREATE TRIGGER injected BEFORE INSERT ON Track WHEN NEW.id='netease-2' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
        }
        var failed=NativeLibrary.sync(List.of(new NativeLibrary.Entry(1,"changed",List.of(a,b))),"http://127.0.0.1/",List.of(),Map.of());
        check(failed.error()!=null,"SQL failure reported");
        check(scalar("SELECT count(*) FROM PlaylistTrack WHERE playlistId='netease-pl-1'")==1,"SQL failure restores previous membership");
        check(scalar("SELECT count(*) FROM PlaylistTrack WHERE playlistId='local-list'")==2,"local playlist including online track retained");
        check(scalar("SELECT playCount FROM Track WHERE id='local'")==9,"local playback data retained");
    }
    public static void main(String[] args) throws Exception {
        Path sandbox=Path.of(args[0]).toAbsolutePath();
        if (!sandbox.equals(Path.of(System.getenv("APPDATA")).toAbsolutePath())) { throw new SecurityException("sandbox mismatch"); }
        DataPaths.init("com.example.netease"); PluginLog.init(DataPaths.logs());
        coordinator(); database();
        HostBridgeWorker.get().start();
        AtomicBoolean cleaned=new AtomicBoolean();
        HostBridgeWorker.get().stopWithCleanup(() -> cleaned.set(HostBridgeWorker.get().isOnWorkerThread()));
        check(cleaned.get(),"cleanup executes on host worker before stop");
        check(!HostBridgeWorker.get().submit(() -> {}),"stopped host worker rejects work");
        com.example.netease.svc.NativeStreamServer.stop();
        check(com.example.netease.svc.NativeStreamServer.start()<0,"stopped stream service cannot be resurrected by a late callback");
        System.out.println("BEHAVIOR_RESULT="+passed+" PASS");
    }
}
