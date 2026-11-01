import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.minecraftforge.gradle.user.patcherUser.forge.ForgeExtension

import java.util.Properties

buildscript {
    repositories {
        maven("https://files.minecraftforge.net/maven")
        mavenCentral()
        mavenLocal()
    }
    dependencies {
        classpath("com.anatawa12.forge:ForgeGradle:2.3-1.0.8")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    }
}

plugins {
    id("com.github.johnrengelman.shadow") version "7.1.1"
}

// ForgeGradle 2.3 的 sourceSet 处理会把名为 kotlin 的编译任务强转成 AbstractCompile，
// 而 Kotlin 2.0 的 KotlinCompile 已经不是 AbstractCompile（原作者的 ForgeGradle-Modern 修过这里）。
// 先应用 Forge 插件、再应用 Kotlin 插件，让它跳过那条 kotlin 分支。
apply(plugin = "net.minecraftforge.gradle.forge")
apply(plugin = "org.jetbrains.kotlin.jvm")

// 版本号统一写在根目录的 version.properties
val modVersion: String = Properties().apply {
    rootDir.resolve("../version.properties").inputStream().use { load(it) }
}.getProperty("version")

group = "me.sorihiko.dango"
version = modVersion

repositories {
    mavenCentral()
    mavenLocal()
}

configure<ForgeExtension> {
    setSuppressVersionTest(true)
    version = "1.8.9-11.15.1.2318-1.8.9"
    runDir = "run"
    mappings = "stable_22"
    makeObfSourceJar = false
}

// 要打进 jar 的依赖：共用代码（common/core、common/ui，见 settings.gradle.kts 里的 includeBuild）
// 以及它们用到的 Kotlin 运行库、MP3 解码、二维码
val shade by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
configurations["compileOnly"].extendsFrom(shade)
// runClient 开发环境里也要能找到这些依赖
configurations["runtimeOnly"].extendsFrom(shade)

dependencies {
    shade("me.sorihiko.dango:core:$modVersion")
    shade("me.sorihiko.dango:ui:$modVersion")
    shade(kotlin("stdlib"))
    shade("javazoom:jlayer:1.0.1")
    shade("com.google.zxing:core:3.3.3")
}

configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
    jvmToolchain(8)
}

// 源码里有中文注释，统一按 UTF-8 编译，免得 javac 用系统默认 GBK 报不可映射字符
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// java / processResources 的 type-safe 访问器只在 plugins{} 里声明插件时才会生成，
// 这里改成显式配置，避免脚本编译报 "Unresolved reference"
configure<org.gradle.api.plugins.JavaPluginExtension> {
    sourceCompatibility = null
    targetCompatibility = null
}

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    val forgeExtension = project.extensions.getByName<ForgeExtension>("minecraft")
    inputs.property("version", project.version)
    inputs.property("mcversion", forgeExtension.version)
    filesMatching(listOf("mcmod.info", "version.properties")) {
        expand(mapOf("version" to project.version, "mcversion" to forgeExtension.version))
    }
}

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(shade)
    archiveBaseName.set("DangoMusic")
    archiveClassifier.set("")
    archiveVersion.set("$modVersion+1.8.9")     // DangoMusic-1.1.0+1.8.9.jar
    // 依赖全部改包名，避免和其它 mod / Lunar 自带的同名库冲突
    relocate("kotlin", "me.sorihiko.dango.shadow.kotlin")
    relocate("org.intellij.lang.annotations", "me.sorihiko.dango.shadow.annotations.intellij")
    relocate("org.jetbrains.annotations", "me.sorihiko.dango.shadow.annotations.jetbrains")
    relocate("javazoom", "me.sorihiko.dango.shadow.javazoom")
    relocate("com.google.zxing", "me.sorihiko.dango.shadow.zxing")
    // Java 9+ 的模块描述文件，Forge 1.8.9 的老 ASM 读不了
    exclude("module-info.class", "META-INF/versions/**", "META-INF/*.kotlin_module")
    // 1.8.9 的 coremod 入口：用 ASM 替换受伤红闪的四个颜色常量（不引入第二套 Mixin）
    manifest {
        attributes(
            mapOf(
                "FMLCorePlugin" to "me.sorihiko.dango.core.DangoCorePlugin",
                "FMLCorePluginContainsFMLMod" to "true",
            )
        )
    }
}

// 普通 Forge / Lunar 运行时用的是混淆名（func_xxx），打包后要把 MCP 名转换回去（reobf）
@Suppress("UNCHECKED_CAST")
(extensions.getByName("reobf") as NamedDomainObjectContainer<Any>).create("shadowJar")

afterEvaluate {
    tasks.named("reobfShadowJar") { dependsOn(shadowJar) }
    tasks.named("build") { dependsOn("reobfShadowJar") }
    tasks.named("assemble") { dependsOn("reobfShadowJar") }
}

// 不用 Forge 默认的 jar（没有打依赖进去），只发布 shadowJar
tasks.named("reobfJar").configure { enabled = false }
