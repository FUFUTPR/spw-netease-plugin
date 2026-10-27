package com.example.netease.svc;

import com.example.netease.core.Json;
import com.example.netease.core.PluginLog;
import com.example.netease.core.PrivateFiles;
import com.example.netease.core.UserDownloads;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/** Anonymous, download-only updater. No host API, NetEase HTTP client or installer. */
public final class PluginUpdater implements AutoCloseable {
    public static final String REPOSITORY = "https://github.com/FUFUTPR/spw-netease-plugin";
    public static final String API = "https://api.github.com/repos/FUFUTPR/spw-netease-plugin/releases/latest";
    public static final String ID = "com.example.netease";
    public enum Phase { IDLE, CHECKING, AVAILABLE, PROBING, DOWNLOADING, VERIFYING, COMPLETE, CANCELLED, FAILED }
    public record Release(String version, String name, URI url, long size, String sha256) { }
    public record State(Phase phase, Release release, long downloaded, long total, Path file, String message, String source) {
        public boolean busy() { return phase==Phase.PROBING || phase==Phase.DOWNLOADING || phase==Phase.VERIFYING; }
    }
    public record Source(String prefix, String label) { }
    public record CheckResult(State state,String error) { }
    private record Probe(Source source, double speed) { }
    private static final List<Source> SOURCES = List.of(new Source("", "GitHub 官方"),
        new Source("https://gh-proxy.com/", "GH-Proxy"), new Source("https://ghfast.top/", "GHFast"));
    private static final long MAX_PACKAGE = 128L*1024*1024;
    private static volatile PluginUpdater global;
    private static volatile Consumer<State> observer;
    private static final AtomicLong GLOBAL_GENERATION = new AtomicLong();
    private static final State EMPTY = new State(Phase.IDLE,null,0,0,null,"","");
    private final Path root, preferences;
    private volatile Path downloads;
    private boolean downloadsResolved;
    private final String currentVersion;
    private final URI endpoint;
    private final List<Source> sources;
    private final boolean fixture;
    private final ScheduledExecutorService worker;
    private final ExecutorService probes;
    private final ExecutorService connectionCloser;
    private final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
    private final Set<HttpURLConnection> closing = ConcurrentHashMap.newKeySet();
    private final Set<Future<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicLong generation = new AtomicLong();
    private final Consumer<State> listener;
    private volatile State state = EMPTY;
    private volatile boolean closed, notifications = true;
    private CompletableFuture<CheckResult> manualCheck;

    public PluginUpdater(Path data, String currentVersion, URI endpoint, List<Source> sources, Consumer<State> listener) {
        this(data,currentVersion,endpoint,sources,listener,null);
    }
    public PluginUpdater(Path data, String currentVersion, URI endpoint, List<Source> sources, Consumer<State> listener, Path downloadDirectory) {
        this.root=data.toAbsolutePath().normalize().resolve("updates");
        this.preferences=root.resolve("preferences.json");
        this.downloads=downloadDirectory==null?UserDownloads.fallback():downloadDirectory.toAbsolutePath().normalize();
        this.downloadsResolved=downloadDirectory!=null;
        this.currentVersion=currentVersion;this.endpoint=endpoint;this.sources=List.copyOf(sources);this.listener=listener;
        this.fixture=loopback(endpoint);
        worker=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"netease-update-worker"));
        probes=Executors.newFixedThreadPool(3,r->daemon(r,"netease-update-probe"));
        connectionCloser=Executors.newFixedThreadPool(3,r->daemon(r,"netease-update-close"));
    }
    private static Thread daemon(Runnable r,String name) { Thread t=new Thread(r,name);t.setDaemon(true);return t; }
    public static synchronized void startGlobal(Path data,String version) {
        stopGlobal();
        long lifecycle=GLOBAL_GENERATION.incrementAndGet();
        PluginUpdater engine=new PluginUpdater(data,version,URI.create(API),SOURCES,next->{
            if(GLOBAL_GENERATION.get()!=lifecycle)return;
            Consumer<State> target=observer;if(target!=null)try{target.accept(next);}catch(Throwable ignored){}
        });
        global=engine;
        engine.worker.schedule(()->engine.check(false),4,TimeUnit.SECONDS);
    }
    public static synchronized void stopGlobal() { GLOBAL_GENERATION.incrementAndGet();PluginUpdater old=global;global=null;if(old!=null)old.close(); }
    public static PluginUpdater global() { return global; }
    public static State snapshot() { PluginUpdater e=global;return e==null?EMPTY:e.state; }
    public static void observe(Consumer<State> target) { observer=target;if(target!=null)target.accept(snapshot()); }
    public State state() { return state; }
    public Path downloadDirectory() { return downloads; }
    public synchronized void invalidateCompleted(State expected,String message) {
        if(closed||state!=expected||state.phase()!=Phase.COMPLETE)return;
        long token=generation.incrementAndGet();
        publish(token,new State(Phase.FAILED,state.release(),0,state.total(),null,message,""));
    }
    private void resolveDownloads() { if(!downloadsResolved){downloads=UserDownloads.resolve();downloadsResolved=true;} }
    public boolean notificationsEnabled() { return notifications; }
    public void checkNow() { if(!closed)try{worker.execute(()->check(true));}catch(RejectedExecutionException stopped){} }
    /** Manual checks bypass startup reminder opt-out, without interrupting a download. */
    public synchronized CompletableFuture<CheckResult> checkManually() {
        if(closed)return CompletableFuture.completedFuture(new CheckResult(state,"检查已取消"));
        if(state.busy())return CompletableFuture.completedFuture(new CheckResult(state,""));
        if(manualCheck!=null)return manualCheck;
        var result=new CompletableFuture<CheckResult>();manualCheck=result;
        try{worker.execute(()->{
            CheckResult checked=performCheck(true);
            synchronized(this){if(manualCheck==result)manualCheck=null;}
            result.complete(checked);
        });}catch(RejectedExecutionException stopped){manualCheck=null;result.complete(new CheckResult(state,"检查已取消"));}
        return result;
    }
    public CompletableFuture<Boolean> setNotifications(boolean value) {
        CompletableFuture<Boolean> result=new CompletableFuture<>();
        if(closed){result.complete(false);return result;}
        try { worker.execute(()->{
            try { PrivateFiles.directory(root);PrivateFiles.atomicWrite(preferences,
                Json.stringify(Map.of("notifications",value)).getBytes(StandardCharsets.UTF_8));
                notifications=value;result.complete(true);
            }catch(Exception error){PluginLog.w("updates","保存更新提醒设置失败："+error.getClass().getSimpleName());result.complete(false);}
        }); }catch(RejectedExecutionException stopped){result.complete(false);}
        return result;
    }
    private void check(boolean manual) {
        performCheck(manual);
    }
    private CheckResult performCheck(boolean manual) {
        if(closed)return new CheckResult(state,"检查已取消");
        if(state.busy())return new CheckResult(state,"");
        long token=generation.incrementAndGet();
        try {
            try {
                if(Files.isRegularFile(preferences) && Files.size(preferences)<8192)
                    notifications=!Boolean.FALSE.equals(Json.object(Files.readString(preferences)).get("notifications"));
            }catch(Exception invalid){PluginLog.w("updates","更新提醒设置不可读，使用默认提醒");}
            if(!manual&&!notifications){publish(token,EMPTY);return new CheckResult(state,"");}
            numbers(currentVersion.replaceFirst("^[vV]",""));
            publish(token,new State(Phase.CHECKING,null,0,0,null,"正在检查最新版本",""));
            Release release=null;boolean fetched=false;
            List<Source> metadata=fixture?List.of(new Source("","fixture")):sources;
            for(Source source:metadata) {
                ensure(token);
                try { release=parseRelease(readMetadata(URI.create(source.prefix()+endpoint),token),fixture);fetched=true;break; }
                catch(Exception failure){PluginLog.d("updates","发布信息线路暂不可用："+source.label());}
            }
            if(!fetched)throw new IOException("无法获取 GitHub 发布信息");
            if(release==null || compareVersions(release.version(),currentVersion)<=0){publish(token,EMPTY);return new CheckResult(state,"");}
            resolveDownloads();ensure(token);
            Path existing=existingDownload(release);
            if(existing!=null){publish(token,new State(Phase.COMPLETE,release,release.size(),release.size(),existing,"下载完毕","本地文件"));return new CheckResult(state,"");}
            publish(token,new State(Phase.AVAILABLE,release,0,release.size(),null,"网易云插件已有新版本",""));
            return new CheckResult(state,"");
        }catch(Exception error){if(valid(token)){PluginLog.w("updates","检查更新未完成："+error.getClass().getSimpleName());publish(token,EMPTY);}
            return new CheckResult(state,closed?"检查已取消":"检查更新失败，请检查网络后重试");}
    }
    @SuppressWarnings("unchecked")
    public static Release parseRelease(String payload,boolean fixture) throws IOException {
        Map<String,Object> json=Json.object(payload);
        if(Boolean.TRUE.equals(json.get("draft")) || Boolean.TRUE.equals(json.get("prerelease")))return null;
        String version=String.valueOf(json.getOrDefault("tag_name","")).replaceFirst("^[vV]","");
        numbers(version);String name="plugin-"+ID+"-"+version+".spmod";
        Object assets=json.get("assets");if(!(assets instanceof List<?> list))return null;
        for(Object item:list)if(item instanceof Map<?,?> asset && name.equals(asset.get("name"))) {
            if(!"uploaded".equals(asset.get("state")))continue;
            long size=asset.get("size") instanceof Number n?n.longValue():0;
            if(size<=0 || size>MAX_PACKAGE)throw new IOException("发布包大小不合法");
            URI url=URI.create(String.valueOf(asset.get("browser_download_url")));
            if(url.getUserInfo()!=null || url.getFragment()!=null || url.getQuery()!=null)throw new IOException("发布地址不合法");
            String expected="/FUFUTPR/spw-netease-plugin/releases/download/";
            boolean official="https".equals(url.getScheme())&&"github.com".equals(url.getHost())
                &&url.getPath().equals(expected+json.get("tag_name")+"/"+name);
            if(!official&&!(fixture&&loopback(url)))throw new IOException("发布包不属于本仓库");
            String digest=Objects.toString(asset.get("digest"),"");
            if(!digest.isBlank()&&!digest.matches("sha256:[a-fA-F0-9]{64}"))throw new IOException("发布摘要不合法");
            return new Release(version,name,url,size,digest.replaceFirst("^sha256:","").toLowerCase(Locale.ROOT));
        }
        return null;
    }
    private static long[] numbers(String version) throws IOException {
        if(version==null || !version.matches("[0-9]+(?:\\.[0-9]+){1,3}"))throw new IOException("版本号不合法");
        try{return Arrays.stream(version.split("\\.")).mapToLong(Long::parseLong).toArray();}
        catch(NumberFormatException invalid){throw new IOException("版本号超出范围");}
    }
    public static int compareVersions(String a,String b) throws IOException {
        long[] x=numbers(a.replaceFirst("^[vV]","")),y=numbers(b.replaceFirst("^[vV]",""));
        for(int i=0;i<Math.max(x.length,y.length);i++){int c=Long.compare(i<x.length?x[i]:0,i<y.length?y[i]:0);if(c!=0)return c;}return 0;
    }
    public synchronized void download() {
        if(closed || state.busy() || state.release()==null || state.phase()==Phase.COMPLETE)return;
        Release release=state.release();long token=generation.incrementAndGet();
        publish(token,new State(Phase.PROBING,release,0,release.size(),null,"正在下载新版本，请耐心等待",""));
        try{worker.execute(()->download(release,token));}catch(RejectedExecutionException stopped){}
    }
    public synchronized void cancel() {
        if(closed || !state.busy())return;
        long token=generation.incrementAndGet();
        pending.forEach(f->f.cancel(true));closeConnectionsAsync();
        publish(token,new State(Phase.CANCELLED,state.release(),0,state.total(),null,"已取消下载",""));
    }
    private void download(Release release,long token) {
        Path temporary=null;
        try {
            resolveDownloads();ensure(token);
            PrivateFiles.directory(root);Files.createDirectories(downloads);
            CompletionService<Probe> completion=new ExecutorCompletionService<>(probes);
            List<Future<Probe>> jobs=new ArrayList<>();
            for(Source source:sources){Future<Probe> f=completion.submit(()->probe(source,release,token));jobs.add(f);pending.add(f);}
            List<Probe> ranked=new ArrayList<>();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
            for(int i=0;i<jobs.size();i++) {
                ensure(token);long remaining=until-System.nanoTime();if(remaining<=0)break;
                Future<Probe> f=completion.poll(remaining,TimeUnit.NANOSECONDS);if(f==null)break;
                try { Probe p=f.get();if(p!=null)ranked.add(p); }catch(ExecutionException ignored){}
            }
            jobs.forEach(f->{f.cancel(true);pending.remove(f);});
            connections.forEach(HttpURLConnection::disconnect);ensure(token);
            ranked.sort(Comparator.comparingDouble(Probe::speed).reversed());
            if(ranked.isEmpty())throw new IOException("下载线路暂不可用，请稍后重试");
            Exception last=null;
            for(Probe candidate:ranked) {
                ensure(token);
                temporary=PrivateFiles.temporary(downloads,"update-");
                try {
                    transfer(candidate.source(),release,temporary,token);ensure(token);
                    publish(token,new State(Phase.VERIFYING,release,release.size(),release.size(),null,"正在校验下载文件",candidate.source().label()));
                    verify(temporary,release);ensure(token);
                    synchronized(this) {
                        ensure(token);
                        Path target=publishDownload(temporary,release.name());
                        temporary=null;
                        publish(token,new State(Phase.COMPLETE,release,release.size(),release.size(),target,"下载完毕",candidate.source().label()));
                    }
                    return;
                }catch(Exception failure){ensure(token);last=failure;Files.deleteIfExists(temporary);temporary=null;
                    PluginLog.w("updates","下载线路失败，切换下一条："+candidate.source().label()+" / "+failure.getClass().getSimpleName());}
            }
            throw new IOException("所有下载线路均未成功，请重试",last);
        }catch(Exception error){if(valid(token))publish(token,new State(Phase.FAILED,release,0,release.size(),null,"下载未完成，请重试", ""));}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    private Path publishDownload(Path temporary,String name)throws IOException {
        // The user Downloads folder is shared: never replace an existing file.
        for(int suffix=0;suffix<1000;suffix++) {
            String filename=suffix==0?name:name.substring(0,name.length()-6)+" ("+suffix+").spmod";
            Path target=downloads.resolve(filename);
            try{Files.move(temporary,target);return target;}
            catch(FileAlreadyExistsException collision){/* Try the next user-visible name. */}
        }
        throw new IOException("下载目录同名文件过多");
    }
    private Path existingDownload(Release release) {
        Path original=downloads.resolve(release.name());
        if(verifiedDownload(original,release))return original;
        if(!Files.isDirectory(downloads))return null;
        String stem=release.name().substring(0,release.name().length()-6);
        var alternate=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(stem)+" \\([1-9][0-9]{0,2}\\)\\.spmod");
        try(var files=Files.newDirectoryStream(downloads,stem+"*.spmod")) {
            for(Path file:files)if(alternate.matcher(file.getFileName().toString()).matches()&&verifiedDownload(file,release))return file;
        }catch(IOException unavailable){PluginLog.d("updates","已有下载文件暂不可读，等待用户下载");}
        return null;
    }
    private static boolean verifiedDownload(Path file,Release release) {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))return false;
        try{verify(file,release);return true;}catch(Exception invalid){return false;}
    }
    private Probe probe(Source source,Release release,long token) {
        long started=System.nanoTime();HttpURLConnection connection=null;
        try {
            connection=open(URI.create(source.prefix()+release.url()),token);connection.setRequestProperty("Range","bytes=0-65535");
            int code=connection.getResponseCode();if(code!=200&&code!=206)return null;
            try(InputStream input=connection.getInputStream()) {
                byte[] prefix=input.readNBytes(4);if(prefix.length<4||prefix[0]!='P'||prefix[1]!='K')return null;
                byte[] buffer=new byte[8192];int total=4;
                while(total<65536){ensure(token);int n=input.read(buffer,0,Math.min(buffer.length,65536-total));if(n<0)break;
                    total+=n;if(System.nanoTime()-started>TimeUnit.SECONDS.toNanos(6))break;}
                return total>=4?new Probe(source,total/Math.max(.001,(System.nanoTime()-started)/1e9)):null;
            }
        }catch(Exception ignored){return null;}finally{disconnect(connection);}
    }
    private void transfer(Source source,Release release,Path target,long token) throws Exception {
        HttpURLConnection connection=open(URI.create(source.prefix()+release.url()),token);
        long received=0,lastUpdate=0,started=System.nanoTime();
        publish(token,new State(Phase.DOWNLOADING,release,0,release.size(),null,"正在下载新版本，请耐心等待",source.label()));
        try {
            if(connection.getResponseCode()!=200)throw new IOException("下载响应不完整");
            try(InputStream input=connection.getInputStream();OutputStream output=Files.newOutputStream(target)) {
                byte[] bytes=new byte[32768];int n;
                while((n=input.read(bytes))!=-1) {
                    ensure(token);received+=n;if(received>release.size())throw new IOException("包大小超过发布信息");
                    output.write(bytes,0,n);long now=System.nanoTime();
                    if(now-started>TimeUnit.MINUTES.toNanos(10))throw new IOException("下载超时");
                    if(now-lastUpdate>TimeUnit.MILLISECONDS.toNanos(100)){lastUpdate=now;
                        publish(token,new State(Phase.DOWNLOADING,release,received,release.size(),null,"正在下载新版本，请耐心等待",source.label()));}
                }
            }
            if(received!=release.size())throw new IOException("下载文件不完整");
        }finally{disconnect(connection);}
    }
    private String readMetadata(URI uri,long token) throws IOException {
        HttpURLConnection c=open(uri,token);
        try {
            c.setRequestProperty("Accept","application/vnd.github+json");
            if(c.getResponseCode()!=200)throw new IOException("发布接口不可用");
            try(InputStream in=c.getInputStream()){byte[] bytes=in.readNBytes(1024*1024+1);if(bytes.length>1024*1024)throw new IOException("发布信息过大");return new String(bytes,StandardCharsets.UTF_8);}
        }finally{disconnect(c);}
    }
    private HttpURLConnection open(URI uri,long token) throws IOException {
        ensure(token);
        if(!"https".equals(uri.getScheme())&&!(fixture&&loopback(uri)))throw new IOException("更新仅允许 HTTPS");
        HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();
        c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent","Salt-NetEase-Update/"+currentVersion);
        c.setRequestProperty("Accept-Encoding","identity");
        c.setRequestProperty("Cookie","");
        c.setAuthenticator(new Authenticator(){@Override protected PasswordAuthentication getPasswordAuthentication(){return null;}});
        connections.add(c);if(!valid(token)){disconnect(c);throw new IOException("更新任务已取消");}return c;
    }
    private void disconnect(HttpURLConnection c){if(c!=null){connections.remove(c);c.disconnect();}}
    private void closeConnectionsAsync() {
        // disconnect() may wait for HttpURLConnection's blocked-read monitor.
        // Never hold the UI/cancel caller there. Snapshot only this generation's
        // current connections so a quick retry cannot lose its new connection.
        for(HttpURLConnection connection:List.copyOf(connections))if(closing.add(connection)) {
            try { connectionCloser.execute(()->{
                try{disconnect(connection);}finally{closing.remove(connection);}
            }); }catch(RejectedExecutionException stopped){closing.remove(connection);}
        }
    }
    private static boolean loopback(URI uri) {return "http".equals(uri.getScheme())&&("127.0.0.1".equals(uri.getHost())||"localhost".equals(uri.getHost()));}
    public static void verify(Path file,Release release) throws Exception {
        if(Files.size(file)!=release.size())throw new IOException("发布包大小不符");
        if(!release.sha256().isBlank()) {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}
            if(!HexFormat.of().formatHex(hash.digest()).equalsIgnoreCase(release.sha256()))throw new IOException("发布包 SHA-256 不符");
        }
        try(ZipFile zip=new ZipFile(file.toFile())) {
            if(zip.size()>20000)throw new IOException("发布包条目异常");
            Set<String> names=new HashSet<>();long unpacked=0;
            for(var entries=zip.entries();entries.hasMoreElements();) {
                var item=entries.nextElement();String name=item.getName();
                if(name.startsWith("/")||name.contains("\\")||name.contains(":")||Arrays.asList(name.split("/")).contains("..")||!names.add(name))throw new IOException("发布包路径异常");
                unpacked+=Math.max(0,item.getSize());if(unpacked>512L*1024*1024)throw new IOException("发布包解压大小异常");
            }
            var entry=zip.getEntry("META-INF/MANIFEST.MF");
            if(entry==null||entry.getSize()>65536||zip.getEntry("classes/com/example/netease/NeteasePlugin.class")==null)throw new IOException("不是网易云插件包");
            try(InputStream in=zip.getInputStream(entry)) {
                byte[] bytes=in.readNBytes(65537);if(bytes.length>65536)throw new IOException("清单过大");
                var attributes=new Manifest(new ByteArrayInputStream(bytes)).getMainAttributes();
                if(!ID.equals(attributes.getValue("Plugin-Id"))||!release.version().equals(attributes.getValue("Plugin-Version")))throw new IOException("发布包身份或版本不符");
            }
        }
    }
    private boolean valid(long token){return !closed&&generation.get()==token&&!Thread.currentThread().isInterrupted();}
    private void ensure(long token)throws IOException {if(!valid(token))throw new IOException("更新任务已取消");}
    private synchronized void publish(long token,State next){if(!valid(token))return;state=next;if(listener!=null)try{listener.accept(next);}catch(Throwable ignored){}}
    @Override public void close() {
        synchronized(this){if(closed)return;closed=true;generation.incrementAndGet();
            if(manualCheck!=null){manualCheck.complete(new CheckResult(state,"检查已取消"));manualCheck=null;}}
        pending.forEach(f->f.cancel(true));closeConnectionsAsync();
        worker.shutdownNow();probes.shutdownNow();connectionCloser.shutdown();
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(600);
        try{for(ExecutorService executor:List.of(worker,probes,connectionCloser)){
            long remaining=deadline-System.nanoTime();if(remaining>0)executor.awaitTermination(remaining,TimeUnit.NANOSECONDS);
        }}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
    public static String bytes(long value) {
        if(value<1024)return value==0?"0KB":value+"B";
        if(value<1024*1024)return String.format(Locale.ROOT,"%.0fKB",value/1024.0);
        return String.format(Locale.ROOT,"%.2fMB",value/(1024.0*1024));
    }
}
