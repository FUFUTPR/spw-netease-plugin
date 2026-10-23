// 0.12.0 批次 1：Java + Kotlin + Compose 统一由 Gradle 编译。
// 打包仍由 tools/build.py 负责（见 --package-only），Gradle 只产出 class 文件。
//
// 版本锁定依据（docs/ITERATION-0.12.0.md 有完整证据）：
//   - Kotlin 2.4.20 = 宿主 1.19.0 自带 Kotlin 版本
//   - Compose runtime/foundation/ui 1.12.2，编译出的 ABI 与宿主 androidx runtime 1.12.0-beta01 一致
//   - JDK 25 = 宿主运行时；宿主 API class 是 major 69

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

val hostApiJar = file("tools/.cache/spw-workshop-api-host.jar")
val pf4jJar = file("tools/.cache/pf4j-3.12.0.jar")

val verifyHostClasspath by tasks.registering {
    description = "检查从宿主抽取的编译期 classpath 是否存在"
    doLast {
        val missing = listOf(hostApiJar, pf4jJar).filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "缺少宿主编译期 classpath: " + missing.joinToString(", ") { it.path } +
                    "；请先运行 tools/extract-api.ps1（或 build.ps1，不要加 -SkipApi）"
            )
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        // 宿主 API 与插件都用 Kotlin 2.4.20 编译，无需额外的元数据/默认参数兼容开关。
    }
}

sourceSets {
    main {
        java.srcDirs("src/main/java")
        kotlin.srcDirs("src/main/kotlin")
    }
}

dependencies {
    // 编译期来自宿主：这三份 classpath 绝不进入安装包。
    compileOnly(files(hostApiJar, pf4jJar))
    compileOnly(fileTree("libs") { include("*.jar") })

    // 宿主编译期已经带 Kotlin 2.4.20 与 Compose 运行时；插件只编译不打包。
    compileOnly("org.jetbrains.compose.runtime:runtime-desktop:1.12.2")
    compileOnly("org.jetbrains.compose.foundation:foundation-desktop:1.12.2")
    compileOnly("org.jetbrains.compose.ui:ui-desktop:1.12.2")
}

val pluginClassesDir = layout.buildDirectory.dir("plugin-classes")

tasks.withType<JavaCompile>().configureEach {
    destinationDirectory.set(pluginClassesDir)
    options.encoding = "UTF-8"
    options.release.set(25)
    dependsOn(verifyHostClasspath)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    destinationDirectory.set(pluginClassesDir)
    dependsOn(verifyHostClasspath)
}

// 宿主 UI 适配层与页面只编译一次，输出到 build/plugin-classes；Python 打包器用 --classes-dir 指向这里。
tasks.register("compilePluginClasses") {
    group = "build"
    description = "编译插件 Java + Kotlin（输出 build/plugin-classes）"
    dependsOn("classes")
}
