package com.example.netease.svc;

import com.example.netease.core.PrivateFiles;
import com.example.netease.core.PluginLog;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** A private, atomic recovery copy outside the host-managed plugin-data folder.
 * Contains only the existing encrypted vault + its key; no plaintext credentials or API requests.
 */
final class VaultRecovery {
    private static final int MAGIC = 0x4e435631;
    private VaultRecovery() { }

    static Path file(Path data) {
        if (data == null) return null;
        Path parent = data.toAbsolutePath().normalize().getParent();
        if (parent != null && parent.getFileName().toString().equals("data") && parent.getParent() != null
                && data.getFileName().toString().equals("com.example.netease"))
            return parent.getParent().resolve("private-data").resolve("com.example.netease").resolve("account.vault");
        return data.resolve(".private-recovery").resolve("account.vault");
    }

    static boolean valid(byte[] key, byte[] vault) {
        if (key.length != 32) return false;
        try {
            String text = new String(vault, StandardCharsets.UTF_8).trim();
            if (!text.startsWith("v1:")) return false;
            byte[] bytes = java.util.Base64.getDecoder().decode(text.substring(3).trim());
            if (bytes.length < 28) return false;
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key,"AES"), new GCMParameterSpec(128,java.util.Arrays.copyOf(bytes,12)));
            cipher.updateAAD("com.example.netease/account/v1".getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(java.util.Arrays.copyOfRange(bytes,12,bytes.length));
            boolean valid = plain.length > 0;
            java.util.Arrays.fill(plain,(byte)0);
            return valid;
        } catch (Exception invalid) { return false; }
    }

    static void checkpoint(Path data) {
        try {
            Path keyFile=data.resolve(CookieVault.KEY_NAME), vaultFile=data.resolve(CookieVault.FILE_NAME);
            if (!Files.isRegularFile(keyFile) || !Files.isRegularFile(vaultFile)) return;
            byte[] key=Files.readAllBytes(keyFile), vault=Files.readAllBytes(vaultFile);
            if (!valid(key,vault)) return;
            var bytes=new ByteArrayOutputStream();
            try(var output=new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);output.write(key);output.writeInt(vault.length);output.write(vault);
            }
            Path file=file(data);PrivateFiles.directory(file.getParent());PrivateFiles.atomicWrite(file,bytes.toByteArray());
            Files.deleteIfExists(file.resolveSibling("logged-out"));
            java.util.Arrays.fill(key,(byte)0);
        } catch (Exception error) { PluginLog.w("account","凭据恢复副本写入失败，原凭据保留："+error.getClass().getSimpleName()); }
    }

    static void restoreMissing(Path data) {
        if (data == null) return;
        boolean hasVault=Files.exists(data.resolve(CookieVault.FILE_NAME));
        boolean hasKey=Files.exists(data.resolve(CookieVault.KEY_NAME));
        if (hasVault && hasKey) return;
        Path file=file(data);
        if (Files.exists(file.resolveSibling("logged-out"))) return;
        if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) return;
        try(var input=new DataInputStream(Files.newInputStream(file))) {
            if(input.readInt()!=MAGIC)return;
            byte[] key=input.readNBytes(32);int length=input.readInt();
            if(length<3 || length>1_048_576)return;
            byte[] vault=input.readNBytes(length);
            if(vault.length!=length || input.read()!=-1 || !valid(key,vault))return;
            if(hasVault && !java.util.Arrays.equals(Files.readAllBytes(data.resolve(CookieVault.FILE_NAME)),vault))return;
            Files.createDirectories(data);
            PrivateFiles.atomicWrite(data.resolve(CookieVault.KEY_NAME),key);
            PrivateFiles.atomicWrite(data.resolve(CookieVault.FILE_NAME),vault);
            java.util.Arrays.fill(key,(byte)0);
            PluginLog.i("account","已恢复导入前保存的本机加密凭据（不输出凭据内容）");
        } catch(Exception error) { PluginLog.w("account","凭据恢复副本不可用，文件保留："+error.getClass().getSimpleName()); }
    }

    static void clear(Path data) throws IOException {
        Path file=file(data);
        if(file!=null && Files.isDirectory(file.getParent())) {
            // A locked backup must not resurrect a deliberately logged-out account.
            PrivateFiles.atomicWrite(file.resolveSibling("logged-out"),new byte[]{1});
            try { Files.deleteIfExists(file); }
            catch(IOException locked) { PluginLog.w("account","恢复副本已撤销，旧密文等待文件锁释放后清理"); }
        }
    }
}
