package com.example.netease.nativeui

/** Verified 1.19.0 -> 1.19.1 UI name changes. Signatures still must pass every
 * component/theme/hook ABI check; unknown hosts never get an approximate UI. */
internal object HostUiAbi {
    private val renamed = mapOf(
        "androidx.compose.ui.\u10f5" to "androidx.compose.ui.\u0996", // MultipleTrackPopupMenu
        "androidx.compose.ui.\u0712" to "androidx.compose.ui.\u09dc", // Track popup properties
        "androidx.compose.ui.\u0587" to "androidx.compose.ui.\u0910", // SPC PopupMenu
        "androidx.compose.ui.\u09a8" to "androidx.compose.ui.\u0538", // PopupMenuScope
        "androidx.compose.ui.\u012f" to "androidx.compose.ui.\u0585",
        "androidx.compose.ui.\u0168" to "androidx.compose.ui.\u0ab6",
        "androidx.compose.ui.\u022b" to "androidx.compose.ui.\u0252",
        "androidx.compose.ui.\u03b4" to "androidx.compose.ui.\u0396",
        "androidx.compose.ui.\u045d" to "androidx.compose.ui.\u040c",
        "androidx.compose.ui.\u0682" to "androidx.compose.ui.\u0772",
        "androidx.compose.ui.\u0936" to "androidx.compose.ui.\u0234",
        "androidx.compose.ui.\u0994" to "androidx.compose.ui.\u04d1",
        "androidx.compose.ui.\u0450" to "androidx.compose.ui.\u067f", // MainWindowContent
        "androidx.compose.ui.\u0ab3" to "androidx.compose.ui.\u09b2", // MainWindow
        "androidx.compose.ui.\u054a" to "androidx.compose.ui.\u0450", // MainWindowContent singletons
        "androidx.compose.ui.\u029c" to "androidx.compose.ui.\u098c", // MainTitleBar
        "androidx.compose.ui.\u04a1" to "androidx.compose.ui.\u0b1a", // MainTitleBarType
        "androidx.compose.ui.\u10ac" to "androidx.compose.ui.\u0d30", // caption layout
        "androidx.compose.ui.\u0f43" to "androidx.compose.ui.\u045b", // player titlebar state
    )
    private val baseline: Boolean by lazy {
        runCatching {
            Class.forName("androidx.compose.ui.\u022b", false, javaClass.classLoader).declaredMethods.any {
                it.parameterCount == 5 && it.parameterTypes[0].name == HostUiMountScope.MOD_CONFIG_ROUTE_CLASS
            }
        }.getOrDefault(false)
    }
    // Native Steam Linux 1.19.2: source traces and complete member signatures
    // were checked against the installed Linux artifact; signature guards remain mandatory.
    private val linuxRenamed = mapOf(
        "androidx.compose.ui.\u012f" to "androidx.compose.ui.\u093d",
        "androidx.compose.ui.\u0168" to "androidx.compose.ui.\u0118",
        "androidx.compose.ui.\u022b" to "androidx.compose.ui.\u076e",
        "androidx.compose.ui.\u029c" to "androidx.compose.ui.\u0755",
        "androidx.compose.ui.\u03b4" to "androidx.compose.ui.\u01b6",
        "androidx.compose.ui.\u0450" to "androidx.compose.ui.\u062d",
        "androidx.compose.ui.\u045d" to "androidx.compose.ui.\u10a6",
        "androidx.compose.ui.\u04a1" to "androidx.compose.ui.\u04ab",
        "androidx.compose.ui.\u054a" to "androidx.compose.ui.\u049f",
        "androidx.compose.ui.\u0587" to "androidx.compose.ui.\u0e94",
        "androidx.compose.ui.\u0682" to "androidx.compose.ui.\u091a",
        "androidx.compose.ui.\u0712" to "androidx.compose.ui.\u091e",
        "androidx.compose.ui.\u0936" to "androidx.compose.ui.\u077b",
        "androidx.compose.ui.\u0994" to "androidx.compose.ui.\u04c4",
        "androidx.compose.ui.\u09a8" to "androidx.compose.ui.\u0127",
        "androidx.compose.ui.\u0ab3" to "androidx.compose.ui.\u04f1",
        "androidx.compose.ui.\u0f43" to "androidx.compose.ui.\u057b",
        "androidx.compose.ui.\u10ac" to "androidx.compose.ui.\u01dd",
        "androidx.compose.ui.\u10f5" to "androidx.compose.ui.\u077a",
    )
    private fun configRouteOwner(name: String): Boolean = runCatching {
        Class.forName(name, false, javaClass.classLoader).declaredMethods.any {
            it.parameterCount == 5 && it.parameterTypes[0].name == HostUiMountScope.MOD_CONFIG_ROUTE_CLASS
        }
    }.getOrDefault(false)
    private val nativeLinux: Boolean by lazy { configRouteOwner("androidx.compose.ui.\u076e") }
    fun name(value: String): String = when {
        nativeLinux -> linuxRenamed[value] ?: value
        baseline -> value
        else -> renamed[value] ?: value
    }
    fun type(value: String): Class<*> = Class.forName(name(value), false, javaClass.classLoader)
    fun workshopRoute(): Any = type("com.xuncorp.voxzen.ui.navigation.ScreenRoute\$Workshop").getField("INSTANCE").get(null)
}
