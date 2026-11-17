package me.sorihiko.dango

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.hud.BedWarsData
import me.sorihiko.dango.lyrics.LyricLine
import me.sorihiko.dango.lyrics.LyricSplit
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricsProvider
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.impls.music.AutoLyricsModule
import me.sorihiko.dango.module.impls.music.BedWarsModule
import me.sorihiko.dango.module.impls.music.BloodParticlesModule
import me.sorihiko.dango.module.impls.music.CustomHeldItemsModule
import me.sorihiko.dango.module.impls.music.DynamicIslandModule
import me.sorihiko.dango.module.impls.music.GaleAntiAimModule
import me.sorihiko.dango.module.impls.music.HitColorHudModule
import me.sorihiko.dango.module.impls.music.InventoryDisplayHudModule
import me.sorihiko.dango.module.impls.music.Lyrics2DModule
import me.sorihiko.dango.module.impls.music.LyricsModule
import me.sorihiko.dango.module.impls.music.MechvibesModule
import me.sorihiko.dango.module.impls.music.NametagStatsModule
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.module.impls.music.PartyVoiceKeyModule
import me.sorihiko.dango.module.impls.music.ProjectileTrailModule
import me.sorihiko.dango.module.impls.music.SlowSwingModule
import me.sorihiko.dango.module.impls.music.SlowSwingRuntimeBridge
import me.sorihiko.dango.module.impls.music.SongCardModule
import me.sorihiko.dango.module.impls.music.TabStatsHudModule
import me.sorihiko.dango.module.impls.music.WeatherChangerModule
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.music.LocalLibrary
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.platform.Platform
import me.sorihiko.dango.platform.PlayerPose
import me.sorihiko.dango.ui.HudSnap
import me.sorihiko.dango.ui.SnapEngine
import me.sorihiko.dango.ui.SnapMode

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * 全模块功能测试（无头，不启动 Minecraft）。
 *
 * 与 CompatibilityTests 的分工：那边覆盖注册 / 启用 / 参数边界 / 配置往返；
 * 这里覆盖模块自身的业务逻辑与状态机，重点回归本轮修复的 BUG-01…07。
 *
 * 运行：legacy 目录下 `gradlew :common:ui:functionalTest --offline`
 */
private var passed = 0
private val failures = mutableListOf<String>()

private fun verify(label: String, condition: Boolean, detail: String = "") {
    if (condition) {
        passed++
    } else {
        failures += if (detail.isEmpty()) label else "$label ($detail)"
        println("  [FAIL] $label${if (detail.isEmpty()) "" else " -> $detail"}")
    }
}

private fun section(title: String) {
    println("\n== $title ==")
}

private fun safe(label: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        failures += "$label: $t"
        println("  [FAIL] $label -> $t")
    }
}

private class FunctionalPlatform(private val dir: Path) : Platform {
    override val configDir: Path get() = dir
    override val playerName: String = "FunctionalTester"
    override val windowWidth: Int = 1920
    override val windowHeight: Int = 1080
    override val mouseX: Int = 960
    override val mouseY: Int = 540
    override fun isMouseDown(button: Int): Boolean = false
    override fun isKeyDown(key: Int): Boolean = false
    override val ctrlDown: Boolean = false
    override var clipboard: String = ""
    override val inGame: Boolean = false
    override val chatOpen: Boolean = false
    override val guiScale: Int = 2
    override val fps: Int = 60
    override val fov: Float = 70f
    override val pingMs: Int = 0
    override val player: PlayerPose? = null
    override fun openMusicScreen() {}
}

private fun anyField(target: Any, name: String): Any? =
    target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

private fun setAnyField(target: Any, name: String, value: Any?) {
    target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
}

private fun staticField(cls: Class<*>, name: String): Any? =
    cls.getDeclaredField(name).apply { isAccessible = true }.get(null)

private fun setStaticField(cls: Class<*>, name: String, value: Any?) {
    cls.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
}

private fun <T : Module> module(manager: me.sorihiko.dango.module.ModuleManager, clazz: Class<T>): T =
    manager.get(clazz)

private fun boolValue(target: Any, name: String): BooleanValue = anyField(target, name) as BooleanValue

private fun intValue(target: Any, name: String): IntValue = anyField(target, name) as IntValue

/** 手写一个带 ID3v2.3 扩展头的 mp3：TIT2 = [title] */
private fun writeId3v23(tagFile: Path, title: String) {
    val body = byteArrayOf(0) + title.toByteArray(StandardCharsets.ISO_8859_1)
    val content = ByteArrayOutputStream()
    // 扩展头：长度 6（不含这 4 字节）、flags 2 字节、padding 4 字节
    content.write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 0, 0, 0))
    content.write("TIT2".toByteArray(StandardCharsets.ISO_8859_1))
    content.write(byteArrayOf(0, 0, 0, body.size.toByte(), 0, 0))
    content.write(body)
    val data = content.toByteArray()
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0x40))
    out.write(byteArrayOf(((data.size shr 21) and 0x7F).toByte()))
    out.write(byteArrayOf(((data.size shr 14) and 0x7F).toByte()))
    out.write(byteArrayOf(((data.size shr 7) and 0x7F).toByte()))
    out.write(byteArrayOf((data.size and 0x7F).toByte()))
    out.write(data)
    Files.write(tagFile, out.toByteArray())
}

fun main() {
    println("Dango Music 全模块功能测试")

    // ---------------------------------------------------------------- BUG-01 默认启用契约
    section("模块默认启用契约（BUG-01）")
    val tabStatsProbe = TabStatsHudModule()
    val voiceProbe = PartyVoiceKeyModule()
    verify("TabStats defaultEnabled", tabStatsProbe.defaultEnabled)
    verify("PartyVoiceKey defaultEnabled", voiceProbe.defaultEnabled)

    val dir = Files.createTempDirectory("dango-functional").resolve(DangoMusic.CONFIG_DIR)
    val modules = DangoClient.defaultModules()
    DangoMusic.start(FunctionalPlatform(dir), modules)
    val manager = DangoMusic.instance.moduleManager
    repeat(3) { DangoMusic.callEvent(me.sorihiko.dango.events.TickEvent) }

    safe("init() 不应提前启用模块") {
        val registered = module(manager, TabStatsHudModule::class.java)
        val fresh = TabStatsHudModule()
        fresh.init()
        verify("TabStats init() 不写 enabled", !fresh.enabled)
        registered.init()   // 恢复 of() 单例
        val freshVoice = PartyVoiceKeyModule()
        freshVoice.init()
        verify("PartyVoiceKey init() 不写 enabled", !freshVoice.enabled)
        module(manager, PartyVoiceKeyModule::class.java).init()
    }

    // ---------------------------------------------------------------- 模块功能
    section("全模块启用 / tick 基线")
    verify("启动后默认启用的模块全部启用", modules.filter { it.enabledOnFreshConfig }.all { it.enabled })
    verify("NametagStats 全新配置保持关闭", !module(manager, NametagStatsModule::class.java).enabled)
    safe("全量 tick 不抛异常") { DangoMusic.callEvent(me.sorihiko.dango.events.TickEvent) }

    section("事件与设置映射")
    safe("BedWars 设置镜像到 BedWarsData") {
        val bed = module(manager, BedWarsModule::class.java)
        BedWarsData.hypStyle = -1
        intValue(bed, "hypStyle").setValue(2)
        bed.onTick(me.sorihiko.dango.events.TickEvent)
        verify("hypStyle 镜像", BedWarsData.hypStyle == 2, "实际 ${BedWarsData.hypStyle}")
        verify("alertsEnabled 读取开关", bed.alertsEnabled)
        verify("alertsRadius 在范围内", bed.alertsRadius in 3..32)
    }
    safe("NametagStats 设置镜像到 BedWarsData") {
        val nt = module(manager, NametagStatsModule::class.java)
        intValue(nt, "heightOffset").setValue(37)
        boolValue(nt, "showBedwars").set(false)
        boolValue(nt, "showNetLevel").set(false)
        nt.onTick(me.sorihiko.dango.events.TickEvent)
        verify("heightOffset 镜像", BedWarsData.ntHeightOffset == 37, "实际 ${BedWarsData.ntHeightOffset}")
        verify("showBedwars 镜像", !BedWarsData.ntShowBedwars)
        verify("showNetLevel 镜像", !BedWarsData.ntShowNetLevel)
    }
    safe("SlowSwing 桥接与回退") {
        val slow = module(manager, SlowSwingModule::class.java)
        SlowSwingRuntimeBridge.currentTicks = 7
        verify("legacy 实际时长优先", slow.effectiveTicks() == 7, "实际 ${slow.effectiveTicks()}")
        SlowSwingRuntimeBridge.currentTicks = 0
        verify("无实际值时用配置", slow.effectiveTicks() == slow.configuredDelay())
        SlowSwingRuntimeBridge.currentTicks = 6
    }
    safe("Weather 模式表") {
        val weather = module(manager, WeatherChangerModule::class.java)
        verify("模式名数量", WeatherChangerModule.MODE_NAMES.size == 4)
        verify("初始模式为晴天", weather.mode() == WeatherChangerModule.CLEAR)
        verify("雨量 / 雷量在 0..1", weather.rain() in 0f..1f && weather.thunder() in 0f..1f)
    }
    safe("Mechvibes 类型目录") {
        val mech = module(manager, MechvibesModule::class.java)
        verify("类型名与目录一一对应", MechvibesModule.TYPE_NAMES.size == MechvibesModule.DIRS.size)
        verify("typeDir 取当前类型", mech.typeDir() == MechvibesModule.DIRS[mech.typeIndex()])
    }
    safe("ProjectileTrail 类型表") {
        val trail = module(manager, ProjectileTrailModule::class.java)
        verify("类型名数量 10", ProjectileTrailModule.TYPE_NAMES.size == 10)
        verify("当前类型在范围内", trail.type() in 0..9)
        verify("角度偏移在 -30..30", trail.angleOffset() in -30f..30f)
    }
    safe("CustomHeldItems 偏移与缩放") {
        val held = module(manager, CustomHeldItemsModule::class.java)
        verify("偏移在 -1..1", held.x() in -1f..1f && held.y() in -1f..1f && held.z() in -1f..1f)
        verify("缩放在 0..1", held.xScale() in 0f..1f && held.yScale() in 0f..1f && held.zScale() in 0f..1f)
    }
    safe("BloodParticles 数量") {
        val blood = module(manager, BloodParticlesModule::class.java)
        verify("粒子数量 1..10", blood.amount() in 1..10)
        verify("音效开关可读", blood.soundEnabled())
    }
    safe("GaleAntiAim 切换") {
        val gale = module(manager, GaleAntiAimModule::class.java)
        val before = gale.localSpin.get()
        gale.toggleSpin()
        verify("toggleSpin 取反", gale.localSpin.get() == !before)
        gale.toggleSpin()
        verify("再次切换还原", gale.localSpin.get() == before)
        verify("移动修正表", GaleAntiAimModule.MOVE_FIX_NAMES.size == 3)
    }
    safe("HitColor rgba 输出") {
        val hit = module(manager, HitColorHudModule::class.java)
        hit.update()
        val rgba = hit.rgba()
        verify("rgba 长度为 4", rgba.size == 4)
        verify("rgba 分量在 0..1", rgba.all { it in 0f..1f }, rgba.joinToString())
    }
    safe("Inventory 发布缓冲") {
        val inventory = module(manager, InventoryDisplayHudModule::class.java)
        val rectBuffer = anyField(inventory, "rectBuffer") as IntArray
        verify("槽位矩形缓冲 144", rectBuffer.size == 36 * 4, "实际 ${rectBuffer.size}")
        verify("未渲染时槽位数为 0", InventoryDisplayHudModule.slotCount == 0)
    }
    safe("Lyrics2D / 灵动岛歌词开关") {
        val two = module(manager, Lyrics2DModule::class.java)
        val island = module(manager, DynamicIslandModule::class.java)
        two.setLyrics(false)
        verify("2D 关闭后 lyricsEnabled=false", !two.lyricsEnabled)
        two.setLyrics(true)
        verify("2D 打开后 lyricsEnabled=true", two.lyricsEnabled)
        island.setIslandLyrics(false)
        verify("灵动岛关闭后 lyricsEnabled=false", !island.lyricsEnabled)
        island.setIslandLyrics(true)
        verify("灵动岛打开后 lyricsEnabled=true", island.lyricsEnabled)
    }
    safe("SongCard / TabStats 关闭态") {
        verify("SongCard 默认不显示", !module(manager, SongCardModule::class.java).showing)
        verify("TabStats 非游戏内不展开", !module(manager, TabStatsHudModule::class.java).isOpen)
    }
    safe("NeteaseMusic 音量夹取") {
        val music = module(manager, NeteaseMusicModule::class.java)
        music.setVolume(5f)
        verify("音量上限 1", MusicPlayer.volume <= 1f, "实际 ${MusicPlayer.volume}")
        music.setVolume(-2f)
        verify("音量下限 0", MusicPlayer.volume >= 0f, "实际 ${MusicPlayer.volume}")
        music.setVolume(0.5f)
    }

    // ---------------------------------------------------------------- 歌词解析
    section("歌词解析 / 切分")
    safe("LRC 多时间标签") {
        val lines = LyricsProvider.parseNeteaseLrc("[00:01.00][00:03.50]Hello\n[00:05.5]World")
        verify("解析出 3 行", lines.size == 3, "实际 ${lines.size}")
        verify("时间按升序", lines.map { it.timeMs } == lines.map { it.timeMs }.sorted())
        verify("多标签共享文本", lines[0].text == "Hello" && lines[1].text == "Hello")
        verify("1 位毫秒补零", lines[2].timeMs == 5500L, "实际 ${lines[2].timeMs}")
    }
    safe("LRC 空文本与无效行") {
        val lines = LyricsProvider.parseNeteaseLrc("not a lyric\n[00:02.00]\n[99:99.99]x")
        verify("只保留带时间标签的行", lines.isNotEmpty())
        verify("时间不倒退", lines.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
    }
    safe("YRC 逐字解析") {
        val lines = LyricsProvider.parseYrc(
            "[0,1200](0,600,0)你(600,600,0)好\n[1200,1200](1200,600,0)世(1800,600,0)界",
        )
        verify("解析出 2 行", lines.size == 2, "实际 ${lines.size}")
        verify("文本拼接", lines[0].text == "你好", "实际 ${lines[0].text}")
        verify("逐字数据存在", !lines[0].words.isNullOrEmpty())
        verify("逐字时间升序", lines.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
    }
    safe("LyricSplit 分行") {
        LyricSplit.enabled = true
        val src = listOf(LyricLine(1000L, "第一句 很长很长 第二句 也很长"), LyricLine(9000L, "第三句"))
        val out = LyricSplit.split(src)
        verify("长句被拆分", out.size > src.size, "实际 ${out.size}")
        verify("时间不倒退", out.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
        val joined = out.joinToString("") { it.text }.replace(" ", "")
        val expected = src.joinToString("") { it.text }.replace(" ", "")
        verify("文本不丢字", joined == expected, "$joined vs $expected")
        LyricSplit.enabled = false
    }
    safe("Lyrics.indexAt 边界") {
        LyricSplit.enabled = false
        val l = Lyrics(listOf(LyricLine(1000L, "a"), LyricLine(5000L, "b")), "test")
        verify("第一句之前为 -1", l.indexAt(0L) == -1, "实际 ${l.indexAt(0L)}")
        verify("区间内命中第一句", l.indexAt(1000L) == 0)
        verify("区间内命中第二句", l.indexAt(4999L) == 0 && l.indexAt(5000L) == 1)
        verify("空歌词返回 -1", Lyrics(emptyList(), "test").indexAt(10L) == -1)
        LyricSplit.enabled = true
    }

    // ---------------------------------------------------------------- BUG-02 三路互斥
    section("歌词三路互斥（BUG-02）")
    val lyricsModule = module(manager, LyricsModule::class.java)
    val twoD = module(manager, Lyrics2DModule::class.java)
    val island = module(manager, DynamicIslandModule::class.java)
    safe("首帧 3D 优先") {
        val worldMode = boolValue(lyricsModule, "worldMode")
        worldMode.set(true)
        twoD.enabled = true
        twoD.setLyrics(true)
        island.setIslandLyrics(false)
        val sync = LyricsModule::class.java.getDeclaredMethod("syncLyricModes").apply { isAccessible = true }
        sync.invoke(lyricsModule)
        verify("首帧不关 3D 歌词", worldMode.get(), "实际 ${worldMode.get()}")
        verify("首帧按优先级关掉 2D", !twoD.lyricsEnabled)
    }

    // ---------------------------------------------------------------- BUG-03 歌词时钟
    section("歌词时钟一致性（BUG-03）")
    safe("偏移只影响取句，不影响逐字时钟") {
        LyricSplit.enabled = false
        val cache = anyField(lyricsModule, "cache") as ConcurrentHashMap<String, Any>
        cache.clear()
        val track = NeteaseTrack(1L, "Song", listOf("Artist"), 200000L, "")
        cache["netease:1"] = Lyrics(listOf(LyricLine(0L, "A"), LyricLine(8000L, "B")), "test")
        val offset = intValue(lyricsModule, "globalOffset")
        offset.setValue(3000)
        setStaticField(MusicPlayer::class.java, "state", MusicPlayer.State.PAUSED)
        setStaticField(MusicPlayer::class.java, "current", track)
        setStaticField(MusicPlayer::class.java, "pausedAtMs", 6000L)

        val view = lyricsModule.lyricView(false)
        verify("取句使用偏移后的位置", view.current == "B", "实际 ${view.current}")
        verify("posMs 不含偏移（逐字时钟）", view.posMs == 6000L, "实际 ${view.posMs}")
        verify("lineEndMs 为文件时钟", view.lineEndMs == 13000L, "实际 ${view.lineEndMs}")

        cache.clear()
        offset.setValue(0)
        setStaticField(MusicPlayer::class.java, "state", MusicPlayer.State.STOPPED)
        setStaticField(MusicPlayer::class.java, "current", null)
        setStaticField(MusicPlayer::class.java, "pausedAtMs", 0L)
        LyricSplit.enabled = true
    }

    // ---------------------------------------------------------------- BUG-04 分行开关
    section("分行开关同步（BUG-04）")
    safe("模块关闭时也同步分行开关") {
        val splitLines = boolValue(lyricsModule, "splitLines")
        lyricsModule.enabled = false
        splitLines.set(false)
        LyricSplit.enabled = true
        lyricsModule.render(me.sorihiko.dango.events.WorldRenderEndEvent(0f))
        verify("关闭状态改设置立即生效", !LyricSplit.enabled)
        splitLines.set(true)
        LyricSplit.enabled = true
        lyricsModule.enabled = true
    }

    // ---------------------------------------------------------------- BUG-05 播放器恢复
    section("播放器暂停恢复（BUG-05）")
    safe("暂停且无输出流时恢复播放") {
        setStaticField(MusicPlayer::class.java, "state", MusicPlayer.State.PAUSED)
        setStaticField(MusicPlayer::class.java, "audio", null)
        setStaticField(MusicPlayer::class.java, "line", null)
        setStaticField(MusicPlayer::class.java, "pausedAtMs", 1234L)
        MusicPlayer.togglePause()
        verify("播放键恢复为播放态", MusicPlayer.state == MusicPlayer.State.PLAYING, "实际 ${MusicPlayer.state}")
        MusicPlayer.stop()
    }

    // ---------------------------------------------------------------- BUG-06 ID3
    section("本地曲库 ID3 扩展头（BUG-06）")
    safe("v2.3 扩展头后仍能读到标题") {
        val mp3 = Files.createTempFile("dango-id3", ".mp3")
        writeId3v23(mp3, "Hello")
        val method = LocalLibrary::class.java.getDeclaredMethod("readId3", Path::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val tags = method.invoke(LocalLibrary, mp3) as Pair<String?, String?>?
        verify("标题解析正确", tags?.first == "Hello", "实际 ${tags?.first}")
        Files.deleteIfExists(mp3)
    }

    // ---------------------------------------------------------------- BUG-07 匹配
    section("自动歌词单字匹配（BUG-07）")
    safe("单字查询不做包含匹配") {
        val auto = module(manager, AutoLyricsModule::class.java)
        val match = AutoLyricsModule::class.java.declaredMethods.first { it.name == "match" }
        match.isAccessible = true
        val track = NeteaseTrack(1L, "雨", listOf("A"), 0L, "")
        val cand = NeteaseTrack(2L, "雨下一整晚", listOf("A"), 0L, "")
        @Suppress("UNCHECKED_CAST")
        val result = match.invoke(auto, track, cand, "雨", emptyList<String>(), listOf("雨"), 5000L)
        verify("单字模糊命中被拒绝", result == null, "实际 $result")
        val exact = match.invoke(auto, track, NeteaseTrack(3L, "雨", listOf("A"), 0L, ""), "雨", emptyList<String>(), listOf("雨"), 5000L)
        verify("歌名完全一致仍可用", exact != null)
    }

    // ---------------------------------------------------------------- 吸附
    section("吸附引擎与 HUD 安全区")
    safe("SnapEngine 边界") {
        val engine = SnapEngine()
        val none = engine.snap(10f, 20f, 100f, 30f, 800f, 600f, emptyList(), emptySet(), 6f, 8f)
        verify("未启用模式不改坐标", none.x == 10f && none.y == 20f)
        val zeroTol = engine.snap(10f, 20f, 100f, 30f, 800f, 600f, emptyList(), setOf(SnapMode.GRID), 0f, 8f)
        verify("容差为 0 不改坐标", zeroTol.x == 10f && zeroTol.y == 20f)
        val grid = engine.snap(13f, 13f, 100f, 30f, 800f, 600f, emptyList(), setOf(SnapMode.GRID), 6f, 8f)
        verify("网格吸附到 16", grid.x == 16f && grid.y == 16f, "实际 ${grid.x},${grid.y}")
        val neg = engine.snap(13f, 13f, 100f, 30f, 800f, 600f, emptyList(), setOf(SnapMode.GRID), 6f, 0f)
        verify("网格为 0 不崩溃且不改坐标", neg.x == 13f && neg.y == 13f)
    }
    safe("视口小于元素时仍在安全区内") {
        val r = HudSnap.snap("functional", -50f, 5000f, 400f, 400f, 100f, 100f)
        verify("x 不越界", r.x in 0f..100f, "实际 ${r.x}")
        verify("y 不越界", r.y in 0f..100f, "实际 ${r.y}")
    }

    // ---------------------------------------------------------------- 汇总
    println("\n==============================================")
    if (failures.isEmpty()) {
        println("ALL $passed CHECKS PASSED")
        exitProcess(0)
    } else {
        println("FAILED ${failures.size} / ${passed + failures.size}")
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}
