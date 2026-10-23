import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import androidx.compose.runtime.*;
import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.unit.*;
import androidx.compose.ui.semantics.*;
import androidx.compose.ui.text.AnnotatedString;
import com.example.netease.net.Dto;
import com.example.netease.svc.AccountService;
import com.example.netease.ui.controller.*;
import kotlin.Unit;
import kotlin.jvm.functions.*;

/** Real installed Salt components + production pages, rendered without opening
 * the player, logging in, making requests or writing the user's data directory.
 * Only data/ports are fixtures; the renderer/components are from the host JAR.
 * This is a render/interaction regression, not a live plugin-installation test. */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class HostSaltRenderProbe {
    static final String UI="com.example.netease.nativeui.NativeUiState";
    static final String SESSION="com.example.netease.nativeui.NativeUiSession";
    static List<Dto.Song> SONGS=List.of(
        new Dto.Song(1001,"夜曲","周杰伦","十一月的萧邦",238000,0,true,""),
        new Dto.Song(1002,"不可播放的测试曲目","歌手","专辑",220000,0,false,""),
        new Dto.Song(1003,"会员曲目","歌手","专辑",180000,1,true,""));
    static int checks,plays,searches,originalCalls;
    static com.sun.net.httpserver.HttpServer artworkServer;
    static String artworkUrl;
    static void startArtwork()throws Exception {
        var img=new java.awt.image.BufferedImage(160,160,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g=img.createGraphics();g.setColor(new java.awt.Color(0x176bcc));g.fillRect(0,0,160,160);g.dispose();
        var data=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(img,"png",data);byte[] png=data.toByteArray();
        artworkServer=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        artworkServer.createContext("/art",exchange->{exchange.sendResponseHeaders(200,png.length);try(var out=exchange.getResponseBody()){out.write(png);}});
        artworkServer.start();artworkUrl="http://127.0.0.1:"+artworkServer.getAddress().getPort()+"/art";
        SONGS=SONGS.stream().map(s->new Dto.Song(s.id(),s.name(),s.artists(),s.album(),s.durationMs(),s.fee(),s.playable(),artworkUrl)).toList();
    }
    static final String TOP="com.example.netease.nativeui.NativeTopSearch";
    static int topSearches;
    static byte[] qrFixture()throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8,8,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        return bytes.toByteArray();
    }
    static com.xuncorp.voxzen.ui.navigation.Navigator fixtureNavigator;
    static void field(String owner,String name,Object value)throws Exception {
        Class<?> c=Class.forName(owner);var f=c.getDeclaredField(name);f.setAccessible(true);f.set(null,value);
    }
    static void delegated(String owner,String name,Object value)throws Exception {
        Class<?> c=Class.forName(owner);var f=c.getDeclaredField(name+"$delegate");f.setAccessible(true);((MutableState)f.get(null)).setValue(value);
    }
    static int hostClicks;
    static Method multilayerBlur,titleHookDraw;
    static Object titleHook;
    static HookCall titleCall;
    static Object rootWallpaper,rootPopup;
    public static void rootFixture(boolean enabled,Composer c,int changed,int defaults) {
        try {
            // Match MainWindowContent: these ambient values exist INSIDE the
            // original root, never around RootHook and its sibling overlay.
            multilayerBlur.invoke(null,(Function3<Object,Composer,Integer,Unit>)(scope,composer,flags)->{
                rootBody(composer);return Unit.INSTANCE;
            },c,0);
        }catch(Exception e){throw new RuntimeException(e);}
    }
    static void rootBody(Composer c) {
        try {
            var haze=Class.forName("com.xuncorp.voxzen.ui.component.\u052c");
            rootWallpaper=c.consume((CompositionLocal<?>)haze.getMethod("\u037f").invoke(null));
            rootPopup=c.consume((CompositionLocal<?>)haze.getMethod("\u0528").invoke(null));
            var components=Class.forName("com.example.netease.nativeui.HostSaltComponents");
            var modifier=androidx.compose.foundation.layout.SizeKt.fillMaxSize(androidx.compose.ui.Modifier.Companion,1f);
            components.getMethod("drawButton",Function0.class,androidx.compose.ui.Modifier.class,boolean.class,String.class,Object.class,Composer.class)
                .invoke(components.getField("INSTANCE").get(null),(Function0<Unit>)()->{hostClicks++;return Unit.INSTANCE;},modifier,true,"Plain",
                    (Function3<Object,Composer,Integer,Unit>)(scope,composer,flags)->Unit.INSTANCE,c);
            titleHookDraw.invoke(titleHook,titleCall,c,0);
        }catch(Exception e){throw new RuntimeException(e);}
    }
    public static void titleFixture(Object main,Object a,Object b,androidx.compose.ui.Modifier modifier,Composer c,int changed,int defaults) {
        try {
            var height=Arrays.stream(androidx.compose.foundation.layout.SizeKt.class.getMethods())
                .filter(m->m.getName().startsWith("height-")&&m.getParameterCount()==2).findFirst().orElseThrow();
            androidx.compose.foundation.layout.SpacerKt.Spacer((androidx.compose.ui.Modifier)height.invoke(null,modifier,44f),c,0);
        }catch(Exception e){throw new RuntimeException(e);}
    }
    static void topFixture(int width)throws Exception {
        var appConfig=Class.forName("com.xuncorp.voxzen.util.AppConfig");
        appConfig.getMethod("setMultilayerBlurEffect",boolean.class).invoke(appConfig.getField("INSTANCE").get(null),true);
        var rows=new ArrayList<Dto.Song>();for(int i=1;i<=7;i++)rows.add(new Dto.Song(3000+i,"候选"+i,"测试歌手","测试专辑",180000,0,true,artworkUrl));
        var top=new SearchController(s->{try{call(TOP,"setState",new Class[]{SearchController.State.class},s);}catch(Exception e){throw new RuntimeException(e);}},new SearchController.Async(){
            public <T>void run(SearchController.Fetcher<T> ignored,Consumer<T> ok,Consumer<Throwable> bad){topSearches++;ok.accept((T)rows);}
            public void cancel(){}public void close(){}
        },new SearchController.Playback(){public int playOnline(Dto.Song song,String level){plays++;return 21;}public String report(int id){return "fixture";}},()->"standard",()->0L);
        field(TOP,"controller",top);put("SearchKeyword","夜曲");top.search("夜曲");call(TOP,"setExpanded",new Class[]{boolean.class},true);
        String global="com.example.netease.nativeui.HostGlobalSearch";
        field(global,"root",HostSaltRenderProbe.class.getMethod("rootFixture",boolean.class,Composer.class,int.class,int.class));
        var globalClass=Class.forName(global);var globalObject=globalClass.getField("INSTANCE").get(null);
        var nativeRoot=(Method)Arrays.stream(globalClass.getMethods()).filter(m->m.getName().startsWith("rootMethod$")).findFirst().orElseThrow().invoke(globalObject);
        multilayerBlur=nativeRoot.getDeclaringClass().getDeclaredMethod("\u037f",Function3.class,Composer.class,int.class);
        multilayerBlur.setAccessible(true);
        var nativeTitle=(Method)Arrays.stream(globalClass.getMethods()).filter(m->m.getName().startsWith("titleMethod$")).findFirst().orElseThrow().invoke(globalObject);
        Object main=Arrays.stream(nativeTitle.getParameterTypes()[0].getEnumConstants()).filter(v->((Enum<?>)v).name().equals("Main")).findFirst().orElseThrow();
        titleCall=new HookCall(Arrays.asList(main,null,null,androidx.compose.ui.Modifier.Companion));
        field(global,"title",HostSaltRenderProbe.class.getMethod("titleFixture",Object.class,Object.class,Object.class,androidx.compose.ui.Modifier.class,Composer.class,int.class,int.class));
        Class<?> titleHookType=Class.forName(global+"$TitleHook");
        var instance=titleHookType.getDeclaredField("INSTANCE");instance.setAccessible(true);titleHook=instance.get(null);
        titleHookDraw=titleHookType.getDeclaredMethod("Content",com.xuncorp.spw.workshop.api.hook.UiHookCall.class,Composer.class,int.class);titleHookDraw.setAccessible(true);
        put("Visible",false);inject("started",true);
    }
    static Object originalRoute,originalViewModel;
    static int originalMask;
    public static void originalFixture(Object route,Object vm,Composer composer,int changed,int defaults){
        originalCalls++;originalRoute=route;originalViewModel=vm;originalMask=defaults;
    }
    static final class HookCall extends com.xuncorp.spw.workshop.api.hook.UiHookCall {
        final List<Object> args;
        HookCall(Object route,Object vm){args=Arrays.asList(route,vm);}
        HookCall(List<Object> values){args=values;}
        public Object getThisObject(){return null;}
        public List<Object> getArguments(){return args;}
        public Object getArgument(String name){return null;}
    }
    // ImageComposeScene is driven on this test thread. Unconfined can resume
    // artwork coroutines on IO threads during node disposal, unlike the real UI.
    static final class RenderDispatcher extends kotlinx.coroutines.CoroutineDispatcher {
        final java.util.concurrent.ConcurrentLinkedQueue<Runnable> queue=new java.util.concurrent.ConcurrentLinkedQueue<>();
        @Override public void dispatch(kotlin.coroutines.CoroutineContext context,Runnable task){queue.add(task);}
        void flush(){for(int i=0;i<1000;i++){var task=queue.poll();if(task==null)return;task.run();}}
    }
    static final RenderDispatcher renderDispatcher=new RenderDispatcher();
    static org.jetbrains.skia.Image render(ImageComposeScene scene,long time){frame=Math.max(frame,time);renderDispatcher.flush();return scene.render(frame);}
    static long frame=2000000000L;
    static byte[] frame(ImageComposeScene scene)throws Exception{
        try(var img=render(scene,frame+=100000000L);var data=img.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG,100,0)){return data.getBytes();}
    }
    static void apply(ImageComposeScene scene)throws Exception{
        androidx.compose.runtime.snapshots.Snapshot.Companion.sendApplyNotifications();
        frame(scene);frame(scene);
    }
    static void settle(ImageComposeScene scene)throws Exception {
        androidx.compose.runtime.snapshots.Snapshot.Companion.sendApplyNotifications();
        for(int i=0;i<20;i++)frame(scene);
    }
    static void preview(byte[] png,Path path,Object snapshot)throws Exception {
        var source=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        var result=new java.awt.image.BufferedImage(source.getWidth(),source.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=result.createGraphics();
        try {
            graphics.setColor(new java.awt.Color((int)(packed(snapshot,"Background")>>>32),true));
            graphics.fillRect(0,0,result.getWidth(),result.getHeight());graphics.drawImage(source,0,0,null);
        } finally {graphics.dispose();}
        javax.imageio.ImageIO.write(result,"png",path.toFile());
    }
    static void transparent(byte[] png,String label)throws Exception{
        var img=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        check((img.getRGB(0,img.getHeight()/2)>>>24)==0,label+" leaves host background visible");
    }
    static void embeddedModal(byte[] png,String label)throws Exception{
        var img=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        int alpha=img.getRGB(0,0)>>>24;
        check(alpha>=150 && alpha<=156,label+" uses translucent embedded scrim (alpha="+alpha+")");
    }
    static void modalBounds(ImageComposeScene scene,int width) {
        var dialog=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsProperties.INSTANCE.getIsDialog())).findFirst().orElseThrow();
        var r=dialog.getBoundsInRoot();
        check(r.getLeft()>0 && r.getTop()>0 && r.getRight()<width && r.getBottom()<640,"embedded dialog is centered inside content, not a full page");
    }
    static void accountBackground(ImageComposeScene scene,byte[] png)throws Exception {
        var dialog=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsProperties.INSTANCE.getIsDialog())).findFirst().orElseThrow().getBoundsInRoot();
        var name=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsProperties.INSTANCE.getText())&&n.getConfig().toString().contains("演示账号")).findFirst().orElseThrow().getBoundsInRoot();
        var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        int y=(int)(name.getTop()+name.getHeight()/2);
        check(image.getRGB((int)name.getLeft()-4,y)==image.getRGB((int)dialog.getLeft()+4,y),"account information inherits native dialog background without nested card");
    }
    static long packed(Object snapshot,String name)throws Exception{
        Object palette=snapshot.getClass().getMethod("getPalette").invoke(snapshot);
        Method getter=Arrays.stream(palette.getClass().getMethods()).filter(m->m.getName().startsWith("get"+name+"-")).findFirst().orElseThrow();
        return (Long)getter.invoke(palette);
    }
    static SearchController search;
    static Object call(String name,String method,Class<?>[] types,Object... args)throws Exception {
        Class<?> c=Class.forName(name);
        try{return c.getMethod(method,types).invoke(c.getField("INSTANCE").get(null),args);}
        catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}
    }
    static void put(String property,Object value)throws Exception {
        Class<?> c=Class.forName(UI);
        Method m=Arrays.stream(c.getMethods()).filter(x->x.getName().equals("set"+property)).findFirst().orElseThrow();
        m.invoke(c.getField("INSTANCE").get(null),value);
    }
    static void inject(String field,Object value)throws Exception {
        Class<?> c=Class.forName(SESSION);Field f=c.getDeclaredField(field);f.setAccessible(true);
        f.set(c.getField("INSTANCE").get(null),value);
    }
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    static void publish(String property,Object state){try{put(property,state);}catch(Exception e){throw new RuntimeException(e);}}
    @SuppressWarnings("unchecked")
    static void fixtures(String page)throws Exception {
        search=new SearchController(s->publish("Search",s),new SearchController.Async(){
            public <T>void run(SearchController.Fetcher<T> ignored,Consumer<T> ok,Consumer<Throwable> bad){searches++;ok.accept((T)SONGS);}
            public void cancel(){} public void close(){}
        },new SearchController.Playback(){
            public int playOnline(Dto.Song song,String level){plays++;return 17;}
            public String report(int id){return "fixture";}
        },()->"standard",()->0L);
        inject("searchController",search);search.search("夜曲");search.selectRow(0);
        PlaylistController.Api api=(PlaylistController.Api)Proxy.newProxyInstance(HostSaltRenderProbe.class.getClassLoader(),new Class[]{PlaylistController.Api.class},(p,m,a)->switch(m.getName()){
            case "likedSongs","dailySongs","localPlaylist"->SONGS;
            case "userPlaylists"->List.of();
            case "loggedIn"->true;
            case "uid"->1L;
            case "accountEpoch"->0L;
            default->throw new AssertionError("Unexpected playlist port: "+m);
        });
        var playlist=new PlaylistController(s->publish("Playlist",s),new PlaylistController.Async(){
            public <T>void run(PlaylistController.Fetcher<T> work,Consumer<T> ok,Consumer<Throwable> bad){try{ok.accept(work.fetch());}catch(Exception e){bad.accept(e);}}
            public void cancel(){}public void close(){}
        },api,new PlaylistController.Playback(){
            public int playPlaylist(List<Dto.Song> rows,int start,String level,String source){plays++;return 19;}
            public String report(int id){return "fixture";}
        },()->"standard",()->0L);
        inject("playlistController",playlist);playlist.loadLiked();
        var loginPorts=(LoginController.Ports)Proxy.newProxyInstance(HostSaltRenderProbe.class.getClassLoader(),new Class[]{LoginController.Ports.class},(p,m,a)->switch(m.getName()){
            case "status"->new AccountService.LoginStatus(AccountService.State.WAITING_SCAN,"","",0,false,"",0);
            case "qrImage"->new Dto.QrSession("render-fixture",qrFixture());
            case "startQr","pauseQrWatch"->true;
            case "qrRiskRequired"->page.equals("RISK");
            case "wasLoggedInAtOpen"->false;
            case "qrVerifyUrl"->page.equals("RISK")?"https://example.invalid/render-fixture":"";
            case "maskPhone"->"fixture";
            case "nowMs"->0L;
            case "openVerify","closeVerify","retryQrAfterRisk"->null;
            default->throw new AssertionError("Network/authentication forbidden in render probe: "+m.getName());
        });
        var login=new LoginController(loginPorts,new LoginController.Async(){
            public <T>void run(LoginController.Fetcher<T> work,Consumer<T> ok,Consumer<Throwable> bad){throw new AssertionError("Authentication forbidden");}
            public void cancel(){}public void close(){}
        },s->publish("Login",s));
        inject("loginController",login);login.open();
        if(page.equals("RISK")||page.equals("LOGIN"))login.tick();
        if(page.equals("SMS"))login.switchTo(LoginController.Mode.SMS);
        publish("Login",login.state());
        put("Account",new AccountService.LoginStatus(AccountService.State.LOGGED_IN,"","演示账号",1,false,"",0));
        put("Visible",true);
        Class<?> pg=Class.forName("com.example.netease.nativeui.NativePage");
        put("Page",Enum.valueOf((Class)pg,Set.of("SMS","RISK").contains(page)?"LOGIN":page));
    }
    static ProvidedValue<?>[] hostContext(boolean rootOverlay)throws Exception {
        List<ProvidedValue<?>> locals=new ArrayList<>();
        Class<?> haze=Class.forName("com.xuncorp.voxzen.ui.component.\u052c");
        for(String getter:rootOverlay?List.<String>of():List.of("\u037f","\u0528")){
            var local=(ProvidableCompositionLocal)haze.getMethod(getter).invoke(null);
            locals.add(local.provides(new dev.chrisbanes.haze.HazeState()));
        }
        var route=(com.xuncorp.voxzen.ui.navigation.ScreenRoute)Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute$ModConfigContent").getConstructor(String.class).newInstance("com.example.netease");
        var sheetCtor=com.xuncorp.voxzen.ui.component.PlayerSheetState.class.getConstructors()[0];
        var stateCtor=sheetCtor.getParameterTypes()[0].getConstructors()[0];
        var sheetState=stateCtor.newInstance(stateCtor.getParameterTypes()[0].getEnumConstants()[0],DensityKt.Density(1f,1f),new androidx.compose.animation.core.SnapSpec<>(0),(Function1<Object,Boolean>)v->true);
        var sheet=(com.xuncorp.voxzen.ui.component.PlayerSheetState)sheetCtor.newInstance(sheetState);
        var nav=new com.xuncorp.voxzen.ui.navigation.Navigator(route,Set.of(route),new androidx.navigation3.runtime.NavBackStack<>(),sheet);
        nav.getNavBackStack().add(route);
        fixtureNavigator=nav;
        locals.add(com.xuncorp.voxzen.ui.navigation.AppNavigationKt.getLocalNavigator().provides(nav));
        return locals.toArray(ProvidedValue<?>[]::new);
    }
    static List<SemanticsNode> nodes(ImageComposeScene scene){
        List<SemanticsNode> nodes=new ArrayList<>();
        for(var owner:scene.getSemanticsOwners())walk(owner.getRootSemanticsNode(),nodes);
        return nodes;
    }
    static void walk(SemanticsNode n,List<SemanticsNode> out){out.add(n);for(var c:n.getChildren())walk(c,out);}
    static String dump(ImageComposeScene scene){return nodes(scene).stream().map(n->n.getConfig().toString()).reduce("",(a,b)->a+"\n"+b);}
    static void key(ImageComposeScene scene,int code)throws Exception { key(scene,code,0); }
    static void key(ImageComposeScene scene,int code,int modifiers)throws Exception {
        var event=new java.awt.event.KeyEvent(new java.awt.Canvas(),java.awt.event.KeyEvent.KEY_PRESSED,0,modifiers,code,java.awt.event.KeyEvent.CHAR_UNDEFINED);
        scene.getClass().getMethod("sendKeyEvent-ZmokQxo",Object.class).invoke(scene,androidx.compose.ui.input.key.KeyEvent_desktopKt.toComposeEvent(event));
        render(scene,200000000L).close();
    }
    static void clickButton(ImageComposeScene scene,String text,long time)throws Exception {
        var r=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getOnClick()) && n.getConfig().toString().contains("Text : ["+text+"]")).findFirst().orElseThrow().getBoundsInRoot();
        float x=r.getLeft()+r.getWidth()/2,y=r.getTop()+r.getHeight()/2;
        pointer(scene,1,x,y,time);pointer(scene,2,x,y,time+20);apply(scene);
    }
    static void pointer(ImageComposeScene scene,int event,float x,float y,long time)throws Exception {
        pointer(scene,event,x,y,time,false);
    }
    static void pointer(ImageComposeScene scene,int event,float x,float y,long time,boolean secondary)throws Exception {
        long pos=((long)Float.floatToRawIntBits(x)<<32)|(Float.floatToRawIntBits(y)&0xffffffffL);
        Class<?> buttons=Class.forName("androidx.compose.ui.input.pointer.PointerButtons");
        Class<?> mods=Class.forName("androidx.compose.ui.input.pointer.PointerKeyboardModifiers");
        Class<?> button=Class.forName("androidx.compose.ui.input.pointer.PointerButton");
        Object bs=buttons.getMethod("box-impl",int.class).invoke(null,event==1?(secondary?2:1):0);
        Object km=mods.getMethod("box-impl",int.class).invoke(null,0);
        Object primary=button.getMethod("box-impl",int.class).invoke(null,secondary?1:0);
        scene.getClass().getMethod("sendPointerEvent-BGSDPeU",int.class,long.class,long.class,long.class,int.class,buttons,mods,Object.class,button)
            .invoke(scene,event,pos,0L,time,2,bs,km,null,primary);
        render(scene,time*1000000L).close();
    }
    static void rowInteractions(ImageComposeScene scene)throws Exception {
        var rows=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsProperties.INSTANCE.getContentDescription()) && n.getConfig().toString().contains("夜曲")).toList();
        var rect=rows.getFirst().getBoundsInRoot();float x=rect.getLeft()+rect.getWidth()/2, y=rect.getTop()+rect.getHeight()/2;
        search.selectRow(1);apply(scene);
        int before=plays;
        pointer(scene,1,x,y,1000);
        check(search.state().selectedRow()==0,"primary press selects immediately before release/double-click timeout");
        check(plays==before,"single press never starts playback");
        pointer(scene,2,x,y,1020);
        pointer(scene,1,x,y,1120);pointer(scene,2,x,y,1140);
        Thread.sleep(350);render(scene,1500000000L).close();
        check(plays==before+1,"double click anywhere in native row preserves controller playback");
        check(search.state().selectedRow()==0,"native row focuses/selects after recomposition during press");
    }
    static void menuChecks(ImageComposeScene scene,Path out)throws Exception {
        String menu="com.example.netease.nativeui.NativeSongMenu";
        var account=new com.example.netease.svc.AccountService.LoginStatus(com.example.netease.svc.AccountService.State.LOGGED_IN,"fixture","演示账号",7001,false,"",0);
        field("com.example.netease.svc.AccountService","status",account);
        var identity=new com.example.netease.ui.controller.PlaylistMenuController.Identity(1,7001);
        var writes=new ArrayList<String>();var readTasks=new ArrayList<Runnable>();
        var controller=new com.example.netease.ui.controller.PlaylistMenuController(()->identity,
            new com.example.netease.ui.controller.PlaylistMenuController.Cloud(){
                public List<Dto.PlaylistBrief> playlists(long uid){return List.of(
                    new Dto.PlaylistBrief(9001,"我的测试歌单","演示账号",3,0,true),
                    new Dto.PlaylistBrief(9002,"订阅歌单不应出现","其他用户",4,0,false));}
                public void add(com.example.netease.ui.controller.PlaylistMenuController.Identity acc,long song,long playlist,boolean like,java.util.function.Consumer<String> done){
                    writes.add(song+":"+playlist+":"+like);done.accept("");
                }
            },readTasks::add,state->{try{delegated(menu,"state",state);}catch(Exception e){throw new RuntimeException(e);}},()->{},System::currentTimeMillis);
        field(menu,"controller",controller);
        var row=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsProperties.INSTANCE.getContentDescription())&&n.getConfig().toString().contains("夜曲")).findFirst().orElseThrow().getBoundsInRoot();
        int before=plays;
        pointer(scene,1,row.getLeft()+120,row.getTop()+20,21000,true);
        pointer(scene,2,row.getLeft()+120,row.getTop()+20,21020,true);apply(scene);
        check(plays==before,"right click never triggers playback");
        check((Boolean)call(menu,"getVisible",new Class[]{}),"right click opens production native song menu");
        check(dump(scene).contains("正在读取歌单"),"native popup opens immediately while playlist read is pending");
        readTasks.getFirst().run();apply(scene);
        Files.write(out.resolve("NATIVE-MENU.png"),frame(scene));
        check(controller.state().playlists().size()==1,"menu excludes subscribed playlists");
        String tree=dump(scene);Files.writeString(out.resolve("NATIVE-MENU-tree.txt"),tree);
        check(tree.contains("添加到我喜欢的音乐")&&tree.contains("我的测试歌单"),"actual host popup renders like and owned playlist actions");
        var nativeMenu=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getOnClick())&&n.getConfig().toString().contains("我的测试歌单")).findFirst().orElseThrow().getBoundsInRoot();
        check(nativeMenu.getWidth()>=250 && nativeMenu.getWidth()<=480,"native cloud context menu is wide enough without overflowing window");
        check(!tree.contains("订阅歌单不应出现"),"foreign playlist is absent from native popup");
        clickButton(scene,"我的测试歌单",22000);
        check(writes.size()==1&&writes.getFirst().equals("1001:9001:false"),"native menu action adds clicked song exactly once through cloud port");
        check(plays==before,"playlist menu action does not start playback");
        check(!(Boolean)call(menu,"getVisible",new Class[]{}),"native menu dismisses after action");
        call(menu,"stop",new Class[]{});
    }
    static void searchInteractions(ImageComposeScene scene)throws Exception {
        check(nodes(scene).stream().noneMatch(n->n.getConfig().contains(SemanticsActions.INSTANCE.getSetText())),"results page has no duplicate search input");
        String tree=dump(scene);
        check(tree.contains(SONGS.size()+" 首歌曲"),"results retain song count");
        for(String retired:List.of("播放所选","重试搜索","找到 ","双击播放","「夜曲」"))
            check(!tree.contains(retired),"results omit retired control/hint: "+retired);
        nativeArtwork(scene,"夜曲");
        coverReloadChecks();
    }
    static Object privateValue(Class<?> type,String name)throws Exception {var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    static void coverReloadChecks()throws Exception {
        Class<?> cover=Class.forName("com.example.netease.svc.PlaybarCover");
        var source=com.example.netease.svc.CoverStore.originalImage(com.example.netease.svc.CoverStore.refKey(SONGS.getFirst().album(),SONGS.getFirst().artists()));
        com.example.netease.svc.PlaybarCover.warmSong(SONGS.getFirst());
        Method inject=cover.getDeclaredMethod("inject",byte[].class,int.class,long.class);inject.setAccessible(true);
        check(inject.invoke(null,source,256,1001L)==null,"actual native image owner and image flow accept fixture artwork");
        var owner=privateValue(cover,"lastOwnerInjected");
        Object cache=Class.forName("com.xuncorp.voxzen.image.ScaledImageBitmapCacheKt").getMethod("getSharedScaledImageBitmapCache").invoke(null);
        Method change=Arrays.stream(cache.getClass().getMethods()).filter(m->m.getName().equals("onSourceChanged")).findFirst().orElseThrow();
        field(cover.getName(),"currentSongId",1001L);field(cover.getName(),"playingIdCache",1001L);
        field(cover.getName(),"lastOk",1001L);field(cover.getName(),"musicDeliveredId",1001L);
        field(cover.getName(),"deliveredCycle",((java.util.concurrent.atomic.AtomicLong)privateValue(cover,"playbackCycle")).get());
        com.example.netease.svc.PlaybarCover.onHostState("Ready");
        com.example.netease.svc.PlaybarCover.onMusicStarted("retained-cover fixture");
        check(privateValue(cover,"lastOwnerInjected")==owner,"pause/resume with held image retains the same owner");
        change.invoke(cache,new Object[]{null});field(cover.getName(),"sameTrackRepairAt",0L);
        com.example.netease.svc.PlaybarCover.onMusicStarted("same-song reload fixture");
        var cur=cache.getClass().getDeclaredField("currentSource");cur.setAccessible(true);
        check(cur.get(cache)==owner,"same-song playlist reload restores a cleared native image source immediately");
        owner.getClass().getMethod("close").invoke(owner);change.invoke(cache,new Object[]{null});
        field(cover.getName(),"sameTrackRepairAt",0L);
        com.example.netease.svc.PlaybarCover.onMusicStarted("released-owner reload fixture");
        var fresh=privateValue(cover,"lastOwnerInjected");
        check(fresh!=owner&&cur.get(cache)==fresh,"released host bitmap is rebuilt from cached image bytes on same-song reload");
        com.example.netease.svc.PlaybarCover.onMusicStarted("duplicate ready fixture");
        check(privateValue(cover,"lastOwnerInjected")==fresh,"duplicate ready signal never creates another image owner");
    }
    static void nativeArtwork(ImageComposeScene scene,String title)throws Exception {
        boolean blue=false;long deadline=System.nanoTime()+5_000_000_000L;
        do {
            apply(scene);var row=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsProperties.INSTANCE.getContentDescription())&&n.getConfig().toString().contains(title)).findFirst().orElseThrow().getBoundsInRoot();
            var png=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(frame(scene)));
            int pixel=png.getRGB((int)row.getLeft()+40,(int)row.getTop()+32);
            blue=(pixel&255)>150&&((pixel>>>16)&255)<80;
            if(!blue)Thread.sleep(50);
        }while(!blue&&System.nanoTime()<deadline);
        check(blue,"native song image renders real fixture artwork: "+title);
        long start=System.nanoTime();for(int i=0;i<30;i++)frame(scene);
        System.out.println("RENDER_WARM_30_FRAMES_MS="+(System.nanoTime()-start)/1_000_000);
    }
    static void topChecks(ImageComposeScene scene,int width,Path out)throws Exception {
        String tree=dump(scene);
        check(tree.contains("候选1")&&tree.contains("候选5")&&!tree.contains("候选6"),"topbar keeps first five relevance-ranked results");
        var input=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getSetText())).findFirst().orElseThrow();
        check(input.getBoundsInRoot().getBottom()<=44,"input is inside titlebar");
        int before=plays,full=searches;
        var row=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getOnClick())&&n.getConfig().toString().contains("候选1")).findFirst().orElseThrow().getBoundsInRoot();
        pointer(scene,1,row.getLeft()+row.getWidth()/2,row.getTop()+row.getHeight()/2,11000);
        pointer(scene,2,row.getLeft()+row.getWidth()/2,row.getTop()+row.getHeight()/2,11020);apply(scene);
        check(plays==before+1,"one click on suggestion plays exactly once");
        check(searches==full,"suggestion click does not submit a full search");
        check(!(Boolean)call(TOP,"getExpanded",new Class[]{}),"suggestion closes after playback");
        check(call(UI,"getSearchKeyword",new Class[]{}).equals(""),"candidate playback clears topbar input");
        topFocusChecks(scene);
        var action=input.getConfig().get(SemanticsActions.INSTANCE.getSetText()).getAction();
        action.invoke(new AnnotatedString("新关键词",List.of(),List.of()));apply(scene);
        check(!dump(scene).contains("候选1"),"old keyword candidates disappear immediately");
        Thread.sleep(350);settle(scene);
        check(((List<?>)call(TOP,"rows",new Class[]{})).size()==5,"debounced query updates five suggestions");
        input=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getSetText())).findFirst().orElseThrow();
        input.getConfig().get(SemanticsActions.INSTANCE.getRequestFocus()).getAction().invoke();apply(scene);
        before=plays;full=searches;key(scene,java.awt.event.KeyEvent.VK_ENTER);apply(scene);
        if(searches!=full+1||plays!=before)System.out.println("DEBUG searches="+full+"->"+searches+" plays="+before+"->"+plays+" page="+call(UI,"getPage",new Class[]{})+" focus="+dump(scene).contains("Focused : true"));
        check(searches==full+1&&plays==before,"Enter submits exactly one full search without playing");
        check(((Enum<?>)call(UI,"getPage",new Class[]{})).name().equals("SEARCH"),"Enter opens existing embedded search page");
        check(!dump(scene).contains("候选1"),"Enter dismisses candidate panel");
        check(call(UI,"getSearchKeyword",new Class[]{}).equals(""),"Enter search clears topbar input");
        check(search.state().sourceKeyword().equals("新关键词"),"clearing topbar preserves submitted results keyword");
        int stackSize=fixtureNavigator.getNavBackStack().size();
        input=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getSetText())).findFirst().orElseThrow();
        input.getConfig().get(SemanticsActions.INSTANCE.getSetText()).getAction().invoke(new AnnotatedString("按钮关键词",List.of(),List.of()));apply(scene);
        input.getConfig().get(SemanticsActions.INSTANCE.getRequestFocus()).getAction().invoke();apply(scene);
        settle(scene);
        full=searches;before=plays;clickButton(scene,"搜索",16000);
        if(searches!=full+1||plays!=before||!call(UI,"getSearchKeyword",new Class[]{}).equals(""))
            System.out.println("DEBUG button full="+full+"->"+searches+" plays="+before+"->"+plays+" keyword="+call(UI,"getSearchKeyword",new Class[]{})+" focused="+call(TOP,"getFocused",new Class[]{})+" expanded="+call(TOP,"getExpanded",new Class[]{}));
        check(searches==full+1&&plays==before&&call(UI,"getSearchKeyword",new Class[]{}).equals(""),"search button submits once and clears input without playback");
        check(fixtureNavigator.getNavBackStack().size()==stackSize,"repeated searches never stack another configuration route");
        input=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getSetText())).findFirst().orElseThrow();
        action=input.getConfig().get(SemanticsActions.INSTANCE.getSetText()).getAction();
        action.invoke(new AnnotatedString("",List.of(),List.of()));apply(scene);int requests=topSearches;
        Thread.sleep(300);settle(scene);
        check(topSearches==requests&&!(Boolean)call(TOP,"getExpanded",new Class[]{}),"clearing input cancels suggestions and does not send empty query");
        delegated("com.example.netease.nativeui.HostGlobalSearch","enabled",false);apply(scene);
        check(nodes(scene).stream().noneMatch(n->n.getConfig().contains(SemanticsActions.INSTANCE.getSetText())),"disabled search-box preference hides native titlebar field");
        delegated("com.example.netease.nativeui.HostGlobalSearch","enabled",true);apply(scene);
        check(nodes(scene).stream().anyMatch(n->n.getConfig().contains(SemanticsActions.INSTANCE.getSetText())),"search box can be enabled again without restarting host");
        cloudRemovalDialogChecks(scene,width,out);
    }
    static void cloudRemovalDialogChecks(ImageComposeScene scene,int width,Path out)throws Exception {
        String menu="com.example.netease.nativeui.HostCloudSongMenu";
        var selectionType=Class.forName(menu+"$Selection");
        var loadedType=Class.forName(menu+"$Loaded");
        var removalType=Class.forName(menu+"$Removal");
        var target=new Dto.PlaylistBrief(9001,"我的测试歌单","演示账号",3,0,true);
        var selected=selectionType.getConstructor(List.class,long.class).newInstance(SONGS,9001L);
        var account=loadedType.getConstructor(long.class,long.class,List.class,Map.class,String.class)
            .newInstance(1L,7001L,List.of(target),Map.of(),"");
        byte[] background=frame(scene);
        delegated(menu,"removal",removalType.getConstructor(selectionType,Dto.PlaylistBrief.class,loadedType)
            .newInstance(selected,target,account));apply(scene);
        // The actual MultilayerBlur adds an opaque native background. Verify
        // composited dimming, not the old transparent-root fixture's alpha.
        var before=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(background));
        var after=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(frame(scene)));
        int base=before.getRGB(0,0),dimmed=after.getRGB(0,0);
        for(int shift:new int[]{0,8,16})check(Math.abs(((dimmed>>>shift)&255)-((base>>>shift)&255)*.4)<3,
            "embedded cloud dialog dims actual host background channel "+shift);
        modalBounds(scene,width);
        Files.write(out.resolve("CLOUD-REMOVAL-"+width+".png"),frame(scene));
        var material=privateValue(Class.forName("com.example.netease.nativeui.HostGlobalSearch"),"popupMaterial$delegate");
        Object captured=((MutableState<?>)material).getValue();
        for(String getter:List.of("getWallpaper","getPopup")) {
            var m=captured.getClass().getDeclaredMethod(getter);m.setAccessible(true);
            check(m.invoke(captured)==(getter.equals("getPopup")?rootPopup:rootWallpaper),"root overlay uses original host material identity: "+getter);
        }
        check(dump(scene).contains("确定将 3 首歌曲")&&dump(scene).contains("网易云中的该歌单也会同步移除"),
            "cloud confirmation names target, song count and remote removal");
        int clicksBefore=hostClicks;pointer(scene,1,30,600,18000);pointer(scene,2,30,600,18020);apply(scene);
        check(hostClicks==clicksBefore&&call(menu,"getRemoval",new Class[]{})!=null,
            "embedded cloud confirmation blocks underlying host clicks");
        clickButton(scene,"取消",19000);apply(scene);
        check(call(menu,"getRemoval",new Class[]{})==null,"cancel dismisses cloud confirmation without cloud mutation");
        delegated(menu,"message","隔离测试：云端拒绝写入");
        delegated("com.example.netease.nativeui.HostGlobalSearch","enabled",false);apply(scene);
        modalBounds(scene,width);
        check(dump(scene).contains("隔离测试：云端拒绝写入"),"result dialog also uses captured native material with search disabled");
        var config=Class.forName("com.xuncorp.voxzen.util.AppConfig").getField("INSTANCE").get(null);
        var styles=Class.forName("com.xuncorp.voxzen.ui.theme.SpwWindowStyle");
        var lightDark=Class.forName("com.xuncorp.voxzen.ui.theme.LightDarkTheme");
        for(String style:List.of("None","WindowsAcrylic","WindowsMica","WindowsMicaAlt")) {
            config.getClass().getMethod("setSpwWindowStyle",styles).invoke(config,Enum.valueOf((Class)styles,style));
            for(String mode:List.of("Light","Dark")) {
                config.getClass().getMethod("setLightDarkTheme",lightDark).invoke(config,Enum.valueOf((Class)lightDark,mode));
                settle(scene);
                check(dump(scene).contains("隔离测试：云端拒绝写入")&&nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsProperties.INSTANCE.getIsDialog())).count()==1,
                    "root native dialog survives live material change: "+style+" "+mode);
            }
        }
        config.getClass().getMethod("setMultilayerBlurEffect",boolean.class).invoke(config,false);settle(scene);
        check(dump(scene).contains("隔离测试：云端拒绝写入"),"root dialog survives blur being disabled");
        config.getClass().getMethod("setMultilayerBlurEffect",boolean.class).invoke(config,true);settle(scene);
        check(dump(scene).contains("隔离测试：云端拒绝写入"),"root dialog survives blur being restored");
        clickButton(scene,"关闭",20000);apply(scene);
        check(call(menu,"getMessage",new Class[]{}).equals(""),"result dialog can close without a cloud write");
        delegated("com.example.netease.nativeui.HostGlobalSearch","enabled",true);apply(scene);
    }
    static void topFocusChecks(ImageComposeScene scene)throws Exception {
        call(TOP,"collapse",new Class[]{});apply(scene);settle(scene);
        var compact=(androidx.compose.ui.geometry.Rect)call(TOP,"getFieldBounds",new Class[]{});
        var input=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getSetText())).findFirst().orElseThrow();
        input.getConfig().get(SemanticsActions.INSTANCE.getRequestFocus()).getAction().invoke();apply(scene);settle(scene);
        var wide=(androidx.compose.ui.geometry.Rect)call(TOP,"getFieldBounds",new Class[]{});
        check(wide.getWidth()>compact.getWidth(),"focus expands the compact rounded search field");
        int before=hostClicks;
        pointer(scene,1,30,600,13000);pointer(scene,2,30,600,13020);apply(scene);settle(scene);
        check(!(Boolean)call(TOP,"getFocused",new Class[]{}),"clicking outside an empty field clears actual search focus");
        check(hostClicks==before+1,"same outside click reaches host button without an extra click");
        var collapsed=(androidx.compose.ui.geometry.Rect)call(TOP,"getFieldBounds",new Class[]{});
        check(Math.abs(collapsed.getWidth()-compact.getWidth())<1,"outside click animates back to compact width");
    }
    static void saveDialog(ImageComposeScene scene, Path out, int width)throws Exception {
        var behind=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getOnClick()) && n.getConfig().toString().contains("ContentDescription : [返回配置]")).findFirst().orElseThrow().getBoundsInRoot();
        var button=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getOnClick()) && n.getConfig().toString().contains("Text : [存为本地歌单]")).findFirst().orElseThrow();
        button.getConfig().get(SemanticsActions.INSTANCE.getOnClick()).getAction().invoke();
        apply(scene);
        Files.write(out.resolve("SAVE-"+width+".png"),frame(scene));
        embeddedModal(frame(scene),"naming dialog");
        modalBounds(scene,width);
        float bx=behind.getLeft()+behind.getWidth()/2,by=behind.getTop()+behind.getHeight()/2;
        pointer(scene,1,bx,by,4000);pointer(scene,2,bx,by,4020);apply(scene);
        check((Boolean)call("com.example.netease.nativeui.NativeUiPresentation","getLocalSaveOpen",new Class[]{}),"naming dialog blocks pointer clicks on underlying header");
        check(dump(scene).contains("Text : [保存]"),"local naming opens embedded dialog");
        check(!dump(scene).contains("Text : [我喜欢的音乐]"),"naming dialog hides background accessibility");
        Files.writeString(out.resolve("SAVE-focus-"+width+".txt"),dump(scene));
        key(scene,java.awt.event.KeyEvent.VK_ESCAPE);
        Files.writeString(out.resolve("SAVE-after-"+width+".txt"),dump(scene));
        check(!dump(scene).contains("Text : [保存]"),"Esc closes naming dialog");
        check(((Enum<?>)call(UI,"getPage",new Class[]{})).name().equals("PLAYLIST"),"cancel naming keeps playlist page");
    }
    static void themeChecks(ImageComposeScene scene,Object config,Class<?> styles,java.util.concurrent.atomic.AtomicReference<Object> captured,Path out)throws Exception{
        List<String> rows=new ArrayList<>();
        for(String style:List.of("None","WindowsAcrylic","WindowsMica","WindowsMicaAlt")) {
            config.getClass().getMethod("setWallpaperFilePath",String.class).invoke(config,"");
            config.getClass().getMethod("setSpwWindowStyle",styles).invoke(config,Enum.valueOf((Class)styles,style));
            byte[] light=null, lightDialog=null;
            for(boolean isDark:new boolean[]{false,true}) {
                Class<?> lightDark=Class.forName("com.xuncorp.voxzen.ui.theme.LightDarkTheme");
                config.getClass().getMethod("setLightDarkTheme",lightDark).invoke(config,Enum.valueOf((Class)lightDark,isDark?"Dark":"Light"));
                settle(scene);byte[] png=frame(scene);
                check((Boolean)captured.get().getClass().getMethod("getDark").invoke(captured.get())==isDark,"live host theme follows "+style+" dark="+isDark);
                transparent(png,style+" dark="+isDark);
                if(!isDark)light=png;else check(!Arrays.equals(light,png),"actual UI repaints after host light/dark switch "+style);
                String file="THEME-"+style+"-"+(isDark?"Dark":"Light");Files.write(out.resolve(file+".png"),png);
                // The host owns the window background; transparent PNG viewers
                // can display low-alpha white as opaque. Verify and show a
                // second preview composited over the actual host background.
                var pixels=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
                int expected=(int)(packed(captured.get(),"SubBackground")>>>32);
                var row=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsProperties.INSTANCE.getContentDescription())&&n.getConfig().toString().contains("夜曲")).findFirst().orElseThrow().getBoundsInRoot();
                check(pixels.getRGB((int)row.getLeft()+1,(int)row.getTop()+1)==expected,"selected native row uses actual host subBackground including alpha "+style+" dark="+isDark);
                preview(png,out.resolve(file+"-PREVIEW.png"),captured.get());
                rows.add(file+" background="+Long.toHexString(packed(captured.get(),"Background"))+" text="+Long.toHexString(packed(captured.get(),"Text")));
                put("Page",Enum.valueOf((Class)Class.forName("com.example.netease.nativeui.NativePage"),"ACCOUNT"));settle(scene);
                byte[] dialog=frame(scene);embeddedModal(dialog,"account "+style+" dark="+isDark);
                accountBackground(scene,dialog);
                check(dump(scene).contains("演示账号"),"host dialog renders under "+style+" dark="+isDark);
                if(!isDark)lightDialog=dialog;else check(!Arrays.equals(lightDialog,dialog),"host dialog repaints with actual theme "+style);
                Files.write(out.resolve(file+"-DIALOG.png"),dialog);
                put("Page",Enum.valueOf((Class)Class.forName("com.example.netease.nativeui.NativePage"),"SEARCH"));apply(scene);
            }
        }
        // Wallpaper materials are a separate host option, not a plugin palette.
        var wallpaper=out.resolve("fixture-wallpaper.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8,8,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",wallpaper.toFile());
        config.getClass().getMethod("setWallpaperFilePath",String.class).invoke(config,wallpaper.toAbsolutePath().toString());
        Class<?> material=Class.forName("com.moriafly.salt.ui.material.MaterialType");
        for(Object type:material.getEnumConstants()) {
            config.getClass().getMethod("setWallpaperMaterialType",material).invoke(config,type);
            Class<?> lightDark=Class.forName("com.xuncorp.voxzen.ui.theme.LightDarkTheme");
            config.getClass().getMethod("setLightDarkTheme",lightDark).invoke(config,Enum.valueOf((Class)lightDark,"Light"));
            apply(scene);byte[] png=frame(scene);
            transparent(png,"wallpaper "+((Enum<?>)type).name());
            check(dump(scene).contains("搜索歌曲"),"wallpaper material renders native content "+type);
            Files.write(out.resolve("WALLPAPER-"+((Enum<?>)type).name()+".png"),png);
            rows.add("wallpaper="+((Enum<?>)type).name()+" background="+Long.toHexString(packed(captured.get(),"Background")));
        }
        Files.write(out.resolve("theme-matrix.txt"),rows);
        System.out.println("HOST_THEME_FOLLOW_RESULT="+checks+" PASS");
    }
    static void hookChecks(ImageComposeScene scene,MutableState<HookCall> route,boolean engine)throws Exception {
        check(originalCalls>0,"replacement passes other plugin through original renderer");
        check(originalRoute==route.getValue().args.get(0) && originalMask==2,"original route identity and default ViewModel mask preserved");
        check(!dump(scene).contains("打开网易云音乐")&&!dump(scene).contains("搜索歌曲"),"other plugin has no injected entry or page");
        Object own=Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute$ModConfigContent").getConstructor(String.class).newInstance("com.example.netease");
        put("Visible",false);route.setValue(new HookCall(own,null));apply(scene);
        check(originalRoute==own && !dump(scene).contains("打开网易云音乐"),"collapsed own page keeps original configuration without duplicate floating entry");
        int before=originalCalls;put("Page",Enum.valueOf((Class)Class.forName("com.example.netease.nativeui.NativePage"),"SEARCH"));put("Visible",true);apply(scene);
        check(originalCalls==before && dump(scene).contains("搜索歌曲"),"active page replaces original content rather than covering it");
        var close=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getOnClick())&&n.getConfig().toString().contains("ContentDescription : [返回配置]")).findFirst().orElseThrow();
        close.getConfig().get(SemanticsActions.INSTANCE.getOnClick()).getAction().invoke();apply(scene);
        check(originalCalls>before && !dump(scene).contains("打开网易云音乐"),"return configuration restores original content without floating entry");
        Object explicit=new Object();Object other=route.getValue().args.get(0);
        put("Visible",true);route.setValue(new HookCall(Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute$ModConfigContent").getConstructor(String.class).newInstance("another.plugin"),explicit));apply(scene);
        check(engine ? originalViewModel==null && originalMask==2 : originalViewModel==explicit && originalMask==0,
            engine ? "real navigation default ViewModel preserved" : "other plugin explicit ViewModel preserved");
        check(!dump(scene).contains("搜索歌曲"),"switching to other plugin removes the embedded page");
        if(engine) {
            route.setValue(new HookCall(own,null));inject("started",true);apply(scene);
            String[][] entries={{"openSearch","SEARCH","夜曲"},{"openPlaylist","PLAYLIST","我喜欢的音乐"},
                {"openLoginDialog","LOGIN","刷新二维码"},{"currentAccount","ACCOUNT","登录"}};
            for(String[] entry:entries) {
                put("Visible",false);apply(scene);
                com.example.netease.NeteasePlugin.class.getMethod(entry[0]).invoke(null);apply(scene);
                check(((Enum<?>)call(UI,"getPage",new Class[]{})).name().equals(entry[1]),"production entry routes: "+entry[0]);
                check((Boolean)call(UI,"getVisible",new Class[]{}),"production entry opens page: "+entry[0]);
                check(dump(scene).contains(entry[2]),"transformed host navigation displays: "+entry[0]);
            }
            check(HostHookEngineProbe.replacements()>0,"installed transformer actually dispatches replacement for mask=2");
            modalRoutes(scene);
            com.example.netease.NeteasePlugin.openLoginDialog();apply(scene);
            check(((LoginController)call(SESSION,"login",new Class[]{})).isOpen(),"login controller active before host navigation");
            Object foreignRoute=Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute$ModConfigContent").getConstructor(String.class).newInstance("another.plugin");
            route.setValue(new HookCall(foreignRoute,null));apply(scene);
            check(!((LoginController)call(SESSION,"login",new Class[]{})).isOpen(),"leaving plugin route closes login controller");
            check(!(Boolean)call("com.example.netease.nativeui.NativeUiPresentation","modalOpen",new Class[]{}),"leaving plugin route dismisses embedded modal");
            check(!dump(scene).contains("刷新二维码"),"other plugin does not retain login dialog");
        }
        route.setValue(new HookCall(own,null));inject("started",true);apply(scene);
        com.example.netease.NeteasePlugin.openSearch();apply(scene);
        Object foreign=Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute$ModConfigContent").getConstructor(String.class).newInstance("another.plugin");
        route.setValue(new HookCall(foreign,null));apply(scene);
        int originalBefore=originalCalls;route.setValue(new HookCall(own,null));apply(scene);
        check(!(Boolean)call(UI,"getVisible",new Class[]{}),"leaving search clears its route presentation state");
        check(originalCalls>originalBefore&&!dump(scene).contains("搜索歌曲"),"reentering plugin opens configuration immediately with no extra search/back layer");
        System.out.println((engine?"HOST_UI_ENGINE_RESULT=":"HOST_UI_REPLACEMENT_RESULT=")+checks+" PASS");
    }
    static void modalRoutes(ImageComposeScene scene)throws Exception {
        String presentation="com.example.netease.nativeui.NativeUiPresentation";
        com.example.netease.NeteasePlugin.openSearch();apply(scene);
        put("SearchKeyword","保留搜索内容");search.selectRow(2);apply(scene);
        var behind=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getOnClick()) && n.getConfig().toString().contains("ContentDescription : [返回配置]")).findFirst().orElseThrow().getBoundsInRoot();
        int requested=searches, before=originalCalls;
        com.example.netease.NeteasePlugin.currentAccount();apply(scene);
        check(((Enum<?>)call(presentation,"backgroundPage",new Class[]{})).name().equals("SEARCH"),"account dialog retains search as background");
        check(originalCalls==before,"account over search does not return to configuration");
        check(nodes(scene).stream().noneMatch(n->n.getConfig().contains(SemanticsActions.INSTANCE.getSetText())),"modal hides background input accessibility");
        float bx=behind.getLeft()+behind.getWidth()/2,by=behind.getTop()+behind.getHeight()/2;
        pointer(scene,1,bx,by,6000);pointer(scene,2,bx,by,6020);apply(scene);
        check((Boolean)call(presentation,"modalOpen",new Class[]{}),"account dialog blocks pointer clicks on underlying header");
        key(scene,java.awt.event.KeyEvent.VK_ESCAPE);
        check(((Enum<?>)call(UI,"getPage",new Class[]{})).name().equals("SEARCH"),"Esc account dialog returns to search");
        check(call(UI,"getSearchKeyword",new Class[]{}).equals("保留搜索内容") && search.state().selectedRow()==2 && searches==requested,"dialog close preserves search text, selection and results without new request");
        com.example.netease.NeteasePlugin.openLoginDialog();apply(scene);
        check((Boolean)call(presentation,"modalOpen",new Class[]{}),"login opens as modal over existing page");
        key(scene,java.awt.event.KeyEvent.VK_ESCAPE);
        check(((Enum<?>)call(UI,"getPage",new Class[]{})).name().equals("SEARCH"),"Esc login returns to original search");
        check(!((LoginController)call(SESSION,"login",new Class[]{})).isOpen(),"closing login stops login controller");
        call(presentation,"showConfiguration",new Class[]{});apply(scene);before=originalCalls;
        com.example.netease.NeteasePlugin.currentAccount();apply(scene);
        check(originalCalls>before,"opening account from configuration keeps original configuration composed");
        key(scene,java.awt.event.KeyEvent.VK_ESCAPE);
        check(!(Boolean)call(UI,"getVisible",new Class[]{}),"closing configuration dialog restores original configuration");
        Class<?> pg=Class.forName("com.example.netease.nativeui.NativePage");int pages=0;
        for(Object p:pg.getEnumConstants())if((Boolean)call(presentation,"isPage",new Class[]{pg},p))pages++;
        check(pages==2,"exactly search and playlist are independent pages");
    }
    public static void main(String[] args)throws Exception {
        startArtwork();
        String page=args[0];int width=Integer.parseInt(args[1]);Path out=Path.of(args[2]);Files.createDirectories(out);
        call("com.example.netease.nativeui.HostSaltComponents","validate",new Class[]{});checks++;
        fixtures(page.equals("THEME")?"SEARCH":Set.of("HOOK","ENGINE","TOPSEARCH").contains(page)?"HOME":page);
        if(page.equals("TOPSEARCH"))topFixture(width);
        String style=args.length>3?args[3]:"None";
        boolean dark=args.length>4 && args[4].equals("Dark");
        MutableState<HookCall> routeState=SnapshotStateKt.mutableStateOf(null,SnapshotStateKt.structuralEqualityPolicy());
        var captured=new java.util.concurrent.atomic.AtomicReference<Object>();
        Class<?> themeBridge=Class.forName("com.example.netease.nativeui.HostSaltTheme");
        Object themeBridgeInstance=themeBridge.getField("INSTANCE").get(null);
        Method readTheme=themeBridge.getMethod("read",Composer.class,int.class);
        Class<?> styleClass=Class.forName("com.xuncorp.voxzen.ui.theme.SpwWindowStyle");
        Object config=Class.forName("com.xuncorp.voxzen.util.AppConfig").getField("INSTANCE").get(null);
        config.getClass().getMethod("setSpwWindowStyle",styleClass).invoke(config,Enum.valueOf((Class)styleClass,style));
        Class<?> lightDark=Class.forName("com.xuncorp.voxzen.ui.theme.LightDarkTheme");
        config.getClass().getMethod("setLightDarkTheme",lightDark).invoke(config,Enum.valueOf((Class)lightDark,dark?"Dark":"Light"));
        Method theme=Class.forName("com.xuncorp.voxzen.ui.theme.\u0528").getMethod("\u037f",boolean.class,Function2.class,Composer.class,int.class,int.class);
        Method panel=Class.forName("com.example.netease.nativeui.NativeUiPageKt").getMethod("NativeUiPanel",Composer.class,int.class);
        Object hook=null;Method hookDraw=null;
        if(page.equals("TOPSEARCH")) {
            Class<?> h=Class.forName("com.example.netease.nativeui.HostGlobalSearch$RootHook");
            Field hi=h.getDeclaredField("INSTANCE");hi.setAccessible(true);hook=hi.get(null);
            hookDraw=h.getDeclaredMethod("Content",com.xuncorp.spw.workshop.api.hook.UiHookCall.class,Composer.class,int.class);hookDraw.setAccessible(true);
            routeState.setValue(new HookCall(false,null));
        }
        if(page.equals("HOOK")||page.equals("ENGINE")) {
            Class<?> h=Class.forName("com.example.netease.nativeui.HostUiAdapter$PanelHook");
            Field hi=h.getDeclaredField("INSTANCE");hi.setAccessible(true);hook=hi.get(null);
            hookDraw=h.getDeclaredMethod("Content",com.xuncorp.spw.workshop.api.hook.UiHookCall.class,Composer.class,int.class);hookDraw.setAccessible(true);
            call("com.example.netease.nativeui.HostConfigContent","bind",new Class[]{Method.class},HostSaltRenderProbe.class.getMethod("originalFixture",Object.class,Object.class,Composer.class,int.class,int.class));
            Object other=Class.forName("com.xuncorp.voxzen.ui.navigation.ScreenRoute$ModConfigContent").getConstructor(String.class).newInstance("another.plugin");
            routeState.setValue(new HookCall(other,null));
        }
        final Object hookInstance=hook;final Method hookMethod=hookDraw;
        final boolean engine=page.equals("ENGINE");
        final Method navigation=engine?HostHookEngineProbe.prepare(out.resolve("bridge"),true,(values,c,n)->{
            try{hookMethod.invoke(hookInstance,new HookCall(values[0],values[1]),c,0);}
            catch(Exception e){throw new RuntimeException(e);}return Unit.INSTANCE;
        }):null;
        Function2<Composer,Integer,Unit> content=(c,n)->{try{
            captured.set(readTheme.invoke(themeBridgeInstance,c,0));
            if(navigation!=null)navigation.invoke(null,routeState.getValue().args.get(0),c,0);
            else if(hookMethod!=null)hookMethod.invoke(hookInstance,routeState.getValue(),c,0);else panel.invoke(null,c,0);
        }catch(Exception e){throw new RuntimeException(e);}return Unit.INSTANCE;};
        var locals=hostContext(page.equals("TOPSEARCH"));
        if(page.equals("TOPSEARCH"))field("com.example.netease.nativeui.HostGlobalSearch","navigator",new java.lang.ref.WeakReference<>(fixtureNavigator));
        Function2<Composer,Integer,Unit> themed=(c,n)->{try{theme.invoke(null,false,content,c,0,1);}catch(Exception e){throw new RuntimeException(e);}return Unit.INSTANCE;};
        Function2<Composer,Integer,Unit> hosted=(c,n)->{CompositionLocalKt.CompositionLocalProvider(locals,themed,c,0);return Unit.INSTANCE;};
        var scene=new ImageComposeScene(width,640,DensityKt.Density(1f,1f),LayoutDirection.Ltr,renderDispatcher,hosted);
        try{
            apply(scene);
            if(page.equals("TOPSEARCH")) {
                var input=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getSetText())).findFirst().orElseThrow();
                input.getConfig().get(SemanticsActions.INSTANCE.getRequestFocus()).getAction().invoke();apply(scene);
                settle(scene);
                nativeArtwork(scene,"候选1");
                preview(frame(scene),out.resolve("TOPSEARCH-"+width+"-PREVIEW.png"),captured.get());
                topChecks(scene,width,out);
                System.out.println("HOST_SALT_RENDER_RESULT="+checks+" PASS TOPSEARCH width="+width);return;
            }
            if(page.equals("THEME")){
                themeChecks(scene,config,styleClass,captured,out);return;
            }
            if(page.equals("HOOK")||engine){
                hookChecks(scene,routeState,engine);return;
            }
            if(Set.of("LOGIN","SMS","RISK","ACCOUNT","SYNC").contains(page)) { embeddedModal(frame(scene),page); modalBounds(scene,width); }
            else transparent(frame(scene),"embedded "+page);
            Files.write(out.resolve(page+"-"+width+".png"),frame(scene));
            if(Set.of("ACCOUNT","LOGIN","SMS").contains(page)) preview(frame(scene),out.resolve(page+"-"+width+"-PREVIEW.png"),captured.get());
            String tree=dump(scene);Files.writeString(out.resolve(page+"-"+width+".txt"),tree);
            String expected=switch(page){case "SEARCH"->"夜曲";case "PLAYLIST"->"我喜欢的音乐";case "LOGIN"->"刷新二维码";case "SMS"->"获取验证码";case "RISK"->"我已完成，重试";case "ACCOUNT"->"演示账号";case "SYNC"->"立即同步";default->"打开网易云音乐";};
            if(page.equals("HOME")) check(!tree.contains("打开网易云音乐"),"configuration has no duplicate floating entry");
            else check(tree.contains(expected),"page semantics contains "+expected);
            if(page.equals("SEARCH")||page.equals("PLAYLIST")) {
                check(nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getOnClick())&&n.getConfig().toString().contains("ContentDescription : [返回配置]")).count()==1,"page has exactly one return action");
            }
            if(page.equals("SMS")) {
                var fields=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getSetText())).toList();
                check(fields.size()==2,"SMS has both phone and verification inputs");
                var phone=fields.stream().filter(n->n.getConfig().toString().contains("11 位手机号")).findFirst().orElseThrow().getBoundsInRoot();
                var code=fields.stream().filter(n->n.getConfig().toString().contains("4-6 位数字")).findFirst().orElseThrow().getBoundsInRoot();
                var send=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getOnClick())&&n.getConfig().toString().contains("Text : [获取验证码]")).findFirst().orElseThrow().getBoundsInRoot();
                check(send.getLeft()>phone.getRight()&&Math.abs(send.getTop()+send.getHeight()/2-phone.getTop()-phone.getHeight()/2)<1,"SMS send button is to right of phone on same row");
                check(Math.abs(code.getLeft()+code.getWidth()/2-width/2f)<1,"SMS form is centered");
                check(!tree.contains("选择登录方式后开始"),"SMS omits idle instructions");
            }
            check(!tree.contains("NaN"),"finite layout");
            for(var node:nodes(scene)) if(node.getConfig().contains(SemanticsActions.INSTANCE.getSetText())) {
                var r=node.getBoundsInRoot();
                check(r.getWidth()>=120f && r.getLeft()>=0 && r.getRight()<=width+0.5f,"native input fits viewport");
            }
            if(page.equals("ACCOUNT")) { accountBackground(scene,frame(scene));check(!tree.contains("状态说明"),"account omits redundant status description"); }
            if(page.equals("SEARCH")){searchInteractions(scene);preview(frame(scene),out.resolve("SEARCH-"+width+"-PREVIEW.png"),captured.get());rowInteractions(scene);menuChecks(scene,out);}
            if(page.equals("PLAYLIST"))saveDialog(scene,out,width);
            if(page.equals("LOGIN")||page.equals("SMS")){
                if(page.equals("LOGIN")) {
                    check(tree.contains("使用网易云音乐APP扫码登录")&&!tree.contains("打开网易云音乐 App"),"QR shows only requested scan instruction");
                    var refresh=nodes(scene).stream().filter(n->n.getConfig().contains(SemanticsActions.INSTANCE.getOnClick())&&n.getConfig().toString().contains("Text : [刷新二维码]")).findFirst().orElseThrow().getBoundsInRoot();
                    var qr=nodes(scene).stream().filter(n->n.getConfig().toString().contains("ContentDescription : [登录二维码]")).findFirst().orElseThrow().getBoundsInRoot();
                    check(Math.abs(refresh.getWidth()-qr.getWidth()-16)<1,"QR refresh button matches white QR frame width");
                    clickButton(scene,"短信登录",8000);
                    check(((LoginController)call(SESSION,"login",new Class[]{})).state().mode()==LoginController.Mode.SMS,"pointer click inside dialog reaches SMS mode button");
                    clickButton(scene,"扫码登录",9000);
                    check(((LoginController)call(SESSION,"login",new Class[]{})).state().mode()==LoginController.Mode.QR,"pointer click inside dialog reaches QR mode button");
                    clickButton(scene,"关闭",10000);
                    check(!(Boolean)call(UI,"getVisible",new Class[]{}),"native dialog close button receives real pointer click");
                    inject("started",true);
                    check(com.example.netease.nativeui.NativeUiHost.openPage("login"),"closed dialog reopens through production entry");apply(scene);
                }
                if(page.equals("SMS")) {
                    Set<Integer> reached=new HashSet<>();StringBuilder focusTrace=new StringBuilder();
                    for(int i=0;i<12;i++){
                        key(scene,java.awt.event.KeyEvent.VK_TAB);
                        focusTrace.append("Tab "+i+"\n");
                        for(var node:nodes(scene)) if(Boolean.TRUE.equals(node.getConfig().getOrElseNullable(SemanticsProperties.INSTANCE.getFocused(),()->false))) focusTrace.append(node.getConfig().toString()+"\n");
                        for(var node:nodes(scene)) if(node.getConfig().contains(SemanticsActions.INSTANCE.getSetText()) && Boolean.TRUE.equals(node.getConfig().getOrElseNullable(SemanticsProperties.INSTANCE.getFocused(),()->false)))reached.add(node.getId());
                    }
                    Files.writeString(out.resolve("SMS-focus-"+width+".txt"),focusTrace.toString());
                    check(reached.size()==2,"Tab reaches both embedded SMS inputs");
                    reached.clear();
                    for(int i=0;i<12;i++){
                        key(scene,java.awt.event.KeyEvent.VK_TAB,java.awt.event.InputEvent.SHIFT_DOWN_MASK);
                        for(var node:nodes(scene)) if(node.getConfig().contains(SemanticsActions.INSTANCE.getSetText()) && Boolean.TRUE.equals(node.getConfig().getOrElseNullable(SemanticsProperties.INSTANCE.getFocused(),()->false)))reached.add(node.getId());
                    }
                    check(reached.size()==2,"Shift+Tab reaches both native SMS inputs");
                }
                key(scene,java.awt.event.KeyEvent.VK_ESCAPE);
                check(((Enum<?>)call(UI,"getPage",new Class[]{})).name().equals("HOME"),"Esc returns from embedded login page");
                check(!((LoginController)call(SESSION,"login",new Class[]{})).isOpen(),"Esc closes login controller");
            }
            System.out.println("HOST_SALT_RENDER_RESULT="+checks+" PASS "+page+" width="+width);
        }finally{scene.close();call(SESSION,"stop",new Class[]{});call(TOP,"stop",new Class[]{});artworkServer.stop(0);}
    }
}


