package com.example.netease.nativeui

/**
 * Java 侧（NeteasePlugin）通过反射进入这里。
 *
 * <p>为什么必须反射：harness/run-harness.ps1 只编译 src/main/java（对 StubWorkshopApi），
 * Java 源码一旦 import Kotlin/Compose 类就会编译失败；所以 Java 侧只认
 * "com.example.netease.nativeui.NativeUiHost" 这个字符串，Kotlin 类由 Gradle 编进同一个包。</p>
 */
object NativeUiHost {
    @JvmStatic
    fun install(hostVersion: String, pluginVersion: String) {
        HostUiAdapter.install(hostVersion, pluginVersion)
    }

    @JvmStatic
    fun uninstall() {
        HostUiAdapter.uninstall()
        HostSaltComponents.clearCaches()
        NativeUiPresentation.showConfiguration()
    }

    /**
     * 宿主配置页入口路由（批次 3）：打开主窗口内的原生页面。
     * 返回 false 表示原生层未就绪，Java 侧回退旧 Swing 窗口。
     */
    @JvmStatic
    fun openPage(page: String): Boolean {
        val target = when (page) {
            "home" -> NativePage.HOME
            "search" -> NativePage.SEARCH
            "playlist" -> NativePage.PLAYLIST
            "sync" -> NativePage.SYNC
            "login" -> NativePage.LOGIN
            "account" -> NativePage.ACCOUNT
            else -> return false
        }
        return NativeUiPresentation.openPage(target)
    }
}
