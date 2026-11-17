// 共用界面：播放器窗口、歌词、Song card、灵动岛。只通过 Gfx / Platform 接口画东西。
dependencies {
    "api"(project(":core"))
    // 兼容性边界测试会在无头 JVM 里启动全部模块：运行期由 Forge 提供的库要显式补上
    "testImplementation"("javazoom:jlayer:1.0.1")
    "testImplementation"("com.google.zxing:core:3.3.3")
    "testImplementation"("com.google.code.gson:gson:2.2.4")
    "testImplementation"("org.apache.logging.log4j:log4j-api:2.0-beta9")
}

// 模块兼容性边界测试：单独启用 / 任意组合 / 全部启用 / 参数边界 / 配置往返
tasks.register<org.gradle.api.tasks.JavaExec>("compatTest") {
    group = "verification"
    description = "Runs module compatibility boundary tests without Minecraft"
    dependsOn("testClasses")
    mainClass.set("me.sorihiko.dango.CompatibilityTestsKt")
    classpath = sourceSets["test"].runtimeClasspath
}

// 全模块功能测试：模块业务逻辑 / 状态机 / 歌词解析 / 播放器 / 曲库 / 吸附（不启动 Minecraft）
tasks.register<org.gradle.api.tasks.JavaExec>("functionalTest") {
    group = "verification"
    description = "Runs per-module functional tests without Minecraft"
    dependsOn("testClasses")
    mainClass.set("me.sorihiko.dango.ModuleFunctionalTestsKt")
    classpath = sourceSets["test"].runtimeClasspath
}

// Vibe HUD 移植回归：计分板替换 / 按键显示两套样式 / 目标信息 16 套样式（不启动 Minecraft）
tasks.register<org.gradle.api.tasks.JavaExec>("vibeHudTest") {
    group = "verification"
    description = "Runs Vibe HUD migration regression tests without Minecraft"
    dependsOn("testClasses")
    mainClass.set("me.sorihiko.dango.VibeHudTestsKt")
    classpath = sourceSets["test"].runtimeClasspath
}

// 缺陷修复回归：封面缓存释放 / 计分板行窗 / 名单统计取消 / 状态一致性 / HTTP 边界（不启动 Minecraft）
tasks.register<org.gradle.api.tasks.JavaExec>("defectRegressionTest") {
    group = "verification"
    description = "Runs defect-fix regression tests without Minecraft"
    dependsOn("testClasses")
    mainClass.set("me.sorihiko.dango.DefectRegressionTestsKt")
    classpath = sourceSets["test"].runtimeClasspath
}

// 设置页右键交互回归：列表右键跳转独立设置页 / 悬浮与非悬浮一致（不启动 Minecraft）
tasks.register<org.gradle.api.tasks.JavaExec>("rightClickNavTest") {
    group = "verification"
    description = "Runs settings right-click navigation tests without Minecraft"
    dependsOn("testClasses")
    mainClass.set("me.sorihiko.dango.RightClickNavTestsKt")
    classpath = sourceSets["test"].runtimeClasspath
}

// 无头性能基准：HUD 矩形批次 / 事件派发 / 玻璃抓取核算（不启动 Minecraft）
tasks.register<org.gradle.api.tasks.JavaExec>("perfBench") {
    group = "verification"
    description = "Runs headless performance benchmarks without Minecraft"
    dependsOn("testClasses")
    mainClass.set("me.sorihiko.dango.PerformanceBenchTestsKt")
    classpath = sourceSets["test"].runtimeClasspath
}
