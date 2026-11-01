package me.sorihiko.dango.module.impls.music

import glidehud.api.Key
import glidehud.feature.KeystrokesState
import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.WorldRenderEndEvent
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.hud.HudData
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color

import kotlin.math.min

/**
 * 按键显示：W / A S D / 空格。
 * 取数来自 glidehud.feature.KeystrokesState（1.8.9 的按键由平台层通过 HudData 注入），
 * 视觉完全用本项目的液态玻璃面板与圆角方块，不使用 glide 的任何外观类。
 * 打开任意界面时按键自动视为松开（由 state 处理）。
 */
@ModuleMeta(Category.MUSIC, "Keystrokes")
class KeystrokesHudModule : Module() {

    @ModuleValue("液态玻璃", "液态玻璃背景；关闭后为纯黑面板")
    private val glass = BooleanValue(true)

    @ModuleValue("显示空格", "在按键面板里显示空格键")
    private val showSpace = BooleanValue(true)

    @ModuleValue("隐藏键名", "只显示按键盒，不显示绑定的键名")
    private val unmarked = BooleanValue(false)

    @ModuleValue("缩放", "面板大小")
    private val scale = FloatValue(0.6f, 10f, 1f, 2)

    @ModuleValue("X 坐标", "水平位置（打开聊天栏后可拖动）")
    private val posX = FloatValue(0f, 1f, 0.03f, 3)

    @ModuleValue("Y 坐标", "垂直位置（打开聊天栏后可拖动）")
    private val posY = FloatValue(0f, 1f, 0.55f, 3)

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

    private fun drawKey(ctx: GuiCanvas, key: Key, b: LayoutBounds, glassOn: Boolean) {
        val hi = state.highlight(key).coerceIn(0f, 1f)
        // 只有按键盒本身有液态玻璃（面板外围不再铺玻璃）
        if (glassOn) {
            ctx.drawGlass(b, keyGlass)
        } else {
            ctx.drawShape(b, ShapeStyle(dark, 6f, 1f, border))
        }
        if (hi > 0.02f) {
            fill.a = 0.22f * hi
            ctx.drawShape(b, ShapeStyle(fill, 6f))
            edge.a = hi
            ctx.drawShape(b, ShapeStyle(clear, 6f, 1f, edge))
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
    fun render(e: WorldRenderEndEvent) {
        if (!enabled) return
        state.keys = HudData.keys
        state.showSpace = showSpace.get()
        state.unmarked = unmarked.get()
        state.update()

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
                x = (mx - dragDX).coerceIn(0f, (lw - w).coerceAtLeast(0f))
                y = (my - dragDY).coerceIn(0f, (lh - h).coerceAtLeast(0f))
                posX.setValue(if (lw > w) x / (lw - w) else 0f)
                posY.setValue(if (lh > h) y / (lh - h) else 0f)
            }
        } else {
            dragging = false
        }

        val panel = LayoutBounds(x, y, w, h)
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

            ctx.popTransform()
            mt.flush(k)
        } finally {
            ctx.end()
        }
    }

    companion object {
        @Volatile
        private var instance: KeystrokesHudModule? = null

        fun of(): KeystrokesHudModule? = instance
    }
}
