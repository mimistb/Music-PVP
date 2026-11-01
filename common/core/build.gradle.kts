// 核心：事件、模块、设置、网易云接口、播放器、一起听歌。不碰任何绘图。
// 纯逻辑的 Java 源码（bedwars / bedwarsstats）注释是 UTF-8，显式指定编码，避免在 GBK 默认环境下编译警告。
tasks.withType(org.gradle.api.tasks.compile.JavaCompile::class.java).configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    "implementation"("javazoom:jlayer:1.0.1")       // MP3 解码
    "implementation"("com.google.zxing:core:3.3.3") // 登录二维码
}
