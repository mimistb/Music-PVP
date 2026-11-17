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

/**
 * 装备显示（移植自 Lavender-rise 的 `ArmorHud` 模块）：
 * 竖排 / 横排展示头盔、胸甲、护腿、靴子的图标与剩余耐久。
 *
 * 物品图标由 legacy 的 [me.sorihiko.dango.legacy.LegacyHudOverlayRuntime] 用原版 RenderItem
 * 画在 [itemRects] 上；本模块只负责面板、格子、耐久文字的颜色与布局。
 */
@ModuleMeta(Category.MUSIC, "ArmorHud")
class ArmorHudModule : Module() {

    @ModuleValue("风格", "铠甲一（圆角）/ 铠甲二（直角）/ 铠甲三（横排）")
    private val style = IntValue(0, 2, 0)

    @ModuleValue("水平排列", "关掉为竖向列表；铠甲三固定横排")
    private val horizontal = BooleanValue(false)

    @ModuleValue("自适应", "护甲数量变化时面板平滑伸缩")
    private val selfAdapt = BooleanValue(true)

    @ModuleValue("标题", "面板顶部显示「护甲」")
    private val title = BooleanValue(true)

    @ModuleValue("液态玻璃", "液态玻璃背景；关闭后为深色面板")
    private val glass = BooleanValue(true)

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

    /** 4 件护甲的窗口像素矩形（x/y/w/h） */
    private val rectBuffer = IntArray(4 * 4)

    /** 自适应面板尺寸（逻辑像素），按指数逼近目标尺寸 */
    private var animW = -1f
    private var animH = -1f
    private var animAtNanos = 0L

    private val glassPanel = Theme.GLASS_WINDOW.copy(
        radius = 8f,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.2f), 8f, Vec2(0f, -2f)),
    )
    private val glassPanelSquare = Theme.GLASS_WINDOW.copy(
        radius = 1f,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.2f), 8f, Vec2(0f, -2f)),
    )
    private val darkPanel = ShapeStyle(
        Color(0.02f, 0.02f, 0.03f, 0.88f), 8f, 1f, Theme.HAIRLINE,
        ShadowStyle(Color(0f, 0f, 0f, 0.18f), 8f, Vec2(0f, -2f)),
    )
    private val darkPanelSquare = ShapeStyle(
        Color(0.02f, 0.02f, 0.03f, 0.88f), 1f, 1f, Theme.HAIRLINE,
        ShadowStyle(Color(0f, 0f, 0f, 0.18f), 8f, Vec2(0f, -2f)),
    )
    private val slotStyle = ShapeStyle(Color(0f, 0f, 0f, 0.36f), 3f, 0.8f, Color(1f, 1f, 1f, 0.09f))
    private val accentStyle = ShapeStyle(Color(0.04f, 0.52f, 1f, 0.72f), 1f)
    private val hairlineStyle = ShapeStyle(Theme.HAIRLINE, 0f)

    override fun init() {
        instance = this
    }

    private fun approach(current: Float, target: Float, dt: Float): Float {
        if (current < 0f || dt <= 0f) return target
        return current + (target - current) * (1f - exp(-16f * dt))
    }

    @EventHandler
    fun render(e: HudRenderEvent) {
        if (!enabled) {
            publishedCount = 0
            itemRects = EMPTY_RECTS
            return
        }
        val platform = DangoClient.platform
        val k = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f) * scale.value
        val lw = platform.windowWidth / k
        val lh = platform.windowHeight / k
        val mx = platform.mouseX / k
        val my = platform.mouseY / k

        val styleIndex = style.value
        val isHorizontal = horizontal.get() || styleIndex == 2
        val count = armorCount.coerceIn(0, 4)
        val showTitle = title.get() && !isHorizontal

        val pad = 5f
        val itemSize = 16f
        val pitch = 17f
        val header = if (showTitle) 14f else 0f
        val showDurability = !isHorizontal && count > 0

        // 目标尺寸
        var targetW: Float
        var targetH: Float
        if (count == 0) {
            targetW = 48f
            targetH = pad * 2 + header + 10f
        } else if (isHorizontal) {
            targetW = pad * 2 + count * pitch - (pitch - itemSize)
            targetH = pad * 2 + itemSize
        } else {
            targetW = if (showDurability) 46f else 30f
            targetH = pad * 2 + header + count * pitch - (pitch - itemSize)
        }
        if (targetW < 30f) targetW = 30f
        if (targetH < 20f) targetH = 20f

        val now = System.nanoTime()
        val dt = if (animAtNanos == 0L) 0f else ((now - animAtNanos) / 1e9f).coerceIn(0f, 0.1f)
        animAtNanos = now
        if (selfAdapt.get()) {
            animW = approach(animW, targetW, dt)
            animH = approach(animH, targetH, dt)
        } else {
            animW = targetW
            animH = targetH
        }
        val w = animW
        val h = animH

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
                    "armorhud",
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
        HudSnap.register("armorhud", panel)
        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        val rects = rectBuffer
        ctx.begin(glass.get(), mx.toInt(), my.toInt())
        try {
            ctx.pushScale(k, k, 1f)
            if (glass.get()) {
                ctx.drawGlass(panel, if (styleIndex == 1) glassPanelSquare else glassPanel)
            } else {
                ctx.drawShape(panel, if (styleIndex == 1) darkPanelSquare else darkPanel)
            }
            if (styleIndex == 1) {
                // 铠甲二：左侧竖向强调条
                ctx.drawShape(LayoutBounds(panel.x, panel.y + 1f, 2.5f, panel.height - 2f), accentStyle)
            }

            mt.clear()
            mt.clip = panel
            if (showTitle) {
                mt.text(ctx, "护甲", panel.x + pad + 1f, panel.y + pad - 1f, 9f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                ctx.drawShape(
                    LayoutBounds(panel.x + pad, panel.y + pad + header - 4f, panel.width - pad * 2, 0.6f),
                    hairlineStyle,
                )
            }
            if (count == 0) {
                mt.text(ctx, "Empty..", panel.x + pad + 1f, panel.y + pad + header, 9f, Theme.TEXT_TERTIARY)
            }

            val gx = panel.x + (if (styleIndex == 1) pad + 3f else pad + 1f)
            val gy = panel.y + pad + header
            for (i in 0 until count) {
                val sx = if (isHorizontal) gx + i * pitch - 1f else gx
                val sy = if (isHorizontal) panel.y + (panel.height - itemSize) * 0.5f else gy + i * pitch
                ctx.drawShape(LayoutBounds(sx, sy, itemSize, itemSize), slotStyle)
                rects[i * 4] = (sx * k).toInt()
                rects[i * 4 + 1] = (sy * k).toInt()
                rects[i * 4 + 2] = (itemSize * k).toInt()
                rects[i * 4 + 3] = (itemSize * k).toInt()
            }
            HudSnap.drawGuides(ctx)
            ctx.popTransform()
            mt.flush(k)
        } finally {
            ctx.end()
        }

        publishedCount = count
        publishedDurability = showDurability
        itemRects = if (count > 0) rects else EMPTY_RECTS
    }

    companion object {
        private val EMPTY_RECTS = IntArray(0)
        @Volatile
        private var instance: ArmorHudModule? = null

        fun of(): ArmorHudModule? = instance

        /** 当前護甲件数（由 legacy 叠加层每帧写入，HUD 下一帧读取） */
        @Volatile
        @JvmStatic
        var armorCount = 0

        /** 本帧 4 件护甲的窗口像素矩形，按头盔→靴子的顺序排列 */
        @Volatile
        @JvmStatic
        var itemRects: IntArray = IntArray(0)

        @Volatile
        @JvmStatic
        var publishedCount = 0

        /** 竖排时显示剩余耐久数字 */
        @Volatile
        @JvmStatic
        var publishedDurability = false
    }
}
