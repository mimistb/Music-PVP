package me.sorihiko.dango

import me.sorihiko.dango.gfx.CjkRun
import me.sorihiko.dango.gfx.Gfx
import me.sorihiko.dango.gfx.GlowPass
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.gfx.HudCanvas
import me.sorihiko.dango.gfx.TextCanvas
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.gfx.Textures
import me.sorihiko.dango.gfx.WorldView
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.musicui.Page
import me.sorihiko.dango.musicui.ScreenState
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.platform.Platform
import me.sorihiko.dango.platform.PlayerPose
import me.sorihiko.dango.ui.FontStyle
import me.sorihiko.dango.ui.GlassStyle
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.min
import kotlin.system.exitProcess

/**
 * 设置页右键交互回归测试（无头，不启动 Minecraft、不访问网络）。
 *
 * 覆盖：
 * 1. 列表页右键任意模块卡片 -> 立刻进入该模块的独立设置页，不再原地展开；
 * 2. 独立设置页承载完整控件（点击热区非空）；
 * 3. 独立页右键空白 / Esc 只退一层回到列表，列表页右键空白才离开设置；
 * 4. 悬浮模式与非悬浮模式的右键命中、跳转目标、返回行为完全一致：
 *    悬浮模式只认 pointerX/pointerY，非悬浮只认 platform.mouseX/mouseY。
 *
 * 运行：legacy 目录下 `gradlew :common:ui:rightClickNavTest --offline`
 */
private var passed = 0
private val failures = mutableListOf<String>()

private fun check(label: String, condition: Boolean, detail: String = "") {
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

// ---------------------------------------------------------------- 假平台

private class TestPlatform(private val dir: Path) : Platform {
    override val configDir: Path get() = dir
    override val playerName: String = "RightClickTester"
    override val windowWidth: Int = 1920
    override val windowHeight: Int = 1080
    override var mouseX: Int = 0
    override var mouseY: Int = 0
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

// ---------------------------------------------------------------- 假绘制层

private class NoopTextures : Textures {
    override fun upload(name: String, image: BufferedImage): Texture = object : Texture {}
    override fun delete(texture: Texture) {}
}

/** 只记录状态、不碰 GL 的画布：布局与命中登记照常执行，绘制全部丢弃。 */
private class NoopCanvas : GuiCanvas {
    private var opacity = 1f

    override fun begin(captureBackdrop: Boolean, mouseX: Int, mouseY: Int) {}
    override fun end() {}
    override fun scissor(x: Int, y: Int, width: Int, height: Int) {}
    override fun noScissor() {}

    override fun getMouseX(): Int = 0
    override fun getMouseY(): Int = 0
    override fun isMouseOver(bounds: LayoutBounds): Boolean = false
    override fun drawShape(bounds: LayoutBounds, style: ShapeStyle) {}
    override fun drawText(text: String, x: Float, y: Float, style: FontStyle) {}
    override fun drawImage(bounds: LayoutBounds, image: Texture) {}
    override fun drawGlass(bounds: LayoutBounds, style: GlassStyle) {}
    override fun pushClip(bounds: LayoutBounds) {}
    override fun popClip() {}
    override fun pushOpacity(value: Float) { opacity = value }
    override fun popOpacity() { opacity = 1f }
    override fun getOpacity(): Float = opacity
    override fun popTransform() {}
    override fun pushTranslate(x: Float, y: Float, z: Float) {}
    override fun pushScale(x: Float, y: Float, z: Float) {}
    override fun pushRotate(angle: Float, x: Float, y: Float, z: Float) {}
}

private class TestGfx(private val canvas: GuiCanvas) : Gfx {
    override val textures: Textures = NoopTextures()
    override fun newCanvas(): GuiCanvas = canvas
    override fun uiTextWidth(text: String, size: Float, font: String): Float = 0f
    override fun cjkWidth(text: String, size: Float, style: Int): Float = 0f
    override fun cjkPreload(c: Char, style: Int) {}
    override fun drawCjkRuns(runs: List<CjkRun>, scale: Float) {}
    override fun guarded(block: () -> Unit) = block()
    override val text: TextCanvas get() = error("unused")
    override val world: WorldView get() = error("unused")
    override val glow: GlowPass get() = error("unused")
    override val hud: HudCanvas get() = error("unused")
}

// ---------------------------------------------------------------- 工具

private fun uiScale(platform: Platform): Float =
    min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)

/** 读列表页当前登记的模块卡片热区（整张卡，作用于屏幕坐标） */
@Suppress("UNCHECKED_CAST")
private fun openHits(screen: MusicScreen): List<Pair<LayoutBounds, () -> Unit>> {
    val settingsField = MusicScreen::class.java.getDeclaredField("settings").apply { isAccessible = true }
    val page = settingsField.get(screen)
    val hitsField = page.javaClass.getDeclaredField("openHits").apply { isAccessible = true }
    return hitsField.get(page) as List<Pair<LayoutBounds, () -> Unit>>
}

private fun logicalToWindow(platform: Platform, x: Float, y: Float): Pair<Int, Int> {
    val s = uiScale(platform)
    return (x * s).toInt() to (y * s).toInt()
}

/**
 * 把鼠标放到某个逻辑坐标上，渲染一帧（刷新 ui.mx / ui.my 与本帧热区），再右键。
 * floating = true 时只写 pointerX/pointerY，平台鼠标坐标故意留在别处；
 * 非悬浮时相反。两种模式的判定结果必须一致。
 */
private fun rightClickAt(
    screen: MusicScreen, platform: TestPlatform, floating: Boolean, x: Float, y: Float,
) {
    val (wx, wy) = logicalToWindow(platform, x, y)
    if (floating) {
        screen.floating = true
        screen.pointerActive = true
        screen.pointerX = wx
        screen.pointerY = wy
        platform.mouseX = 1
        platform.mouseY = 1
    } else {
        screen.floating = false
        screen.pointerActive = false
        platform.mouseX = wx
        platform.mouseY = wy
    }
    screen.render()
    screen.mouseClicked(1)
}

private fun enterSettings(screen: MusicScreen) {
    ScreenState.page = Page.SETTINGS
    // 默认停在 "音乐与 HUD" 标签：分组最多，覆盖各种卡片高度
    ScreenState.settingsTab = 1
    ScreenState.settingsDetail = null
    ScreenState.settingsBack = Page.HOME
    screen.floating = false
    screen.pointerActive = false
    screen.render()
}

private fun blankClick(screen: MusicScreen, platform: TestPlatform, floating: Boolean) {
    rightClickAt(screen, platform, floating, 4f, 4f)
}

fun main() {
    println("设置页右键跳转回归测试")

    val platform = TestPlatform(Files.createTempDirectory("dango-rightclick"))
    val canvas = NoopCanvas()
    DangoClient.start(platform, TestGfx(canvas))
    ScreenState.context = canvas
    val module = DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java)
    var closed = false
    val screen = MusicScreen(module) { closed = true }

    // ---------------------------------------------------------------- 列表页
    section("列表页右键 = 跳转独立设置页")
    enterSettings(screen)
    val cards = openHits(screen)
    check("列表页登记了模块卡片热区", cards.size >= 3, "cards=${cards.size}")
    if (cards.isNotEmpty()) {
        val first = cards.first().first
        rightClickAt(screen, platform, false, first.x + first.width / 2f, first.y + first.height / 2f)
        check("非悬浮：右键卡片进入独立设置页", ScreenState.page == Page.SETTINGS_DETAIL, "page=${ScreenState.page}")
        check("非悬浮：记录了被点击的模块", !ScreenState.settingsDetail.isNullOrEmpty())
        val nonFloatingKey = ScreenState.settingsDetail

        screen.render()
        check("独立设置页登记了控件热区", screen.ui.hits.isNotEmpty(), "hits=${screen.ui.hits.size}")

        // 独立页右键空白：只退一层
        blankClick(screen, platform, false)
        check("独立页右键空白回到列表", ScreenState.page == Page.SETTINGS, "page=${ScreenState.page}")
        check("回到列表后清空详情目标", ScreenState.settingsDetail == null)

        // 列表页右键空白：离开设置
        blankClick(screen, platform, false)
        check("列表页右键空白离开设置", ScreenState.page == Page.HOME, "page=${ScreenState.page}")

        // ---------------------------------------------------------------- 歌词标签
        section("另一个标签页同样支持右键跳转")
        enterSettings(screen)
        ScreenState.settingsTab = 0
        screen.render()
        val lyricCard = openHits(screen).firstOrNull()?.first
        check("歌词标签有卡片热区", lyricCard != null)
        if (lyricCard != null) {
            rightClickAt(screen, platform, false, lyricCard.x + lyricCard.width / 2f, lyricCard.y + lyricCard.height / 2f)
            check("歌词模块可进入独立设置页", ScreenState.page == Page.SETTINGS_DETAIL, "page=${ScreenState.page}")
            blankClick(screen, platform, false)
        }

        // ---------------------------------------------------------------- 任意模块
        section("任意模块都能各自进入独立设置页")
        enterSettings(screen)
        val samples = openHits(screen).take(3).map { it.first }
        val keys = mutableListOf<String?>()
        for (card in samples) {
            rightClickAt(screen, platform, false, card.x + card.width / 2f, card.y + card.height / 2f)
            keys += ScreenState.settingsDetail
            check(
                "模块卡片 ${card.x.toInt()} 进入详情页",
                ScreenState.page == Page.SETTINGS_DETAIL,
                "page=${ScreenState.page}",
            )
            blankClick(screen, platform, false)
        }
        check("不同卡片对应不同详情", keys.filterNotNull().toSet().size == keys.size, "keys=$keys")

        // ---------------------------------------------------------------- 悬浮模式
        section("悬浮模式与非悬浮一致")
        enterSettings(screen)
        val card = openHits(screen).firstOrNull()?.first
        if (card != null) {
            val cx = card.x + card.width / 2f
            val cy = card.y + card.height / 2f
            rightClickAt(screen, platform, false, cx, cy)
            val plainPage = ScreenState.page
            val plainKey = ScreenState.settingsDetail
            blankClick(screen, platform, false)

            rightClickAt(screen, platform, true, cx, cy)
            check("悬浮：右键卡片进入独立设置页", ScreenState.page == Page.SETTINGS_DETAIL, "page=${ScreenState.page}")
            check("两种模式跳转目标一致", ScreenState.settingsDetail == plainKey, "float=${ScreenState.settingsDetail} plain=$plainKey")
            check("两种模式页面一致", ScreenState.page == plainPage)

            screen.render()
            check("悬浮独立页同样有控件热区", screen.ui.hits.isNotEmpty())

            blankClick(screen, platform, true)
            check("悬浮独立页右键空白回到列表", ScreenState.page == Page.SETTINGS, "page=${ScreenState.page}")

            // 悬浮模式下平台鼠标坐标不应参与命中
            rightClickAt(screen, platform, true, cx, cy)
            check("悬浮只认 pointer 坐标", ScreenState.page == Page.SETTINGS_DETAIL)
            ScreenState.page = Page.SETTINGS
            ScreenState.settingsDetail = null
            screen.render()
        }

        // ---------------------------------------------------------------- Esc
        section("独立页 Esc 只退一层")
        enterSettings(screen)
        val escCard = openHits(screen).firstOrNull()?.first
        if (escCard != null) {
            rightClickAt(screen, platform, false, escCard.x + escCard.width / 2f, escCard.y + escCard.height / 2f)
            check("进入详情页", ScreenState.page == Page.SETTINGS_DETAIL)
            screen.keyTyped(' ', 1)          // 1 = Esc（Keys.ESCAPE）
            check("Esc 回到列表", ScreenState.page == Page.SETTINGS, "page=${ScreenState.page}")
            check("Esc 不关闭整个播放器", !closed)
        }
    }

    println()
    if (failures.isEmpty()) {
        println("ALL $passed CHECKS PASSED")
    } else {
        println("$passed passed, ${failures.size} failed:")
        for (f in failures) println("  - $f")
        exitProcess(1)
    }
}
