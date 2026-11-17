package me.sorihiko.dango.module.impls.music

import glidehud.api.Key
import glidehud.feature.KeystrokesState
import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.HudRenderEvent
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.hud.HudData
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.ui.HudSnap
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color

import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 按键显示：W / A S D / 空格。
 * 取数来自 glidehud.feature.KeystrokesState（1.8.9 的按键由平台层通过 HudData 注入），
 * 视觉完全用本项目的液态玻璃面板与圆角方块，不使用 glide 的任何外观类。
 * 打开任意界面时按键自动视为松开（由 state 处理）。
 */
@ModuleMeta(Category.MUSIC, "Keystrokes")
class KeystrokesHudModule : Module() {

    @ModuleValue("样式", "液态玻璃（本项目）/ 经典方格（Vibe）")
    private val style = IntValue(0, STYLE_NAMES.size - 1, 0)

    @ModuleValue("液态玻璃", "液态玻璃背景；关闭后为纯黑面板")
    private val glass = BooleanValue(true)

    @ModuleValue("显示空格", "在按键面板里显示空格键")
    private val showSpace = BooleanValue(true)

    @ModuleValue("隐藏键名", "只显示按键盒，不显示绑定的键名")
    private val unmarked = BooleanValue(false)

    @ModuleValue("文字阴影", "键名加一层暗色阴影")
    private val shadow = BooleanValue(true)

    @ModuleValue("缩放", "面板大小")
    private val scale = FloatValue(0.6f, 10f, 1f, 2)

    @ModuleValue("X 坐标", "水平位置（打开聊天栏后可拖动）")
    private val posX = FloatValue(0f, 1f, 0.03f, 3)

    @ModuleValue("Y 坐标", "垂直位置（打开聊天栏后可拖动）")
    private val posY = FloatValue(0f, 1f, 0.55f, 3)

    // ---- 经典方格（Vibe）专用：默认值与原实现 OverlaySettings.Keystrokes 一致 ----

    @ModuleValue("显示左键", "经典方格样式下显示 LMB")
    private val leftClick = BooleanValue(true)

    @ModuleValue("显示右键", "经典方格样式下显示 RMB")
    private val rightClick = BooleanValue(true)

    @ModuleValue("按下动画", "按下时颜色与底色的渐变过渡")
    private val animate = BooleanValue(true)

    @ModuleValue("动画速度", "按下 / 回弹的响应速度")
    private val animSpeed = FloatValue(4f, 40f, 18f, 1)

    @ModuleValue("按键尺寸", "经典方格样式的单键边长")
    private val keySize = IntValue(18, 42, 24)

    @ModuleValue("按键间距", "经典方格样式的键与键间距")
    private val keyGap = IntValue(0, 8, 3)

    @ModuleValue("文字缩放", "经典方格样式的键名大小")
    private val textScale = FloatValue(0.6f, 1.5f, 1f, 2)

    @ModuleValue("按下颜色", "按键按下时的高亮底色（#RRGGBB）")
    private val pressedColor = IntValue(0, 0xFFFFFF, 0x79BFFF)

    @ModuleValue("按下文字", "按键按下时的键名颜色（#RRGGBB）")
    private val pressedTextColor = IntValue(0, 0xFFFFFF, 0xFFFFFF)

    @ModuleValue("方格圆角", "经典方格样式的圆角半径")
    private val cellRadius = FloatValue(0f, 16f, 6f, 1)

    @ModuleValue("方格底色", "经典方格样式的底色（#RRGGBB）")
    private val cellBackgroundColor = IntValue(0, 0xFFFFFF, 0x141B27)

    @ModuleValue("方格透明度", "经典方格样式底色的不透明度")
    private val cellAlpha = FloatValue(0f, 1f, 0.77f, 2)

    private val state = KeystrokesState(HudData.keys)
    private var context: GuiCanvas? = null
    private val mt = MixedText()

    private var dragging = false
    private var dragDX = 0f
    private var dragDY = 0f

    /** 键盒尺寸（逻辑像素） */
    private val box = 18f
    private val gap = 3f
    private val pad = 8f

    /** 复用同一个颜色对象，避免每帧给每个键新建 Color */
    private val fill = Color(1f, 1f, 1f, 0.055f)
    private val edge = Color(Theme.ACCENT.r, Theme.ACCENT.g, Theme.ACCENT.b, 0f)
    private val clear = Color(0f, 0f, 0f, 0f)
    private val dark = Color(0.02f, 0.02f, 0.03f, 0.9f)
    private val border = Color(1f, 1f, 1f, 0.07f)

    /**
     * 每个按键自己的液态玻璃：只包住按键盒，面板本身不再是玻璃。
     * 折射 / 斜面按小面板缩小，否则 18px 的盒子会被大斜面糊住。
     */
    private val keyGlass = Theme.GLASS_SELECTION.copy(
        radius = 6f,
        refraction = 7f,
        bevel = 8f,
        dispersion = 0.22f,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.10f), 4f, Vec2(0f, -1f)),
    )

    override fun init() {
        instance = this
    }

    private fun rows(): Int = if (showSpace.get()) 3 else 2

    private fun contentW() = box * 3f + gap * 2f

    private fun contentH() = box * rows() + gap * (rows() - 1)

    private fun text(ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float, color: Color) =
        mt.text(ctx, s, x, y, size, color, Theme.FONT_MEDIUM)

    private fun width(s: String, size: Float): Float = mt.width(s, size, Theme.FONT_MEDIUM)

    /** 关闭玻璃时的按键底 / 按下高亮（fill / edge 的透明度逐帧写，样式对象复用） */
    private val keyDark = ShapeStyle(dark, 6f, 1f, border)
    private val keyHiFill = ShapeStyle(fill, 6f)
    private val keyHiEdge = ShapeStyle(clear, 6f, 1f, edge)

    private fun drawKey(ctx: GuiCanvas, key: Key, b: LayoutBounds, glassOn: Boolean) {
        val hi = state.highlight(key).coerceIn(0f, 1f)
        // 只有按键盒本身有液态玻璃（面板外围不再铺玻璃）
        if (glassOn) {
            ctx.drawGlass(b, keyGlass)
        } else {
            ctx.drawShape(b, keyDark)
        }
        if (hi > 0.02f) {
            fill.a = 0.22f * hi
            ctx.drawShape(b, keyHiFill)
            edge.a = hi
            ctx.drawShape(b, keyHiEdge)
        }
        val label = state.label(key) ?: return
        val size = 8.5f
        val s = mt.trim(label, b.width - 4f, size, Theme.FONT_MEDIUM)
        if (s.isEmpty()) return
        text(ctx, s, b.x + (b.width - width(s, size)) / 2f,
            b.y + (b.height - size * 0.73f) / 2f, size,
            if (hi > 0.5f) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY)
    }

    @EventHandler
    fun render(e: HudRenderEvent) {
        if (!enabled) return
        state.keys = HudData.keys
        state.showSpace = showSpace.get()
        state.unmarked = unmarked.get()
        state.update()
        if (style.value == 1) {
            renderVibe()
            return
        }

        val platform = DangoClient.platform
        val uiScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val k = uiScale * scale.value
        val lw = platform.windowWidth / k
        val lh = platform.windowHeight / k
        val mx = platform.mouseX / k
        val my = platform.mouseY / k

        val cw = contentW()
        val ch = contentH()
        val w = cw + pad * 2f
        val h = ch + pad * 2f
        var x = posX.value * (lw - w)
        var y = posY.value * (lh - h)

        if (!platform.chatOpen) {
            dragging = false
        } else if (platform.isMouseDown(0)) {
            if (!dragging && mx in x..(x + w) && my in y..(y + h)) {
                dragging = true
                dragDX = mx - x
                dragDY = my - y
            }
            if (dragging) {
                val snap = HudSnap.snap(
                    "keystrokes",
                    (mx - dragDX).coerceIn(0f, (lw - w).coerceAtLeast(0f)),
                    (my - dragDY).coerceIn(0f, (lh - h).coerceAtLeast(0f)),
                    w, h, lw, lh,
                )
                x = snap.x
                y = snap.y
                posX.setValue(if (lw > w) x / (lw - w) else 0f)
                posY.setValue(if (lh > h) y / (lh - h) else 0f)
            }
        } else {
            dragging = false
        }

        val panel = LayoutBounds(x, y, w, h)
        HudSnap.register("keystrokes", panel)
        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), mx.toInt(), my.toInt())
        try {
            ctx.pushScale(k, k, 1f)
            val glassOn = glass.get()

            val cx = panel.x + pad
            val cy = panel.y + pad
            mt.clear()
            mt.clip = panel

            val mid = cx + (cw - box) / 2f
            drawKey(ctx, Key.FORWARD, LayoutBounds(mid, cy, box, box), glassOn)
            val row2 = cy + box + gap
            drawKey(ctx, Key.LEFT, LayoutBounds(cx, row2, box, box), glassOn)
            drawKey(ctx, Key.BACK, LayoutBounds(cx + box + gap, row2, box, box), glassOn)
            drawKey(ctx, Key.RIGHT, LayoutBounds(cx + (box + gap) * 2f, row2, box, box), glassOn)
            if (showSpace.get()) {
                drawKey(ctx, Key.JUMP, LayoutBounds(cx + 3f, row2 + box + gap, cw - 6f, box), glassOn)
            }

            HudSnap.drawGuides(ctx)
            ctx.popTransform()
            mt.flush(k)
        } finally {
            ctx.end()
        }
    }

    // ------------------------------------------------------------ 经典方格（Vibe）

    /** 7 个键（W / A S D / LMB RMB / Space）的按下插值，原实现的 press[] */
    private val press = FloatArray(7)
    private var lastFrameNanos = 0L

    private class Placement(val panel: LayoutBounds, val k: Float, val mx: Float, val my: Float)

    /** 计算缩放 / 位置并处理拖动，两种样式共用 */
    private fun place(name: String, w: Float, h: Float): Placement {
        val platform = DangoClient.platform
        val uiScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val k = uiScale * scale.value
        val lw = platform.windowWidth / k
        val lh = platform.windowHeight / k
        val mx = platform.mouseX / k
        val my = platform.mouseY / k

        var x = posX.value * (lw - w)
        var y = posY.value * (lh - h)
        if (!platform.chatOpen) {
            dragging = false
        } else if (platform.isMouseDown(0)) {
            if (!dragging && mx in x..(x + w) && my in y..(y + h)) {
                dragging = true
                dragDX = mx - x
                dragDY = my - y
            }
            if (dragging) {
                val snap = HudSnap.snap(
                    name,
                    (mx - dragDX).coerceIn(0f, (lw - w).coerceAtLeast(0f)),
                    (my - dragDY).coerceIn(0f, (lh - h).coerceAtLeast(0f)),
                    w, h, lw, lh,
                )
                x = snap.x
                y = snap.y
                posX.setValue(if (lw > w) x / (lw - w) else 0f)
                posY.setValue(if (lh > h) y / (lh - h) else 0f)
            }
        } else {
            dragging = false
        }
        HudSnap.register(name, LayoutBounds(x, y, w, h))
        return Placement(LayoutBounds(x, y, w, h), k, mx, my)
    }

    private fun renderVibe() {
        val size = keySize.value.toFloat()
        val gap = keyGap.value.toFloat()
        val showMouse = leftClick.get() || rightClick.get()
        val rows = 2 + (if (showMouse) 1 else 0) + (if (showSpace.get()) 1 else 0)
        val w = size * 3f + gap * 2f
        val h = rows * size + (rows - 1) * gap

        val p = place("keystrokes", w, h)
        val panel = p.panel
        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), p.mx.toInt(), p.my.toInt())
        try {
            ctx.pushScale(p.k, p.k, 1f)
            mt.clear()
            mt.clip = panel

            // 原实现：dt 封顶 0.1s，动画系数 1 - exp(-dt * speed)；关闭动画直接到位
            val now = System.nanoTime()
            val dt = if (lastFrameNanos == 0L) 1f else min(0.1f, (now - lastFrameNanos) / 1e9f)
            lastFrameNanos = now
            val t = if (animate.get()) 1f - exp(-dt * animSpeed.value) else 1f
            val guiOpen = !DangoClient.platform.inGame
            val mouseL = !guiOpen && DangoClient.platform.isMouseDown(0)
            val mouseR = !guiOpen && DangoClient.platform.isMouseDown(1)
            val down = booleanArrayOf(
                !guiOpen && state.isDown(Key.FORWARD),
                !guiOpen && state.isDown(Key.LEFT),
                !guiOpen && state.isDown(Key.BACK),
                !guiOpen && state.isDown(Key.RIGHT),
                mouseL,
                mouseR,
                !guiOpen && state.isDown(Key.JUMP),
            )
            for (i in press.indices) press[i] += ((if (down[i]) 1f else 0f) - press[i]) * t

            val x = panel.x
            val y = panel.y
            val labels = arrayOf(
                state.label(Key.FORWARD) ?: "",
                state.label(Key.LEFT) ?: "",
                state.label(Key.BACK) ?: "",
                state.label(Key.RIGHT) ?: "",
                "LMB", "RMB",
                state.label(Key.JUMP) ?: "Space",
            )
            if (unmarked.get()) for (i in 4..6) labels[i] = ""

            val bgStyle = ShapeStyle(
                Color(
                    ((cellBackgroundColor.value shr 16) and 0xFF) / 255f,
                    ((cellBackgroundColor.value shr 8) and 0xFF) / 255f,
                    (cellBackgroundColor.value and 0xFF) / 255f,
                    cellAlpha.value,
                ),
                min(cellRadius.value, size / 2f),
                1f,
                Color(1f, 1f, 1f, 0.06f),
            )

            drawVibeCell(ctx, LayoutBounds(x + size + gap, y, size, size), labels[0], press[0], bgStyle)
            for (i in 1..3) {
                drawVibeCell(
                    ctx,
                    LayoutBounds(x + (i - 1) * (size + gap), y + size + gap, size, size),
                    labels[i], press[i], bgStyle,
                )
            }
            var yy = y + 2f * (size + gap)
            val l = leftClick.get()
            val r = rightClick.get()
            if (showMouse) {
                val cw = if (l && r) (w - gap) / 2f else w
                if (l) drawVibeCell(ctx, LayoutBounds(x, yy, cw, size), labels[4], press[4], bgStyle)
                if (r) drawVibeCell(
                    ctx,
                    LayoutBounds(if (l) x + cw + gap else x, yy, if (l) w - cw - gap else w, size),
                    labels[5], press[5], bgStyle,
                )
                yy += size + gap
            }
            if (showSpace.get()) {
                drawVibeCell(ctx, LayoutBounds(x, yy, w, size), labels[6], press[6], bgStyle)
            }

            HudSnap.drawGuides(ctx)
            ctx.popTransform()
            mt.flush(p.k)
        } finally {
            ctx.end()
        }
    }

    /** 单个方格：底色 + 按下高亮 + 居中键名，几何与配色照搬 Vibe 的 KeystrokesRenderer#cell */
    private fun drawVibeCell(ctx: GuiCanvas, b: LayoutBounds, label: String, amount: Float, bg: ShapeStyle) {
        if (glass.get()) {
            ctx.drawGlass(b, keyGlass)
        } else {
            ctx.drawShape(b, bg)
        }
        if (amount > 0.001f) {
            val hi = Color(
                ((pressedColor.value shr 16) and 0xFF) / 255f,
                ((pressedColor.value shr 8) and 0xFF) / 255f,
                (pressedColor.value and 0xFF) / 255f,
                amount,
            )
            ctx.drawShape(
                LayoutBounds(b.x + 1f, b.y + 1f, b.width - 2f, b.height - 2f),
                ShapeStyle(hi, min(cellRadius.value, (b.height - 2f) / 2f)),
            )
        }
        if (label.isEmpty()) return
        val base = b.height * 0.42f
        val maxW = b.width - 6f
        val labelW = mt.width(label, base, Theme.FONT_MEDIUM)
        val fit = if (labelW > 0f) min(textScale.value, maxW / labelW) else textScale.value
        val size = base * fit
        val tw = mt.width(label, size, Theme.FONT_MEDIUM)
        val color = Color(
            (0.94f * (1f - amount) + ((pressedTextColor.value shr 16) and 0xFF) / 255f * amount),
            (0.95f * (1f - amount) + ((pressedTextColor.value shr 8) and 0xFF) / 255f * amount),
            (0.98f * (1f - amount) + (pressedTextColor.value and 0xFF) / 255f * amount),
            1f,
        )
        if (shadow.get()) {
            mt.text(ctx, label, b.x + (b.width - tw) / 2f + 0.8f, b.y + (b.height - size * 0.73f) / 2f + 0.8f, size, Color(0f, 0f, 0f, 0.5f), Theme.FONT_MEDIUM)
        }
        mt.text(ctx, label, b.x + (b.width - tw) / 2f, b.y + (b.height - size * 0.73f) / 2f, size, color, Theme.FONT_MEDIUM)
    }

    companion object {
        val STYLE_NAMES = arrayOf("液态玻璃", "经典方格")

        @Volatile
        private var instance: KeystrokesHudModule? = null

        fun of(): KeystrokesHudModule? = instance
    }
}
