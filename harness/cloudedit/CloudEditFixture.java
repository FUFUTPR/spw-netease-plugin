package com.example.netease.svc;
import com.example.netease.net.Dto;
import com.example.netease.core.Sql;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Isolated adapters; the CloudPlaylistEdits class under test is extracted from the actual package. */
public final class CloudEditFixture {
    public static final List<String> events=new CopyOnWriteArrayList<>();
    static volatile String remoteError="";
    static volatile boolean localOk=true, switchBeforeSend, switchAfterAck;
    static volatile CountDownLatch waitAck;
    static volatile boolean queueWrite;
    static volatile Runnable queuedWrite;
    static volatile Thread waitingThread;
    static int checks;
    public static void event(String s){events.add(s);}
    static void check(boolean value,String text){if(!value)throw new AssertionError(text+" "+events);checks++;System.out.println("PASS "+text);}
    static Dto.Song song(long id){return new Dto.Song(id,"song","artist","album",1000,0,true,"");}
    static void reset(){CloudPlaylistEdits.stop();events.clear();remoteError="";localOk=true;switchBeforeSend=switchAfterAck=false;waitAck=null;queueWrite=false;queuedWrite=null;AccountService.epoch=1;AccountService.uid=7;}
    static CompletableFuture<String> edit(boolean remove){var done=new CompletableFuture<String>();CloudPlaylistEdits.edit(1,7,List.of(song(101)),10,"target",false,remove,done::complete);return done;}
    static void idle()throws Exception{for(int i=0;i<100&&CloudPlaylistEdits.busy();i++)Thread.sleep(10);check(!CloudPlaylistEdits.busy(),"edit worker returns idle");}
    public static void main(String[] args)throws Exception {
        reset();check(edit(false).get(3,TimeUnit.SECONDS).isEmpty(),"confirmed add completes successfully");idle();
        check(events.equals(List.of("post:101:10:false:false","invalidate","warm:101","seed:101","commit:101:10:false")),"cloud acknowledgement precedes artwork seed and immediate targeted commit");
        reset();check(edit(true).get(3,TimeUnit.SECONDS).isEmpty(),"confirmed removal completes successfully");idle();
        check(events.equals(List.of("post:101:10:false:true","invalidate","commit:101:10:true")),"remove commits only after cloud acknowledgement and does not prefetch artwork");
        reset();remoteError="denied";check(edit(true).get(3,TimeUnit.SECONDS).equals("denied"),"remote refusal is reported");idle();
        check(events.size()==1&&events.getFirst().startsWith("post:"),"remote failure never mutates local membership or retries POST");
        reset();localOk=false;String error=edit(false).get(3,TimeUnit.SECONDS);idle();
        check(error.contains("云端已修改")&&error.contains("本地"),"local refresh failure reports partial success truthfully");
        check(events.stream().filter(e->e.startsWith("post:")).count()==1,"local failure never resends confirmed cloud mutation");
        reset();switchBeforeSend=true;error=edit(true).get(3,TimeUnit.SECONDS);idle();
        check(events.isEmpty()&&error.contains("账号"),"queued mutation checks account again immediately before send");
        reset();switchAfterAck=true;error=edit(false).get(3,TimeUnit.SECONDS);idle();
        check(events.stream().noneMatch(e->e.startsWith("commit:"))&&error.contains("账号"),"account switch after cloud acknowledgement blocks local write");
        reset();waitAck=new CountDownLatch(1);var first=edit(false);
        for(int i=0;i<100&&events.isEmpty();i++)Thread.sleep(10);
        String second=edit(false).get(3,TimeUnit.SECONDS);check(second.contains("正在进行"),"repeated actions cannot enqueue a duplicate cloud write");
        waitAck.countDown();check(first.get(3,TimeUnit.SECONDS).isEmpty(),"original write can complete");idle();
        check(events.stream().filter(e->e.startsWith("post:")).count()==1,"in-flight deduplication emits one POST");
        reset();waitAck=new CountDownLatch(1);var stale=edit(false);
        for(int i=0;i<100&&events.isEmpty();i++)Thread.sleep(10);
        CloudPlaylistEdits.stop();Thread.sleep(50);
        check(events.stream().noneMatch(e->e.startsWith("commit:"))&&!stale.isDone(),"stopping invalidates pending local write and late UI callback");
        reset();var done=new CompletableFuture<String>();CloudPlaylistEdits.edit(1,7,List.of(song(101)),99,"foreign",false,true,done::complete);
        check(done.get(3,TimeUnit.SECONDS).contains("自己创建")&&events.isEmpty(),"foreign playlist is rejected before POST");idle();
        reset();done=new CompletableFuture<>();CloudPlaylistEdits.edit(1,7,List.of(song(101)),20,"favorite",false,true,done::complete);
        check(done.get(3,TimeUnit.SECONDS).isEmpty(),"removing favorite playlist membership succeeds");idle();
        check(events.contains("post:101:20:true:true"),"favorite ID uses unlike endpoint independent of playlist title");
        reset();queueWrite=true;var abandoned=edit(true);
        for(int i=0;i<200&&queuedWrite==null;i++)Thread.sleep(10);
        check(queuedWrite!=null,"mutation can wait in account queue before transport starts");
        waitingThread.interrupt();
        check(!abandoned.get(3,TimeUnit.SECONDS).isEmpty(),"abandoned wait reports uncertainty instead of success");idle();
        queuedWrite.run();
        check(events.isEmpty(),"abandoned queued mutation never sends after failure was returned");
        reset();queueWrite=true;var expired=edit(true);
        check(expired.get(45,TimeUnit.SECONDS).contains("未确认"),"actual forty-second acknowledgement timeout reports uncertainty");idle();
        check(queuedWrite!=null,"timed-out mutation remains queued in isolated account fixture");
        queuedWrite.run();
        check(events.isEmpty(),"timed-out queued removal never sends after user was told it failed");
        CloudPlaylistEdits.stop();System.out.println("CLOUD_EDIT_PIPELINE_RESULT="+checks+" PASS");
    }
}
class AccountService {
    enum State { LOGGED_IN }
    record LoginStatus(State state,long uid) {}
    static volatile long epoch=1,uid=7;
    public static long sessionEpoch(){return epoch;}
    public static LoginStatus status(){return new LoginStatus(State.LOGGED_IN,uid);}
    public static CompletableFuture<List<Dto.PlaylistBrief>> menuPlaylists(long e,long u){return CompletableFuture.completedFuture(List.of(new Dto.PlaylistBrief(10,"target","owner",0,0,true),new Dto.PlaylistBrief(20,"different language favorite","owner",0,0,true)));}
    public static CompletableFuture<Long> likedPlaylist(long e,long u){return CompletableFuture.completedFuture(20L);}
    public static void editSongInPlaylist(long e,long u,long song,long playlist,boolean like,boolean remove,BooleanSupplier valid,Consumer<String> done) {
        if(CloudEditFixture.queueWrite) {
            CloudEditFixture.waitingThread=Thread.currentThread();
            CloudEditFixture.queuedWrite=() -> {
                if(!valid.getAsBoolean()){done.accept("请求已失效");return;}
                CloudEditFixture.event("post:"+song+":"+playlist+":"+like+":"+remove);done.accept("");
            };
            return;
        }
        if(CloudEditFixture.switchBeforeSend)epoch++;
        if(!valid.getAsBoolean()){done.accept("账号已变化");return;}
        CloudEditFixture.event("post:"+song+":"+playlist+":"+like+":"+remove);
        try {if(CloudEditFixture.waitAck!=null)CloudEditFixture.waitAck.await();}catch(InterruptedException ex){Thread.currentThread().interrupt();return;}
        if(CloudEditFixture.switchAfterAck)epoch++;
        done.accept(CloudEditFixture.remoteError);
    }
}
class NativeLibrary { public static boolean editMembershipOn(Sql raw,long playlist,String title,Dto.Song song,boolean remove,String prefix){CloudEditFixture.event("commit:"+song.id()+":"+playlist+":"+remove);return CloudEditFixture.localOk;} }
class PlaybarCover { public static void warmSong(Dto.Song song){CloudEditFixture.event("warm:"+song.id());} }
class CoverDelivery { public static void deliverPlanned(Dto.Song song,String prefix){CloudEditFixture.event("seed:"+song.id());} }
class NativeStreamServer { public static int start(){return 1234;} public static String baseUrl(){return "http://127.0.0.1:1234";} }
