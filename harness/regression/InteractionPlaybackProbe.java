import com.example.netease.net.Dto;
import com.example.netease.host.VoxzenBridge;
import com.example.netease.svc.PlaybarCover;
import com.example.netease.ui.controller.PlaylistMenuController;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Interaction, account switching, in-flight dedupe and same-ID repeat boundaries. */
public class InteractionPlaybackProbe {
    static int checks;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    static Dto.Song song(long id){return new Dto.Song(id,"Song "+id,"Artist","Album",1000,0,true,"");}
    static class Cloud implements PlaylistMenuController.Cloud {
        int reads,writes; long song,playlist; boolean like; Consumer<String> done;
        public List<Dto.PlaylistBrief> playlists(long uid){reads++;return List.of(
            new Dto.PlaylistBrief(10,"Mine","Owner",1,0,true),new Dto.PlaylistBrief(11,"Subscribed","Other",1,0,false),
            new Dto.PlaylistBrief(10,"Duplicate","Owner",1,0,true));}
        public void add(PlaylistMenuController.Identity account,long song,long playlist,boolean like,Consumer<String> done){
            writes++;this.song=song;this.playlist=playlist;this.like=like;this.done=done;
        }
    }
    static Object get(Class<?> cls,String name)throws Exception {var f=cls.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    static void set(Class<?> cls,String name,Object value)throws Exception {var f=cls.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
    static void menu() {
        var account=new AtomicReference<>(new PlaylistMenuController.Identity(1,7));var clock=new AtomicLong(1000);
        var cloud=new Cloud();var states=new ArrayList<PlaylistMenuController.State>();var sync=new AtomicInteger();
        var c=new PlaylistMenuController(account::get,cloud,Runnable::run,states::add,sync::incrementAndGet,clock::get);
        c.open(song(1));check(cloud.reads==1&&c.state().playlists().size()==1,"owned playlists only, deduplicated");
        c.open(song(2));check(cloud.reads==1,"reopening uses account-scoped playlist cache");
        c.add(11,false);check(cloud.writes==0,"subscribed playlist cannot be a write target");
        c.add(10,false);check(cloud.writes==1&&cloud.song==2&&cloud.playlist==10&&!cloud.like,"action captures clicked song and owned playlist");
        c.add(10,false);c.add(0,true);check(cloud.writes==1,"in-flight repeated menu actions are ignored");
        cloud.done.accept("结果未确认");check(sync.get()==0&&!c.state().busy()&&c.state().message().equals("结果未确认"),"uncertain write never claims success or syncs");
        c.add(0,true);check(cloud.writes==2&&cloud.like&&cloud.song==2,"like action uses captured song");
        cloud.done.accept("");check(sync.get()==1&&c.state().message().startsWith("已添加"),"only confirmed cloud success requests local sync");
        cloud.done.accept("");check(sync.get()==1,"duplicate completion callback never repeats success or sync");
        clock.addAndGet(60001);c.open(song(3));check(cloud.reads==2,"playlist cache expires");
        account.set(new PlaylistMenuController.Identity(2,8));c.add(10,false);
        check(cloud.writes==2&&c.state().message().contains("账号已变化"),"account switch rejects stale menu write");
        c.open(song(4));check(cloud.reads==3,"new account never reuses prior playlist cache");
        c.add(10,false);account.set(new PlaylistMenuController.Identity(3,9));int before=states.size();cloud.done.accept("");
        check(sync.get()==1&&!c.state().busy()&&c.state().playlists().isEmpty()&&c.state().message().contains("账号已变化"),
            "late response from prior account clears busy without claiming success");
        c.open(song(5));check(cloud.reads==4&&c.state().song().id()==5,"menu reopens for new account after stale write completes");
        c.add(10,false);var oldCompletion=cloud.done;
        account.set(new PlaylistMenuController.Identity(4,10));c.open(song(6));
        check(cloud.reads==5&&!c.state().busy()&&c.state().song().id()==6,"switching accounts can reopen menu while prior write is pending");
        oldCompletion.accept("");check(c.state().song().id()==6&&sync.get()==1,"old completion cannot overwrite reopened menu");
        c.close();before=cloud.reads;c.open(song(5));check(cloud.reads==before,"closed controller never starts new I/O");
        var tasks=new ArrayList<Runnable>();
        var delayed=new PlaylistMenuController(account::get,cloud,tasks::add,states::add,()->{},clock::get);
        delayed.open(song(6));account.set(new PlaylistMenuController.Identity(5,11));tasks.getFirst().run();
        check(delayed.state().playlists().isEmpty(),"late playlist read cannot repopulate another account menu");
        delayed.close();
        var loggedOut=new PlaylistMenuController(()->new PlaylistMenuController.Identity(1,0),cloud,Runnable::run,states::add,()->{},clock::get);
        before=cloud.reads;loggedOut.open(song(7));loggedOut.add(0,true);
        check(cloud.reads==before&&cloud.writes==4,"logged-out menu performs no cloud request");loggedOut.close();
    }
    static void playback()throws Exception {
        var sequence=(AtomicInteger)get(VoxzenBridge.class,"SEQ");int request=sequence.incrementAndGet();
        set(VoxzenBridge.class,"onlineSong",8001L);set(VoxzenBridge.class,"onlineSeq",request);
        set(VoxzenBridge.class,"onlineAt",System.currentTimeMillis());
        var reports=(Map<Integer,String>)get(VoxzenBridge.class,"PLAY_REPORTS");reports.put(request,"正在解析音源…");
        int a=VoxzenBridge.playOnlineRequest(song(8001),"lossless"),b=VoxzenBridge.playOnlineRequest(song(8001),"lossless");
        check(a==request&&b==request&&sequence.get()==request,"same pending online song reuses request before any network work");
        VoxzenBridge.observePlaying(8002);check(!VoxzenBridge.playbackStarted(request),"other song callback is not playback confirmation");
        VoxzenBridge.observePlaying(8001);check(!VoxzenBridge.playbackStarted(request),"prior same-song playback cannot confirm a request still resolving");
        set(VoxzenBridge.class,"submittedOnlineSeq",request);reports.put(request,"已提交给宿主播放器：fixture");
        VoxzenBridge.observePlaying(8001);check(VoxzenBridge.playbackStarted(request),"matching actual playing ID confirms current request");
        sequence.incrementAndGet();check(!VoxzenBridge.playbackStarted(sequence.get()),"prior confirmation never confirms a newer request");
        var cycle=(AtomicLong)get(PlaybarCover.class,"playbackCycle");
        long old=cycle.get();set(PlaybarCover.class,"deliveredCycle",old);
        set(PlaybarCover.class,"currentSongId",8001L);set(PlaybarCover.class,"playingIdCache",8001L);
        set(PlaybarCover.class,"lastOk",8001L);set(PlaybarCover.class,"musicDeliveredId",8001L);
        PlaybarCover.noteHostPlaying(false);PlaybarCover.onHostState("Ready");PlaybarCover.onMusicStarted("resume");
        check(cycle.get()==old&&(long)get(PlaybarCover.class,"lastOk")==8001L,"pause/resume retains cover dedupe");
        PlaybarCover.onHostState("Ended");PlaybarCover.onHostState("Buffering");
        set(PlaybarCover.class,"pendingCycle",cycle.get());((AtomicLong)get(PlaybarCover.class,"pending")).set(8001L);
        PlaybarCover.onMusicStarted("repeat fixture");
        check((long)get(PlaybarCover.class,"lastOk")==-1L&&(long)get(PlaybarCover.class,"musicDeliveredId")==-1L,"same-ID repeat clears prior-cycle delivery keys");
        check((long)get(PlaybarCover.class,"deliveredCycle")==cycle.get(),"repeat consumes exactly current cycle");
        PlaybarCover.onMusicStarted("duplicate Ready");check(cycle.get()==old+1,"duplicate start callbacks never create another repeat cycle");
    }
    public static void main(String[] args)throws Exception {menu();playback();System.out.println("INTERACTION_PLAYBACK_RESULT="+checks+" PASS");}
}
