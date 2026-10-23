package spw.harness.playback;
import com.example.netease.host.VoxzenBridge;
import com.xuncorp.voxzen.service.PlaybackController;

public final class PlaybackBridgeProbe {
    public static final class Item {}
    static int checks;
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    public static void main(String[] args)throws Exception {
        var drive=VoxzenBridge.class.getDeclaredMethod("driveOnWorker",int.class,Object.class,Object.class,String.class,String.class,String.class,String.class,String.class);
        drive.setAccessible(true);var ctrl=PlaybackController.INSTANCE;var item=new Item();
        drive.invoke(null,101,item,item,"fixture","Song","Artist","netease-1","fixture-local");
        check(ctrl.queue==1&&ctrl.prepare==1&&ctrl.play==1,"native queue -> prepare -> play exactly once");
        check(ctrl.select==0&&ctrl.toggle==0,"no random-queue reselection or play/pause toggle");
        Thread.sleep(2700);
        check(ctrl.prepare==1&&ctrl.play==1,"no delayed replay after initial start");
        ctrl.reset();ctrl.failQueue=true;
        drive.invoke(null,102,item,item,"fixture","Song","Artist","netease-2","fixture-local");
        check(ctrl.select==0&&ctrl.play==0&&VoxzenBridge.playbackReport(102).startsWith("失败"),"rejected queue never replays a stale song");
        ctrl.reset();ctrl.failSelect=true;
        drive.invoke(null,103,item,item,"fixture","Song","Artist","netease-3","fixture-local");
        check(ctrl.prepare==1&&ctrl.play==0&&ctrl.toggle==0,"prepare error never retries another start path");
        check(VoxzenBridge.playbackReport(103).startsWith("失败"),"rejected selection is reported as failure");
        System.out.println("PLAYBACK_BRIDGE_RESULT="+checks+" PASS");
    }
}
