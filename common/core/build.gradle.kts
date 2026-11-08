// 核心：事件、模块、设置、网易云接口、播放器、一起听歌。不碰任何绘图。
// 纯逻辑的 Java 源码（bedwars / bedwarsstats）注释是 UTF-8，显式指定编码，避免在 GBK 默认环境下编译警告。
tasks.withType(org.gradle.api.tasks.compile.JavaCompile::class.java).configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    "implementation"("javazoom:jlayer:1.0.1")       // MP3 解码
    "implementation"("com.google.zxing:core:3.3.3") // 登录二维码
    // 登录测试（common 下执行 gradlew :core:loginTest）用；游戏运行时由 Forge 提供这两个库
    "testImplementation"("com.google.code.gson:gson:2.2.4")
    "testImplementation"("org.apache.logging.log4j:log4j-api:2.0-beta9")
}

// 扫码登录的单元 / 集成测试：本地 Mock HTTP 服务模拟 QQ音乐与酷狗接口，不访问真实平台。
tasks.register<org.gradle.api.tasks.JavaExec>("loginTest") {
    group = "verification"
    description = "Runs login unit/integration tests against local mock servers"
    dependsOn("testClasses")
    mainClass.set("me.sorihiko.dango.music.LoginTestsKt")
    classpath = sourceSets["test"].runtimeClasspath
}

// 本轮 bug 修复的回归用例（比赛识别证据、网易云 Cookie 域名边界等纯逻辑）
tasks.register<org.gradle.api.tasks.JavaExec>("bugfixTest") {
    group = "verification"
    description = "Runs regression tests for the bugfix batch"
    dependsOn("testClasses")
    mainClass.set("me.sorihiko.dango.BugfixTestsKt")
    classpath = sourceSets["test"].runtimeClasspath
}
