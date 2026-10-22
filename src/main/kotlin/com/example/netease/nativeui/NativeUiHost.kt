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
    }
}
