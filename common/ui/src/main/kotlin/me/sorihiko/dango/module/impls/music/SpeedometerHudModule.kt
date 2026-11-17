package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.HudRenderEvent
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.ui.HudSnap
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color

import kotlin.math.max
import kotlin.math.min

/**
 * 速度计：实时显示水平移动速度（格/秒），并画出最近 4 秒的速度折线。
 *
 * 速度由 legacy 的 SpeedometerRuntime 每 tick 发布（player.motionX / motionZ × 20），
 * 本模块按 33ms 采样进环形缓冲，面板/数值每帧刷新，不持有任何游戏对象。
 */
@ModuleMeta(Category.MUSIC, "Speedometer")
class SpeedometerHudModule : Module() {

    @ModuleValue("液态玻璃", "液态玻璃背景；关闭后为纯黑面板")
    private val glass = BooleanValue(true)

    @ModuleValue("速度曲线", "显示最近 4 秒的速度曲线")
    private val graph = BooleanValue(true)

    @ModuleValue("仪表图标", "速度数值左侧的彩色指示块")
    private val icon = BooleanValue(true)

    @ModuleValue("缩放", "面板大小")
    private val scale = FloatValue(0.6f, 2.5f, 1f, 2)

    @ModuleValue("X 坐标", "面板位置（打开聊天栏后可拖动）")
    private val posX = FloatValue(0f, 1f, 0.015f, 3)

    @ModuleValue("Y 坐标", "面板位置（打开聊天栏后可拖动）")
    private val posY = FloatValue(0f, 1f, 0.34f, 3)

    private var context: GuiCanvas? = null
    private val mt = MixedText()

    private var dragging = false
    private var dragDX = 0f
    private var dragDY = 0f

    // 环形缓冲：120 个采样 × 33ms ≈ 4 秒
    private val samples = FloatArray(SAMPLES)
    private var sampleIndex = 0
    private var sampleCount = 0
    private var lastSampleNanos = 0L

    /** 面板液态玻璃 / 纯黑面板样式：原来每帧 copy 一次 GlassStyle（+ ShadowStyle + Vec2 + Color） */
    private val panelGlass = Theme.GLASS_WINDOW.copy(
        radius = 10f,
        fallback = Color(0.40f, 0.42f, 0.46f, 0.82f),
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.2f), 8f, Vec2(0f, -2f)),
    )
    private val panelDark = ShapeStyle(
        Color(0.02f, 0.02f, 0.03f, 0.82f), 10f, 1f, Theme.HAIRLINE,
        ShadowStyle(Color(0f, 0f, 0f, 0.18f), 8f, Vec2(0f, -2f)),
    )

    /** 三种速度状态对应的指示块颜色 */
    private val iconSprint = Color(0.18f, 0.82f, 0.35f, 1f)
    private val iconWalk = Theme.ACCENT
    private val iconIdle = Theme.TEXT_TERTIARY
    private val iconSprintStyle = ShapeStyle(iconSprint, 1.5f)
    private val iconWalkStyle = ShapeStyle(iconWalk, 1.5f)
    private val iconIdleStyle = ShapeStyle(iconIdle, 1.5f)
    /** 曲线底 */
    private val graphTrack = ShapeStyle(Theme.TRACK, 5f)

    /** 柱状图样式按 alpha 量化到 10 档复用：原来每根柱每帧 new 一个 Color + ShapeStyle */
    private val barStyles = arrayOfNulls<ShapeStyle>(10)

    private fun barStyle(alpha: Float): ShapeStyle {
        val idx = ((alpha - 0.30f) / 0.05f).toInt().coerceIn(0, 9)
        val cached = barStyles[idx]
        if (cached != null) return cached
        val style = ShapeStyle(Color(0.20f, 0.72f, 1f, 0.30f + (idx + 1) * 0.05f), 1f)
        barStyles[idx] = style
        return style
    }

    /** 速度数值文本：按两位小数量化缓存，避免每帧走一遍格式化解析 */
    private var valueKey = Int.MIN_VALUE
    private var valueText = "0.00"

    private fun valueTextOf(speed: Float): String {
        val key = (speed * 100f).toInt().coerceAtLeast(0)
        if (key != valueKey) {
            valueKey = key
            val whole = key / 100
            val frac = key % 100
            valueText = if (frac < 10) "$whole.0$frac" else "$whole.$frac"
        }
        return valueText
    }

    override fun init() {
        instance = this
    }

    @EventHandler
    fun render(e: HudRenderEvent) {
        if (!enabled) return
        val now = System.nanoTime()
        if (now - lastSampleNanos >= 33_000_000L) {
            lastSampleNanos = now
            samples[sampleIndex] = speedMps
            sampleIndex = (sampleIndex + 1) % SAMPLES
            if (sampleCount < SAMPLES) sampleCount++
        }

        val platform = DangoClient.platform
        val k = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f) * scale.value
        val lw = platform.windowWidth / k
        val lh = platform.windowHeight / k
        val mx = platform.mouseX / k
        val my = platform.mouseY / k

        val speed = speedMps
        val w = 196f
        val headH = 16f
        val graphH = if (graph.get()) 34f else 0f
        val h = 10f + headH + 22f + graphH + 8f

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
                    "speedometer",
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
        HudSnap.register("speedometer", panel)
        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), mx.toInt(), my.toInt())
        try {
            ctx.pushScale(k, k, 1f)
            if (glass.get()) {
                ctx.drawGlass(panel, panelGlass)
            } else {
                ctx.drawShape(panel, panelDark)
            }

            mt.clear()
            mt.clip = panel

            val labelY = panel.y + 8f
            mt.text(ctx, "SPEED", panel.x + 12f, labelY, 9f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            val state = if (speed >= 4.5f) "疾跑" else if (speed >= 1.5f) "移动" else "静止"
            val sw = mt.width(state, 9f, Theme.FONT_REGULAR)
            mt.text(ctx, state, panel.right - 12f - sw, labelY, 9f, Theme.TEXT_SECONDARY, Theme.FONT_REGULAR)

            val valueY = panel.y + 26f
            if (icon.get()) {
                val style = if (speed >= 4.5f) iconSprintStyle else if (speed >= 1.5f) iconWalkStyle else iconIdleStyle
                ctx.drawShape(LayoutBounds(panel.x + 12f, valueY + 2f, 3f, 14f), style)
            }
            val value = valueTextOf(speed)
            mt.text(ctx, value, panel.x + 24f, valueY - 2f, 20f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            val vw = mt.width(value, 20f, Theme.FONT_SEMIBOLD)
            mt.text(ctx, "m/s", panel.x + 24f + vw + 5f, valueY + 9f, 9f, Theme.TEXT_SECONDARY, Theme.FONT_REGULAR)

            if (graph.get()) {
                val gx = panel.x + 12f
                val gy = panel.y + 10f + headH + 22f
                val gw = panel.width - 24f
                val gh = graphH - 4f
                ctx.drawShape(LayoutBounds(gx, gy, gw, gh), graphTrack)
                // 一根柱对应一个 33ms 采样：按满 120 个采样画才是注释里的"最近 4 秒"
                val span = min(sampleCount, SAMPLES)
                if (span > 1) {
                    var peak = 1f
                    for (i in 0 until span) {
                        val idx = (sampleIndex - span + i + SAMPLES) % SAMPLES
                        peak = max(peak, samples[idx])
                    }
                    peak = max(peak, 3f)
                    val step = gw / SAMPLES
                    for (i in 0 until span) {
                        val idx = (sampleIndex - span + i + SAMPLES) % SAMPLES
                        val ratio = (samples[idx] / peak).coerceIn(0f, 1f)
                        val bh = (gh * ratio).coerceAtLeast(0.6f)
                        ctx.drawShape(
                            LayoutBounds(gx + i * step, gy + gh - bh, max(1.2f, step - 1.2f), bh),
                            barStyle(0.35f + 0.45f * ratio),
                        )
                    }
                }
            }
            HudSnap.drawGuides(ctx)
            ctx.popTransform()
            mt.flush(k)
        } finally {
            ctx.end()
        }
    }

    companion object {
        private const val SAMPLES = 120

        @Volatile
        private var instance: SpeedometerHudModule? = null

        fun of(): SpeedometerHudModule? = instance

        /** 当前水平速度（格/秒），legacy 每 tick 写入 */
        @Volatile
        @JvmStatic
        var speedMps = 0f
            private set

        @JvmStatic
        fun publish(speed: Float) {
            speedMps = speed
        }
    }
}
