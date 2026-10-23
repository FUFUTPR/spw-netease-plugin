import com.example.netease.core.DataPaths;
import com.example.netease.core.PluginLog;
import com.example.netease.host.VoxzenBridge;
import com.example.netease.net.Dto;
import com.example.netease.svc.*;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/** Selected online song -> existing cover pipeline, with local fixture images. */
public final class SearchArtworkProbe {
    static int checks;
    static void check(boolean ok,String label) {if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    static byte[] png(int color)throws Exception {
        var image=new BufferedImage(240,240,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();g.setColor(new java.awt.Color(color));g.fillRect(0,0,240,240);g.dispose();
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    public static void main(String[] args)throws Exception {
        DataPaths.init("com.example.netease");PluginLog.init(DataPaths.logs());CoverStore.init();
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] blue=png(0x2244bb),red=png(0xbb4422);
        server.createContext("/blue",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(200,blue.length);try(var out=exchange.getResponseBody()){out.write(blue);}});
        server.createContext("/red",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(200,red.length);try(var out=exchange.getResponseBody()){out.write(red);}});
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var prepare=VoxzenBridge.class.getDeclaredMethod("prepareOnlineCover",Dto.Song.class);prepare.setAccessible(true);
            long id=9127001L;
            String album="Artwork fixture 0.12.7",artist="Artist A";
            check(CoverStore.refKeyOf(id)==null,"selected search song starts outside synchronized cover map");
            CoverStore.ensure(album,artist,""); // Prior failed search left a no-source placeholder.
            prepare.invoke(null,new Dto.Song(id,"Selected",artist,album,180000,0,true,base+"/blue"));
            String key=CoverStore.refKey(album,artist);
            check(key.equals(CoverStore.refKeyOf(id)),"production online preparation registers song identity");
            check((base+"/blue").equals(CoverStore.picUrlOf(key)),"selected song artwork source retained");
            var lookup=PlaybarCover.class.getDeclaredMethod("refKeyOf",long.class);lookup.setAccessible(true);
            check(key.equals(lookup.invoke(null,id)),"playbar resolves online song without host Track row");
            byte[] bytes=CoverStore.fetchImage(key);
            check(bytes!=null&&bytes.length>0,"existing pipeline returns real artwork bytes");
            int pixel=ImageIO.read(new ByteArrayInputStream(bytes)).getRGB(100,100);
            check((pixel&255)>((pixel>>>16)&255),"placeholder replaced by selected blue artwork");
            prepare.invoke(null,new Dto.Song(id,"Selected",artist,album,180000,0,true,base+"/blue"));
            check(calls.get()==1,"repeated selected-song preparation reuses existing image");
            prepare.invoke(null,new Dto.Song(id+1,"Other","Artist B",album,180000,0,true,base+"/red"));
            check(!CoverStore.refKeyOf(id).equals(CoverStore.refKeyOf(id+1)),"same album title retains distinct artists");
            check(key.equals(lookup.invoke(null,id)),"another song cannot change prior artwork identity");
            int other=ImageIO.read(new ByteArrayInputStream(CoverStore.fetchImage(CoverStore.refKeyOf(id+1)))).getRGB(100,100);
            check(((other>>>16)&255)>(other&255),"other artist receives own red artwork");
            if(java.nio.file.Files.isRegularFile(NativeLibrary.dbPath())) {
                Class.forName("org.sqlite.JDBC");
                try(var db=DriverManager.getConnection("jdbc:sqlite:"+NativeLibrary.dbPath());var query=db.createStatement();var result=query.executeQuery("SELECT count(*) FROM Track WHERE id='netease-9127001'")) {
                    check(result.getLong(1)==0,"artwork preparation does not insert search song into host library");
                }
            }
        } finally {server.stop(0);}
        System.out.println("SEARCH_ARTWORK_RESULT="+checks+" PASS");
    }
}
