import com.example.netease.ui.LatestTask;
import com.example.netease.net.NeteaseApi;
import com.example.netease.net.Dto;
import com.example.netease.core.Json;
import com.example.netease.host.VoxzenBridge;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class AsyncProbe {
    static int passed;
    static void check(boolean value,String label) {
        if (!value) { throw new AssertionError(label); }
        passed++; System.out.println("PASS " + label);
    }
    static void waitFor(java.util.function.BooleanSupplier ready) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while (!ready.getAsBoolean() && System.nanoTime()<end) { Thread.sleep(5); }
        check(ready.getAsBoolean(),"bounded async completion");
    }
    static void ignoreInterrupt(CountDownLatch latch) {
        boolean done=false;
        while (!done) { try { latch.await(); done=true; } catch (InterruptedException ignored) { } }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        AtomicReference<String> rendered=new AtomicReference<>("previous");
        AtomicInteger failures=new AtomicInteger();
        CountDownLatch oldEntered=new CountDownLatch(1),oldRelease=new CountDownLatch(1),oldFinished=new CountDownLatch(1);
        try (LatestTask tasks=new LatestTask("probe-order",1000,Runnable::run)) {
            tasks.run(() -> { oldEntered.countDown(); ignoreInterrupt(oldRelease); oldFinished.countDown(); return "old"; },rendered::set,e->failures.incrementAndGet());
            check(oldEntered.await(1,TimeUnit.SECONDS),"old request running");
            tasks.run(() -> "new",rendered::set,e->failures.incrementAndGet());
            waitFor(()->rendered.get().equals("new"));
            oldRelease.countDown(); check(oldFinished.await(1,TimeUnit.SECONDS),"late response returned");
            Thread.sleep(30);
            check(rendered.get().equals("new") && failures.get()==0,"late response cannot overwrite latest result");
            tasks.run(()->{throw new IllegalStateException("injected");},rendered::set,e->failures.incrementAndGet());
            waitFor(()->failures.get()==1);
            check(rendered.get().equals("new"),"failed refresh preserves valid data");
        }
        CountDownLatch slowRelease=new CountDownLatch(1);
        AtomicInteger timeout=new AtomicInteger(),late=new AtomicInteger();
        try (LatestTask tasks=new LatestTask("probe-timeout",20,Runnable::run)) {
            tasks.run(()->{ignoreInterrupt(slowRelease);return "late";},v->late.incrementAndGet(),e->{if(e instanceof TimeoutException)timeout.incrementAndGet();});
            waitFor(()->timeout.get()==1); slowRelease.countDown(); Thread.sleep(30);
            check(late.get()==0 && timeout.get()==1,"timed out response cannot publish later");
        }
        ConcurrentLinkedQueue<Runnable> ui=new ConcurrentLinkedQueue<>();
        AtomicInteger writes=new AtomicInteger();
        LatestTask closed=new LatestTask("probe-close",1000,ui::add);
        closed.run(()->"queued",v->writes.incrementAndGet(),e->writes.incrementAndGet());
        waitFor(()->!ui.isEmpty()); long version=closed.version(); closed.close();
        for(Runnable r:ui){r.run();}
        check(writes.get()==0 && !closed.isCurrent(version),"close invalidates already queued UI callbacks");
        closed.run(()->"forbidden",v->writes.incrementAndGet(),e->writes.incrementAndGet());
        check(writes.get()==0,"closed window rejects requests");

        var parse=NeteaseApi.class.getDeclaredMethod("parseSearch",Object.class); parse.setAccessible(true);
        Object data=Json.parse("{\"result\":{\"songs\":[{\"id\":1,\"name\":\"old\",\"artists\":[{\"name\":\"a\"}],\"album\":{\"name\":\"b\"},\"duration\":123,\"privilege\":{\"st\":0}},{\"id\":2,\"name\":\"new\",\"ar\":[{\"name\":\"c\"}],\"al\":{\"name\":\"d\"},\"dt\":456,\"privilege\":{\"st\":-200}},{\"id\":3,\"name\":\"unknown\"}]}}");
        List<Dto.Song> songs=(List<Dto.Song>)parse.invoke(null,data);
        check(songs.size()==3 && songs.get(0).playable(),"search st=0 is playable");
        check(!songs.get(1).playable(),"negative privilege is unplayable");
        check(songs.get(2).playable(),"missing privilege defers to playback resolver");
        check(songs.get(1).artists().equals("c") && songs.get(1).durationMs()==456,"search handles modern metadata");
        check(songs.get(0).album().equals("b") && songs.get(0).durationMs()==123,"search handles legacy metadata");
        var set=VoxzenBridge.class.getDeclaredMethod("set",int.class,String.class);set.setAccessible(true);
        set.invoke(null,901,"first");set.invoke(null,902,"second");
        check(VoxzenBridge.playbackReport(901).equals("first") && VoxzenBridge.playbackReport(902).equals("second"),"playback reports belong to their own request");
        VoxzenBridge.stop();
        int afterStop=VoxzenBridge.playOnlineRequest(songs.get(0),"standard");
        check(VoxzenBridge.playbackReport(afterStop).contains("已停用"),"disabled plugin rejects playback without network work");
        System.out.println("ASYNC_RESULT="+passed+" PASS");
    }
}
