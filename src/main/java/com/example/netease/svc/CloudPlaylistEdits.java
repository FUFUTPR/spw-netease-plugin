package com.example.netease.svc;

import com.example.netease.NeteasePlugin;
import com.example.netease.cfg.PluginConfig;
import com.example.netease.core.PluginLog;
import com.example.netease.host.HostSqlBridge;
import com.example.netease.net.Dto;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Cloud acknowledgement first, then a guarded incremental Room transaction. No transport retries. */
public final class CloudPlaylistEdits {
    private CloudPlaylistEdits() {}
    private static ExecutorService worker;
    private static boolean busy;
    private static volatile long generation;
    public static synchronized boolean busy() { return busy; }
    private static boolean valid(long epoch,long uid,long token) {
        return token==generation && AccountService.sessionEpoch()==epoch && AccountService.status().uid()==uid
            && AccountService.status().state()==AccountService.State.LOGGED_IN;
    }
    public static synchronized void edit(long epoch,long uid,List<Dto.Song> songs,long playlist,
                                         String title,boolean like,boolean remove,Consumer<String> done) {
        if(busy) { done.accept("已有歌单修改正在进行，请稍候"); return; }
        if(worker==null) worker=Executors.newSingleThreadExecutor(r -> {var t=new Thread(r,"netease-cloud-edit");t.setDaemon(true);return t;});
        long token=generation; busy=true;
        worker.execute(() -> {
            var active=new java.util.concurrent.atomic.AtomicBoolean(true);
            int completed=0;
            try {
                if(!valid(epoch,uid,token)) throw new IllegalStateException("账号已变化，请重新打开菜单");
                var owned=AccountService.menuPlaylists(epoch,uid).get(25,TimeUnit.SECONDS);
                long liked=AccountService.likedPlaylist(epoch,uid).get(25,TimeUnit.SECONDS);
                long target=like ? liked : playlist;
                boolean favorite=target==liked;
                if(owned.stream().noneMatch(p -> p.mine() && p.id()==target))
                    throw new IllegalStateException("只能修改当前账号自己创建的歌单");
                for(var song:List.copyOf(songs)) {
                    if(!valid(epoch,uid,token)) throw new IllegalStateException("账号已变化，后续修改已停止");
                    var ack=new CompletableFuture<String>();
                    AccountService.editSongInPlaylist(epoch,uid,song.id(),target,favorite,remove,
                        () -> active.get() && valid(epoch,uid,token),ack::complete);
                    String error=ack.get(40,TimeUnit.SECONDS);
                    if(error!=null && !error.isBlank()) throw new IllegalStateException(error);
                    completed++;
                    NeteasePlugin.invalidateSyncForPlaylistEdit();
                    if(!valid(epoch,uid,token)) throw new IllegalStateException("云端已修改，账号已变化，本地更新已停止");
                    if(!remove) PlaybarCover.warmSong(song); // Local decoded bytes, no image download on the host worker.
                    if(PluginConfig.mergePlaylists()) {
                        NativeStreamServer.start();
                        if(!remove) CoverDelivery.deliverPlanned(song,NativeStreamServer.baseUrl()+"/netease/");
                        Boolean committed=HostSqlBridge.onHostConnection(HostSqlBridge.guarded(
                            () -> valid(epoch,uid,token) && PluginConfig.mergePlaylists(),
                            sql -> NativeLibrary.editMembershipOn(sql,target,title,song,remove,NativeStreamServer.baseUrl()+"/netease/")));
                        if(!Boolean.TRUE.equals(committed)) throw new IllegalStateException("网易云已修改，本地歌单更新未完成，请手动同步歌单");
                    }
                }
                active.set(false);
                if(token==generation) done.accept("");
            } catch(Throwable error) {
                // A timed-out account task may still be queued. Revoke it before reporting failure
                // or allowing another edit; generation alone changes only on plugin shutdown.
                active.set(false);
                if(error instanceof InterruptedException) Thread.currentThread().interrupt();
                PluginLog.w("cloud-edit","歌单修改停止，已确认云端 "+completed+" 首："+error);
                String message=error.getMessage();
                if(token==generation) done.accept((completed>0 ? "云端已修改 "+completed+" 首；" : "")+
                    (message==null ? "修改结果未确认，请先检查网易云歌单" : message));
            } finally { active.set(false); synchronized(CloudPlaylistEdits.class) { if(token==generation) busy=false; } }
        });
    }
    public static synchronized void stop() { generation++;busy=false;if(worker!=null)worker.shutdownNow();worker=null; }
}
