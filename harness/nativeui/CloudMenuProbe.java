import java.lang.reflect.*;
import java.util.*;
import kotlin.Unit;
import kotlin.jvm.functions.*;
import com.example.netease.net.Dto;

/** Actual host DSL + actual singleton labels. Does not initialize host repositories or send requests. */
public final class CloudMenuProbe {
    static int checks;
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    static Object menu;
    static Class<?> menuClass,scope;
    static Object call(String name,Object... args)throws Exception {
        Method m=Arrays.stream(menuClass.getDeclaredMethods()).filter(x->!x.getName().contains("$lambda$")&&(x.getName().equals(name)||x.getName().startsWith(name+"$"))&&x.getParameterCount()==args.length).findFirst().orElseThrow();
        m.setAccessible(true);return m.invoke(menu,args);
    }
    static final class Captured implements Function1<Object,Unit> {
        final Set<?> tracks;final Object properties;
        Captured(Set<?> tracks,Object properties){this.tracks=tracks;this.properties=properties;}
        public Unit invoke(Object scope){return Unit.INSTANCE;}
    }
    record Entry(Object action,Object label,boolean submenu) {}
    public static void main(String[] args)throws Exception {
        menuClass=Class.forName("com.example.netease.nativeui.HostCloudSongMenu");menu=menuClass.getField("INSTANCE").get(null);
        String owner=(String)call("popupCaller");
        for(String caller:List.of(owner.endsWith("\u0996")?"androidx.compose.ui.\u042f":"androidx.compose.ui.\u014b","com.xuncorp.voxzen.ui.player.mini.\u052d")) {
            try(var input=CloudMenuProbe.class.getResourceAsStream("/"+caller.replace('.','/')+".class")) {
                check(input!=null&&GlobalSearchHookProbe.count(input.readAllBytes(),owner.replace('.','/'),"\u037f",null)>0,
                    "actual native song-menu call site verified: "+caller);
            }
        }
        Class<?> abi=Class.forName("com.example.netease.nativeui.HostUiAbi");Object abiObj=abi.getField("INSTANCE").get(null);
        scope=(Class<?>)abi.getMethod("type",String.class).invoke(abiObj,"androidx.compose.ui.\u09a8");
        Class<?> props=(Class<?>)abi.getMethod("type",String.class).invoke(abiObj,"androidx.compose.ui.\u0712");
        Object property=props.getConstructor(String.class,boolean.class,boolean.class,int.class).newInstance("netease-pl-9001",true,true,0);
        Class<?> components=Class.forName("com.example.netease.nativeui.HostSaltComponents");Object component=components.getField("INSTANCE").get(null);
        Object song=components.getMethod("displayTrack",Dto.Song.class,int.class,String.class).invoke(component,new Dto.Song(1001,"cloud","artist","album",180000,0,true,""),0,null);
        Object selected=call("selection",new Captured(Set.of(song),property));
        check(selected!=null,"native captured track set and playlist properties resolve");
        check(selected.getClass().getMethod("getPlaylist").invoke(selected).equals(9001L),"playlist removal retains actual context playlist ID");
        var idField=song.getClass().getDeclaredField("id");idField.setAccessible(true);idField.set(song,"local-fixture");
        check(call("selection",new Captured(Set.of(song),property))==null,"local songs bypass cloud menu decoration");idField.set(song,"netease-1001");
        boolean legacy=scope.getName().endsWith("\u09a8");
        Class<?> singletons=Class.forName(legacy?"androidx.compose.ui.\u0217":"androidx.compose.ui.\u0712");
        var labels=new HashMap<Integer,Object>();
        for(Method method:singletons.getDeclaredMethods()) if(Modifier.isStatic(method.getModifiers())&&method.getParameterCount()==0&&method.getReturnType()==Function2.class) {
            Object label=method.invoke(null);Object key=call("labelKey",label);if(key instanceof Integer i)labels.put(i,label);
        }
        for(int key:new int[]{1419648159,-1468264589,-1687823975,-1734620327,-581413720,-967620052,1247155673})
            check(labels.containsKey(key),"native semantic label verified: "+key);
        var entries=new ArrayList<Entry>();
        Object target=Proxy.newProxyInstance(scope.getClassLoader(),new Class[]{scope},(p,m,a)->{
            if(a!=null&&a.length==3)entries.add(new Entry(a[0],a[2],m.getParameterTypes()[0]==Function1.class));return null;
        });
        Method item=Arrays.stream(scope.getMethods()).filter(m->m.getName().equals("\u037f")&&m.getParameterCount()==3&&m.getParameterTypes()[0]==Function0.class).findFirst().orElseThrow();
        Function0<Unit> safe=()->Unit.INSTANCE, unsafe=()->{throw new AssertionError("native local mutation retained");};
        Function1<Object,Unit> original=t->{try{
            for(int key:new int[]{1419648159,-1468264589,-1687823975,-1734620327,-581413720,-967620052})item.invoke(t,unsafe,true,labels.get(key));
            item.invoke(t,safe,true,labels.get(1247155673));return Unit.INSTANCE;
        }catch(Exception e){throw new RuntimeException(e);}};
        Class<?> loaded=Class.forName(menuClass.getName()+"$Loaded");
        Object account=loaded.getConstructor(long.class,long.class,List.class,Map.class,String.class).newInstance(1L,7001L,
            List.of(new Dto.PlaylistBrief(9001,"current","owner",1,0,true),new Dto.PlaylistBrief(9002,"target","owner",0,0,true)),Map.of(1001L,List.of(9001L)),"");
        int[] dismisses={0};Function0<Unit> dismiss=()->{dismisses[0]++;return Unit.INSTANCE;};
        call("decorate",original,target,selected,account,dismiss);
        check(entries.stream().noneMatch(e->e.action==unsafe),"file operations and all native local-only playlist mutations are absent");
        check(entries.stream().filter(e->e.action==safe).count()==1,"native selected queue action is retained unchanged");
        check(entries.size()==4&&entries.stream().filter(Entry::submenu).count()==1,"cloud adds, target submenu and confirmed removal replace local writes");
        ((Function0<?>)entries.getLast().action).invoke();
        check(dismisses[0]==1&&call("getRemoval")!=null,"remove first dismisses native popup and opens embedded confirmation");
        call("dismissRemoval");check(call("getRemoval")==null,"cancel closes confirmation without calling cloud service");
        check((Boolean)call("suppress",1605270770),"dynamic native file-manager action is suppressed");
        System.out.println("CLOUD_NATIVE_MENU_RESULT="+checks+" PASS");
    }
}
