package com.example.netease.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Resolves the user's Downloads location without creating or chmod'ing it.
 * Call on a worker: Windows may need a bounded registry query. */
public final class UserDownloads {
    private UserDownloads() { }
    @FunctionalInterface interface Command { String run(List<String> arguments)throws Exception; }
    private static final Pattern XDG=Pattern.compile("^\\s*XDG_DOWNLOAD_DIR\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*(?:#.*)?$");
    public static Path fallback() { return Path.of(System.getProperty("user.home",".")).toAbsolutePath().resolve("Downloads").normalize(); }
    public static Path resolve() {
        return resolve(System.getProperty("os.name",""),Path.of(System.getProperty("user.home",".")),System.getenv(),UserDownloads::capture);
    }
    static Path resolve(String os,Path home,Map<String,String> env,Command command) {
        home=home.toAbsolutePath().normalize();
        try {
            if(os.toLowerCase(Locale.ROOT).startsWith("windows")) {
                String script="[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);"+
                    "$p=[Microsoft.Win32.Registry]::GetValue('HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders',"+
                    "'{374DE290-123F-4565-9164-39C4925E467B}',$null);"+
                    "if($p){[Console]::Write([Environment]::ExpandEnvironmentVariables([string]$p))}";
                String executable=Path.of(env.getOrDefault("SystemRoot","C:\\Windows"),"System32","WindowsPowerShell","v1.0","powershell.exe").toString();
                String value=command.run(List.of(executable,"-NoLogo","-NoProfile","-NonInteractive","-Command",script)).strip();
                if(!value.isEmpty()) {Path path=Path.of(value);if(path.isAbsolute())return path.normalize();}
            } else if(os.toLowerCase(Locale.ROOT).contains("linux")) {
                Path config=home.resolve(".config");String configured=env.get("XDG_CONFIG_HOME");
                if(configured!=null&&!configured.isBlank()&&Path.of(configured).isAbsolute())config=Path.of(configured);
                Path file=config.resolve("user-dirs.dirs");
                if(Files.isRegularFile(file)&&Files.size(file)<=65536) {
                    Path selected=null;
                    for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)) {
                        var match=XDG.matcher(line);if(!match.matches())continue;
                        String expanded=expandHome(match.group(1),home.toString());
                        if(expanded!=null) {Path path=Path.of(expanded);if(path.isAbsolute())selected=path.normalize();}
                    }
                    if(selected!=null)return selected;
                }
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(Exception unavailable){PluginLog.w("updates","读取系统下载目录失败，使用用户 Downloads："+unavailable.getClass().getSimpleName());}
        return home.resolve("Downloads");
    }
    private static String expandHome(String value,String home) {
        var out=new StringBuilder();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c=='\\'&&i+1<value.length()&&"\\\"$`".indexOf(value.charAt(i+1))>=0)out.append(value.charAt(++i));
            else if(c=='$') {
                if(value.startsWith("${HOME}",i)){out.append(home);i+=6;}
                else if(value.startsWith("$HOME",i)&&(i+5==value.length()||!Character.isLetterOrDigit(value.charAt(i+5))&&value.charAt(i+5)!='_')){out.append(home);i+=4;}
                else return null;
            } else if(c=='`')return null;
            else out.append(c);
        }
        return out.toString();
    }
    private static String capture(List<String> arguments)throws Exception {
        Process process=new ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try(var input=process.getInputStream();var output=new ByteArrayOutputStream()) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(process.isAlive()) {
                int ready=input.available();if(ready>0)output.write(input.readNBytes(Math.min(ready,131073-output.size())));
                if(output.size()>131072)throw new IOException("下载目录响应过长");
                if(System.nanoTime()>deadline)throw new IOException("下载目录查询超时");
                Thread.sleep(10);
            }
            output.write(input.readNBytes(131073-output.size()));
            if(process.exitValue()!=0||output.size()>131072)throw new IOException("下载目录查询失败");
            return output.toString(StandardCharsets.UTF_8);
        } finally {if(process.isAlive())process.destroyForcibly();}
    }
}
