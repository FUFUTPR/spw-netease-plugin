package com.example.netease.net;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real search enrichment, without an external account or API request. */
public class SearchArtworkMergeProbe {
 static int checks;
 static void check(boolean b,String label){if(!b)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 static Dto.Song song(long id,String image){return new Dto.Song(id,"Original "+id,"Artist","Album",123000,1,false,image);}
 public static void main(String[] args) {
  var calls=new AtomicInteger();var rows=List.of(song(128001,""),song(128002,"https://example.invalid/existing"),song(128003,""),song(128001,""));
  var result=NeteaseApi.searchArtwork(rows,ids->{calls.incrementAndGet();check(ids.equals(List.of(128001L,128003L)),"batch contains only missing distinct IDs");return List.of(song(128003,"https://example.invalid/c"),song(128001,"https://example.invalid/a"),song(999,"https://example.invalid/wrong"));});
  check(calls.get()==1,"missing covers resolve in one detail request");
  check(result.stream().map(Dto.Song::id).toList().equals(rows.stream().map(Dto.Song::id).toList()),"enrichment preserves relevance order and duplicate positions");
  check(result.get(0).picUrl().endsWith("/a")&&result.get(2).picUrl().endsWith("/c"),"out-of-order detail response merges by song ID");
  check(result.get(1).equals(rows.get(1)),"existing image and metadata retained");
  check(result.get(0).name().equals(rows.get(0).name())&&!result.get(0).playable()&&result.get(0).fee()==1&&result.get(0).durationMs()==123000,"artwork does not change title, availability, membership or duration");
  var cached=NeteaseApi.searchArtwork(List.of(song(128001,"")),ids->{throw new AssertionError("repeat must not fetch details");});
  check(cached.getFirst().picUrl().endsWith("/a"),"repeated query reuses artwork metadata");
  var failed=NeteaseApi.searchArtwork(List.of(song(128004,"")),ids->{throw new java.io.IOException("fixture failure");});
  check(failed.equals(List.of(song(128004,""))),"detail failure retains playable search results");
  check(NeteaseApi.searchArtwork(List.of(),ids->{throw new AssertionError("empty query");}).isEmpty(),"empty results do not fetch details");
  System.out.println("SEARCH_ARTWORK_MERGE_RESULT="+checks+" PASS");
 }
}
