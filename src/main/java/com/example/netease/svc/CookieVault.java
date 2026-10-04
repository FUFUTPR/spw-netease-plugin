package com.example.netease.svc;

import com.example.netease.core.PluginLog;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 凭据保险柜：cookie 串的 AES-256-GCM 落盘（docs/00 §6.7 冻结签名）。
 *
 * <p><b>文件格式</b>（{@code data\account.json}，纯文本，UTF-8）：</p>
 * <pre>
 *   v1:&lt;Base64( iv(12B) || 密文+tag(16B) )&gt;
 * </pre>
 * <ul>
 *   <li>密钥文件 {@code data\account.key} = 32 字节 CSPRNG（{@link SecureRandom}），首次 {@link #init} 生成，之后复用；</li>
 *   <li>AAD = {@code com.example.netease/account/v1}（绑定版本，换版本 = 换 AAD = 旧密文解不开）；</li>
 *   <li><b>每次 {@link #save} 都重新生成随机 IV</b>（GCM 下 IV 重用会直接毁掉机密性）；</li>
 *   <li>{@link #load()} 对「文件缺失 / 前缀不对 / Base64 坏 / 长度不够 / tag 校验失败 / 换了机器」一律返回 {@code null}，
 *       <b>绝不抛异常、绝不删文件</b>（docs/14 §6 降级：坏文件保留便于排障）。</li>
 * </ul>
 *
 * <p><b>线程</b>：本类只在 {@code netease-account} 线程上被 {@link AccountService} 调用；方法本身 {@code synchronized}，
 * 烟测线程调用也安全。内部不做任何网络请求、不碰宿主 API。</p>
 *
 * <p><b>兼容性保护</b>：{@code account.json} 同时也是宿主配置页托管过的文件名（docs/00 §6.9 的 {@code auto_login}）。
 * 若 {@link #save} 发现现有文件不是本类写的 {@code v1:} 格式，会先把它备份成 {@code account.hostconfig.bak}
 * 再覆盖，避免静默吞掉宿主配置。</p>
 */
public final class CookieVault {

    /** 凭据文件名（{@code data\account.json}）。 */
    public static final String FILE_NAME = "account.json";
    /** 密钥文件名（{@code data\account.key}）。 */
    public static final String KEY_NAME = "account.key";
    /** 覆盖非保险柜内容前的备份名。 */
    public static final String BACKUP_NAME = "account.hostconfig.bak";
    /** 损坏密钥的备份名。 */
    public static final String KEY_BACKUP_NAME = "account.key.bak";

    /** 密文格式版本前缀。 */
    private static final String PREFIX = "v1:";
    /** 绑定的附加认证数据（版本/插件绑定）。 */
    private static final String AAD = "com.example.netease/account/v1";

    private static final int KEY_BYTES = 32;   // AES-256
    private static final int IV_BYTES = 12;    // GCM 推荐 96 bit
    private static final int TAG_BITS = 128;   // GCM tag 长度
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();

    private static volatile Path dir;
    private static volatile Path vaultFile;
    private static volatile Path keyFile;
    private static volatile byte[] key;

    private CookieVault() {
    }

    /**
     * 初始化数据目录：建/复用 {@code account.key}（32B CSPRNG）。
     *
     * <p>密钥文件损坏（长度不对）时会把它备份为 {@code account.key.bak} 并重新生成，
     * 否则用户会陷入「永远保存失败、永远登录不上」的死角。</p>
     */
    public static void init(Path dataDir) {
        synchronized (LOCK) {
            dir = dataDir;
            vaultFile = (dataDir == null) ? null : dataDir.resolve(FILE_NAME);
            keyFile = (dataDir == null) ? null : dataDir.resolve(KEY_NAME);
            key = null;
            if (dataDir == null) {
                PluginLog.w("account", "CookieVault.init(null)：凭据将无法落盘");
                return;
            }
            try {
                Files.createDirectories(dataDir);
            } catch (Throwable t) {
                PluginLog.w("account", "创建凭据目录失败：" + dataDir + " :: " + t);
            }
            key = loadOrCreateKey();
        }
    }

    /** 是否已经有一个「看起来是保险柜文件」的凭据文件（不校验能否解密）。 */
    public static boolean exists() {
        Path f = vaultFile;
        try {
            return f != null && Files.isRegularFile(f) && Files.size(f) > 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 保存明文 cookie 串：每次全新随机 IV；{@code null}/空白 = {@link #clear()}。 */
    public static void save(String plaintextCookie) {
        if (plaintextCookie == null || plaintextCookie.isBlank()) {
            clear();
            return;
        }
        synchronized (LOCK) {
            Path f = vaultFile;
            byte[] k = key;
            if (f == null || k == null) {
                PluginLog.w("account", "CookieVault 未 init，凭据未保存");
                return;
            }
            try {
                byte[] iv = new byte[IV_BYTES];
                RANDOM.nextBytes(iv);
                Cipher cipher = Cipher.getInstance(TRANSFORM);
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(TAG_BITS, iv));
                cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
                byte[] ct = cipher.doFinal(plaintextCookie.getBytes(StandardCharsets.UTF_8));

                byte[] blob = new byte[iv.length + ct.length];
                System.arraycopy(iv, 0, blob, 0, iv.length);
                System.arraycopy(ct, 0, blob, iv.length, ct.length);
                String text = PREFIX + Base64.getEncoder().encodeToString(blob);

                Files.createDirectories(f.getParent());
                backupHostConfigIfAny(f);
                writeAtomically(f, text);
                PluginLog.i("account", "凭据已加密保存：" + f.getFileName() + "（密文 " + text.length()
                        + " 字符 / 明文长度 " + plaintextCookie.length() + "，内容不落日志）");
            } catch (Throwable t) {
                // 加密/写盘失败绝不能向上抛：UI 线程或账号线程都要活着
                PluginLog.e("account", "凭据保存失败（明文不入日志）", t);
            }
        }
    }

    /** 读取并解密；任何异常（缺失/坏 base64/长度不对/篡改/tag 校验失败）→ {@code null}，绝不抛。 */
    public static String load() {
        Path f = vaultFile;
        if (f == null) {
            return null;
        }
        try {
            if (!Files.isRegularFile(f)) {
                return null;
            }
            String text = Files.readString(f, StandardCharsets.UTF_8).trim();
            if (!text.startsWith(PREFIX)) {
                PluginLog.w("account", "凭据文件不是 v1 格式（可能被宿主配置覆盖），按未登录处理，不删除");
                return null;
            }
            byte[] blob = Base64.getDecoder().decode(text.substring(PREFIX.length()).trim());
            if (blob.length < IV_BYTES + 16) {
                PluginLog.w("account", "凭据文件长度不足（" + blob.length + "B），按未登录处理，不删除");
                return null;
            }
            byte[] iv = Arrays.copyOfRange(blob, 0, IV_BYTES);
            byte[] ct = Arrays.copyOfRange(blob, IV_BYTES, blob.length);
            byte[] k = key;
            if (k == null) {
                PluginLog.w("account", "CookieVault 未 init，无法解密凭据");
                return null;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(ct);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // AEADBadTagException / IllegalArgumentException(base64) / 换机器换密钥 …… 一律 null
            PluginLog.w("account", "凭据解密失败（视为未登录，文件保留）：" + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 删 {@code account.json}（保留 {@code account.key}）。 */
    public static void clear() {
        synchronized (LOCK) {
            Path f = vaultFile;
            if (f == null) {
                return;
            }
            try {
                boolean deleted = Files.deleteIfExists(f);
                PluginLog.i("account", deleted ? "凭据文件已删除（密钥保留）" : "凭据文件本就不存在");
            } catch (Throwable t) {
                PluginLog.w("account", "凭据文件删除失败：" + t);
            }
        }
    }

    /** {@code account.json} 的完整路径（未 init 时返回 {@code null}）。 */
    public static Path file() {
        return vaultFile;
    }

    /** 数据目录（未 init 时返回 {@code null}）。 */
    public static Path dir() {
        return dir;
    }

    // ------------------------------------------------------------------ 内部

    /** 读密钥文件；不存在则生成；长度不对则备份后重建。任何失败都不抛。 */
    private static byte[] loadOrCreateKey() {
        Path kf = keyFile;
        try {
            if (kf != null && Files.isRegularFile(kf)) {
                byte[] raw = Files.readAllBytes(kf);
                if (raw.length == KEY_BYTES) {
                    return raw;
                }
                Path bak = kf.resolveSibling(KEY_BACKUP_NAME);
                try {
                    Files.move(kf, bak, StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable ignored) {
                    // 备份失败也要继续重建，否则永远无法保存
                }
                PluginLog.w("account", "密钥文件长度异常（" + raw.length + "B ≠ " + KEY_BYTES
                        + "B），已备份为 " + KEY_BACKUP_NAME + " 并重建（旧凭据将无法解密）");
            }
            byte[] fresh = new byte[KEY_BYTES];
            RANDOM.nextBytes(fresh);
            if (kf != null) {
                Files.write(kf, fresh);
            }
            PluginLog.i("account", "已生成新的凭据密钥：" + KEY_NAME + "（" + KEY_BYTES + "B CSPRNG）");
            return fresh;
        } catch (Throwable t) {
            PluginLog.e("account", "凭据密钥初始化失败（本次进程内保存将被跳过）", t);
            return null;
        }
    }

    /** 若目标文件已存在且不是 {@code v1:} 格式，先备份（避免吞掉宿主写的 account.json 配置）。 */
    private static void backupHostConfigIfAny(Path f) {
        try {
            if (!Files.isRegularFile(f)) {
                return;
            }
            String head = Files.readString(f, StandardCharsets.UTF_8).trim();
            if (head.startsWith(PREFIX)) {
                return;
            }
            Path bak = f.resolveSibling(BACKUP_NAME);
            Files.copy(f, bak, StandardCopyOption.REPLACE_EXISTING);
            PluginLog.w("account", "account.json 内含非保险柜内容（疑似宿主配置），已备份为 " + BACKUP_NAME + " 后再写入凭据");
        } catch (Throwable ignored) {
            // 备份失败不阻断保存
        }
    }

    /** 临时文件 + 原子替换写盘（避免半截文件被 load 读到）。 */
    private static void writeAtomically(Path f, String text) throws Exception {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
