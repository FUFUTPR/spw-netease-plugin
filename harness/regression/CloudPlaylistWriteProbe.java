package com.example.netease.net;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;

public class CloudPlaylistWriteProbe {
    static int checks;
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);checks++;System.out.println("PASS "+m);}
    public static void main(String[] args)throws Exception {
        var add=NeteaseApi.playlistMutationPayload(123,456,false);
        check(add.get("op").equals("add")&&add.get("pid").equals(456L)&&add.get("trackIds").equals("[123]"),"playlist mutation contains exactly one selected song and target");
        check(!add.containsKey("like"),"playlist add does not change favorite state");
        var remove=NeteaseApi.playlistMutationPayload(123,456,false,true);
        check(remove.get("op").equals("del")&&remove.get("pid").equals(456L)&&remove.get("trackIds").equals("[123]"),"removal targets selected cloud membership only");
        check(NeteaseApi.playlistMutationPayload(123,0,true,true).get("like").equals(false),"favorite removal uses like=false");
        var like=NeteaseApi.playlistMutationPayload(123,0,true);
        check(like.get("trackId").equals(123L)&&like.get("like").equals(true)&&!like.containsKey("pid"),"favorite payload uses trackId and like=true");
        NeteaseApi.requirePlaylistMutationSuccess("{\"code\":200}");check(true,"explicit success code accepted");
        for(String response:List.of("", "{}", "{\"code\":512}","{\"code\":301}","{\"code\":-462}")) {
            boolean refused=false;try{NeteaseApi.requirePlaylistMutationSuccess(response);}catch(Exception e){refused=true;}
            check(refused,"missing/error/verification response never reports cloud success");
        }
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mutation",ex->{calls.incrementAndGet();ex.getRequestBody().readAllBytes();ex.sendResponseHeaders(503,-1);ex.close();});server.start();
        try {boolean failed=false;try{Http.postFormOnce("http://127.0.0.1:"+server.getAddress().getPort()+"/mutation",Map.of("fixture","1"));}catch(java.io.IOException e){failed=true;}
            check(failed&&calls.get()==1,"uncertain HTTP 503 mutation is sent once, never retried");
        }finally{server.stop(0);}
        System.out.println("CLOUD_PLAYLIST_WRITE_RESULT="+checks+" PASS");
    }
}
