package com.xuncorp.voxzen.workshop.hooks;

import androidx.compose.runtime.Composer;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/** Dispatch boundary only. The caller bytes and transformer are the installed
 * host's, including its default-mask branch; no plugin-manager/user data needed. */
public final class WorkshopHookBridge {
    public static Function3<Object[], Composer, Integer, Unit> replacement;
    public static int replacements;
    public static boolean hasReplacement(int site) { return true; }
    public static void RenderReplacement(int site, Object receiver, Object[] args, Composer c, int n) {
        replacements++;
        replacement.invoke(args, c, n);
    }
    public static void RenderAfter(int site, Object receiver, Object[] args, Composer c, int n) {}
}
