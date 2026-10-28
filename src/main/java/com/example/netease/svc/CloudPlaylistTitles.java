package com.example.netease.svc;

import com.example.netease.NeteasePlugin;
import com.example.netease.host.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CloudPlaylistTitles {
    private static ExecutorService worker;
    private static volatile long generation;
    private CloudPlaylistTitles() {}
    public static synchronized CompletableFuture<String> rename(long id,String title,String description) {
        if(worker==null)worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"netease-cloud-title");t.setDaemon(true);return t;});
        long token=generation,epoch=AccountService.sessionEpoch(),uid=AccountService.status().uid();
        var active=new AtomicBoolean(true);
        return CompletableFuture.supplyAsync(()->{
            java.util.function.BooleanSupplier valid=()->active.get()&&token==generation&&epoch==AccountService.sessionEpoch()&&uid==AccountService.status().uid();
            try {
                if(title==null||title.isBlank()||title.length()>40)return "歌单标题须为1至40字";
                NeteasePlugin.invalidateSyncForPlaylistEdit();
                AccountService.renamePlaylist(epoch,uid,id,title.trim(),valid).get(45,TimeUnit.SECONDS);
                Boolean done=HostSqlBridge.onHostConnection(HostSqlBridge.guarded(valid,sql->{
                    NativeLibrary.renameCloudPlaylistOn(sql,id,title.trim(),description);return true;
                }));
                if(!Boolean.TRUE.equals(done))return "网易云已改名，本地更新未完成，请刷新歌单核对";
                VoxzenBridge.refreshHostLibrary();return "";
            }catch(Throwable failure){
                Throwable cause=failure instanceof ExecutionException&&failure.getCause()!=null?failure.getCause():failure;
                return cause.getMessage()==null?"修改未完成，请先刷新歌单核对":cause.getMessage();
            }finally{active.set(false);}
        },worker);
    }
    public static synchronized void stop(){generation++;if(worker!=null)worker.shutdownNow();worker=null;}
}
