package com.example.netease.svc;

import com.example.netease.core.*;
import com.example.netease.host.*;
import com.example.netease.net.Dto;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Only cloud tracks with a current ordinary playlist membership belong in Songs.
 * Keep orphan rows for playback/history; filtering never queries Room on the UI thread. */
public final class DailySongVisibility {
    private static final Set<String> known = ConcurrentHashMap.newKeySet();
    private static volatile Set<String> hidden = Set.of();
    private static ScheduledExecutorService worker;
    private static volatile boolean loaded;
    private DailySongVisibility() {}
    private static Path file() { return DataPaths.data().resolve("daily-track-origins.txt"); }
    public static synchronized void start() {
        if (worker != null) return;
        try {load();}catch(Exception error){PluginLog.w("daily","每日推荐曲目来源读取失败："+error.getClass().getSimpleName());}
        worker = Executors.newSingleThreadScheduledExecutor(r -> {var t=new Thread(r,"netease-daily-visibility");t.setDaemon(true);return t;});
        worker.scheduleWithFixedDelay(() -> {
            try {
                load();
                Boolean changed = HostSqlBridge.onHostConnection(DailySongVisibility::refreshOn);
                if (Boolean.TRUE.equals(changed)) HostTrackListGate.refresh("网易云曲目歌单归属变化");
            } catch (Throwable error) { PluginLog.d("daily", "曲目可见性稍后重试："+error.getClass().getSimpleName()); }
        },1,2,TimeUnit.SECONDS);
    }
    private static synchronized void load() throws Exception {
        if (loaded) return;
        if (Files.isRegularFile(file())) for(String line:Files.readAllLines(file()))
            if(line.matches("netease-[0-9]+"))known.add(line);
        hidden=Set.copyOf(known);loaded=true;
    }
    public static synchronized void record(List<Dto.Song> songs) throws Exception {
        load();boolean changed=false;
        for(var song:songs)if(song.id()>0)changed|=known.add(NativeLibrary.ID_PREFIX+song.id());
        if(changed) {
            var next=new HashSet<>(hidden);next.addAll(known);hidden=Set.copyOf(next);
            Files.createDirectories(file().getParent());
            var temp=Files.createTempFile(file().getParent(),"daily-origin-",".tmp");
            try {Files.write(temp,new TreeSet<>(known));Files.move(temp,file(),StandardCopyOption.REPLACE_EXISTING);}
            finally {Files.deleteIfExists(temp);}
        }
    }
    public static boolean hidden(String id) { return hidden.contains(id); }
    public static boolean refreshOn(Sql raw) throws Exception {
        if(!(raw instanceof NativeLibrary.SqlSchema sql))throw new IllegalStateException("曲库查询不可用");
        var layout=new PlaylistLinks(sql);
        var allowed=new HashSet<String>();
        String query=layout.modern
            ? "SELECT DISTINCT s.primaryTrackId,s.playbackTrackId FROM PlaylistTrack p JOIN SyncTrack s ON s.id=p.syncTrackId AND s.scopeId=p.scopeId JOIN Playlist pl ON pl.id=p.playlistId AND pl.scopeId=p.scopeId WHERE p.isRemoved=0 AND p.playlistId<>?"
            : "SELECT DISTINCT p.trackId FROM PlaylistTrack p JOIN Playlist pl ON pl.id=p.playlistId WHERE p.playlistId<>?";
        for(var row:sql.rows(query,DailyRecommendations.HOST_ID))for(Object id:row)if(id!=null)allowed.add(String.valueOf(id));
        var next=new HashSet<>(known);
        for(var row:sql.rows("SELECT id FROM Track WHERE id GLOB 'netease-[0-9]*'")) {
            String id=String.valueOf(row[0]);
            if(id.matches("netease-[0-9]+"))next.add(id);
        }
        next.removeAll(allowed);
        var snapshot=Set.copyOf(next);boolean changed=!snapshot.equals(hidden);hidden=snapshot;return changed;
    }
    public static synchronized void stop() {if(worker!=null)worker.shutdownNow();worker=null;}
}
