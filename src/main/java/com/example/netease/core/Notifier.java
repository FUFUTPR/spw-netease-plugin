package com.example.netease.core;

import com.example.netease.cfg.PluginConfig;
import com.xuncorp.spw.workshop.api.WorkshopApi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户提示（{@code ui.toast}）的唯一出口。
 *
 * <p>要点：</p>
 * <ul>
 *   <li>所有 toast 都投递到 {@link HostBridgeWorker} 执行 —— 宿主 API 只能在那条线程上碰。</li>
 *   <li>同一文案 3 秒内不重复（批量下载/批量匹配时否则会刷屏）。</li>
 *   <li>{@code toast} 本身失败（宿主未注入 instance 等）只记日志，绝不抛。</li>
 *   <li><b>开发者模式闸（0.11.46 追加）</b>：开发者模式关闭 ⇒ <b>一律不弹</b>（全部用户提示静默，
 *       只进 DEBUG 日志）；打开才弹。闸放在本类 = 全插件提示的唯一出口，以后新增调用自动受管。</li>
 * </ul>
 */
public final class Notifier {

    private static final long DEDUP_MS = 3000L;
    private static final int DEDUP_MAX_ENTRIES = 256;

    private static final Map<String, Long> LAST_SENT = new ConcurrentHashMap<>();

    private Notifier() {
    }

    public static void success(String message) {
        toast(message, WorkshopApi.Ui.ToastType.Success);
    }

    public static void warn(String message) {
        toast(message, WorkshopApi.Ui.ToastType.Warning);
    }

    public static void error(String message) {
        toast(message, WorkshopApi.Ui.ToastType.Error);
    }

    public static void toast(String message, WorkshopApi.Ui.ToastType type) {
        if (message == null || message.isBlank()) {
            return;
        }
        final String text = message.length() > 200 ? message.substring(0, 200) + "…" : message;
        final WorkshopApi.Ui.ToastType toastType =
                type == null ? WorkshopApi.Ui.ToastType.Success : type;

        // 0.11.46 追加（用户口径）：只要是提示，开发者模式关闭时就完全不许弹 —— 静默收口 + 只记调试日志。
        if (!PluginConfig.devMode()) {
            PluginLog.d("toast", "（开发者模式关闭，不弹提示）" + text);
            return;
        }

        long now = System.currentTimeMillis();
        Long last = LAST_SENT.get(text);
        if (last != null && now - last < DEDUP_MS) {
            PluginLog.d("toast", "（去重跳过）" + text);
            return;
        }
        if (LAST_SENT.size() > DEDUP_MAX_ENTRIES) {
            LAST_SENT.clear();
        }
        LAST_SENT.put(text, now);

        PluginLog.i("toast", text);
        HostBridgeWorker.get().submit(() -> {
            try {
                WorkshopApi.ui().toast(text, toastType);
            } catch (Throwable t) {
                PluginLog.w("toast", "调用宿主 ui.toast 失败（宿主可能尚未注入 WorkshopApi.instance）:: " + t);
            }
        });
    }
}
