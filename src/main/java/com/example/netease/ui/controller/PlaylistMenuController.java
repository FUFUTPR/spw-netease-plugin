package com.example.netease.ui.controller;

import com.example.netease.net.Dto;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.*;

/** Lazy, account-scoped cloud menu. No host reflection or I/O on the UI thread. */
public final class PlaylistMenuController implements AutoCloseable {
    public record Identity(long epoch, long uid) {}
    public record State(Dto.Song song, List<Dto.PlaylistBrief> playlists, boolean loading, boolean busy, String message) {}
    public interface Cloud {
        List<Dto.PlaylistBrief> playlists(long uid) throws Exception;
        void add(Identity account, long song, long playlist, boolean like, Consumer<String> done);
    }
    private final Supplier<Identity> identity;
    private final Cloud cloud;
    private final Executor executor;
    private final Consumer<State> listener;
    private final Runnable afterWrite;
    private final LongSupplier clock;
    private State state = new State(null, List.of(), false, false, "");
    private Identity opened, cachedFor;
    private List<Dto.PlaylistBrief> cache = List.of();
    private long cacheAt, generation;
    private boolean closed;
    public PlaylistMenuController(Supplier<Identity> identity, Cloud cloud, Executor executor,
                                  Consumer<State> listener, Runnable afterWrite, LongSupplier clock) {
        this.identity=identity; this.cloud=cloud; this.executor=executor;
        this.listener=listener; this.afterWrite=afterWrite; this.clock=clock;
    }
    public synchronized State state() { return state; }
    public void open(Dto.Song song) {
        final Identity account; final long token;
        synchronized(this) {
            if (closed) return;
            account = identity.get();
            if (state.busy() && account.equals(opened)) return;
            opened = account; token = ++generation;
            if (account.uid() <= 0) { update(new State(song,List.of(),false,false,"请先登录网易云账号")); return; }
            if (account.equals(cachedFor) && clock.getAsLong()-cacheAt < 60_000) {
                update(new State(song,cache,false,false,"")); return;
            }
            update(new State(song,List.of(),true,false,""));
        }
        try { executor.execute(() -> {
            try {
                var owned = new LinkedHashMap<Long,Dto.PlaylistBrief>();
                for (var playlist : cloud.playlists(account.uid()))
                    if (playlist.mine() && playlist.id()>0) owned.putIfAbsent(playlist.id(),playlist);
                synchronized(this) {
                    if (!valid(token,account)) return;
                    cache=List.copyOf(owned.values()); cachedFor=account; cacheAt=clock.getAsLong();
                    update(new State(song,cache,false,false,""));
                }
            } catch (Exception error) { synchronized(this) {
                if (valid(token,account)) update(new State(song,List.of(),false,false,"歌单读取失败，请重新打开菜单重试"));
            }}
        }); } catch (RuntimeException rejected) { synchronized(this) {
            if(valid(token,account)) update(new State(song,List.of(),false,false,"歌单服务不可用"));
        }}
    }
    public void add(long playlistId, boolean like) {
        final Identity account; final Dto.Song song; final long token;
        synchronized(this) {
            if (closed || state.busy() || state.song()==null) return;
            account=opened;
            if (account==null || account.uid()<=0 || !account.equals(identity.get())) {
                update(new State(state.song(),List.of(),false,false,"账号已变化，请重新打开菜单")); return;
            }
            if (!like && state.playlists().stream().noneMatch(p -> p.mine() && p.id()==playlistId)) return;
            song=state.song(); token=++generation;
            update(new State(song,state.playlists(),false,true,"正在添加："+song.name()));
        }
        try { cloud.add(account,song.id(),playlistId,like,error -> {
            boolean success=error==null || error.isBlank();
            synchronized(this) {
                if (closed || token!=generation || !state.busy()) return;
                if (!account.equals(identity.get())) {
                    update(new State(song,List.of(),false,false,"账号已变化，请重新打开菜单")); return;
                }
                update(new State(song,state.playlists(),false,false,success ? "已添加到网易云歌单："+song.name() : error));
            }
            if (success) afterWrite.run();
        }); } catch(RuntimeException error) { synchronized(this) {
            if (valid(token,account)) update(new State(song,state.playlists(),false,false,"添加未完成，请重新打开菜单"));
        }}
    }
    private boolean valid(long token,Identity account) { return !closed && token==generation && account.equals(identity.get()); }
    private void update(State next) { state=next; listener.accept(next); }
    @Override public synchronized void close() { closed=true; generation++; cache=List.of(); }
}
