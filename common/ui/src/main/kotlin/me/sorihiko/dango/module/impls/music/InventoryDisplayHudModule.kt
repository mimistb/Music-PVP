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

import kotlin.math.min

/**
 * 背包显示：常驻在 HUD 上的 9 × 4 格背包（含快捷栏），显示物品与数量。
 *
 * 物品图标无法跨版本抽象，所以本模块只负责玻璃面板与格子背景，并把每格的窗口像素矩形
 * 写入 [slotRects]；legacy 的 InventoryHudRuntime 在 HUD 之后用原版 RenderItem 把物品画在格子上，
 * 数量、耐久等也由原版叠加层绘制。格子数据每 tick 更新，物品数量变化实时反映。
 * 打开聊天栏后拖动面板即可改位置。
 */
@ModuleMeta(Category.MUSIC, "Inventory")
class InventoryDisplayHudModule : Module() {

    @ModuleValue("液态玻璃", "液态玻璃背景；关闭后为纯黑面板")
    private val glass = BooleanValue(true)

    @ModuleValue("标题", "面板顶部显示「背包」")
    private val title = BooleanValue(true)

    @ModuleValue("缩放", "面板大小")
    private val scale = FloatValue(0.6f, 2.5f, 1f, 2)

    @ModuleValue("X 坐标", "面板位置（打开聊天栏后可拖动）")
    private val posX = FloatValue(0f, 1f, 0.015f, 3)

    @ModuleValue("Y 坐标", "面板位置（打开聊天栏后可拖动）")
    private val posY = FloatValue(0f, 1f, 0.56f, 3)

    private var context: GuiCanvas? = null
    private val mt = MixedText()

    /** 36 格矩形缓冲，渲染线程独占，避免每帧分配 */
    private val rectBuffer = IntArray(36 * 4)

    private var dragging = false
    private var dragDX = 0f
    private var dragDY = 0f

    /** 面板样式：原来每帧 copy 一次 GlassStyle / 构造一次深色 ShapeStyle */
    private val panelGlass = Theme.GLASS_WINDOW.copy(
        radius = 10f,
        fallback = Color(0.40f, 0.42f, 0.46f, 0.82f),
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.2f), 8f, Vec2(0f, -2f)),
    )
    private val panelDark = ShapeStyle(
        Color(0.02f, 0.02f, 0.03f, 0.82f), 10f, 1f, Theme.HAIRLINE,
        ShadowStyle(Color(0f, 0f, 0f, 0.18f), 8f, Vec2(0f, -2f)),
    )
    /** 36 格共用同一个槽位样式 */
    private val slotStyle = ShapeStyle(Theme.TRACK, 4f)
    private val hairlineStyle = ShapeStyle(Theme.HAIRLINE, 0f)

    override fun init() {
        instance = this
    }

    @EventHandler
    fun render(e: HudRenderEvent) {
        if (!enabled) {
            slotCount = 0
            return
        }
        val platform = DangoClient.platform
        val k = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f) * scale.value
        val lw = platform.windowWidth / k
        val lh = platform.windowHeight / k
        val mx = platform.mouseX / k
        val my = platform.mouseY / k

        val cols = 9
        val rows = 4
        val slot = 18f
        val gap = 2f
        val pad = 8f
        val headH = if (title.get()) 20f else 0f
        val gridW = cols * slot + (cols - 1) * gap
        val gridH = rows * slot + (rows - 1) * gap
        val w = gridW + pad * 2
        val h = gridH + pad * 2 + headH

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
                    "inventory",
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
        HudSnap.register("inventory", panel)
        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        val rects = rectBuffer
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
            if (title.get()) {
                mt.text(ctx, "背包", panel.x + pad + 1f, panel.y + 5f, 10f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                val sub = "9 × 4"
                val sw = mt.width(sub, 8f, Theme.FONT_REGULAR)
                mt.text(ctx, sub, panel.right - pad - sw, panel.y + 6f, 8f, Theme.TEXT_TERTIARY, Theme.FONT_REGULAR)
                ctx.drawShape(LayoutBounds(panel.x + pad, panel.y + headH - 3f, panel.width - pad * 2, 0.6f), hairlineStyle)
            }

            val gx = panel.x + pad
            val gy = panel.y + pad + headH
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    val sx = gx + c * (slot + gap)
                    val sy = gy + r * (slot + gap)
                    ctx.drawShape(LayoutBounds(sx, sy, slot, slot), slotStyle)
                    rects[i * 4] = (sx * k).toInt()
                    rects[i * 4 + 1] = (sy * k).toInt()
                    rects[i * 4 + 2] = (slot * k).toInt()
                    rects[i * 4 + 3] = (slot * k).toInt()
                }
            }
            // 快捷栏与主背包之间画一条细分隔线
            ctx.drawShape(
                LayoutBounds(gx, gy + 3 * (slot + gap) - gap - 1f, gridW, 0.6f),
                hairlineStyle,
            )
            HudSnap.drawGuides(ctx)
            ctx.popTransform()
            mt.flush(k)
        } finally {
            ctx.end()
        }
        slotCount = 36
        slotScale = k
        slotRects = rects
    }

    companion object {
        @Volatile
        private var instance: InventoryDisplayHudModule? = null

        fun of(): InventoryDisplayHudModule? = instance

        /** 36 个格子的窗口像素矩形（x/y/w/h） */
        @Volatile
        @JvmStatic
        var slotRects: IntArray = IntArray(0)

        @Volatile
        @JvmStatic
        var slotCount = 0

        @Volatile
        @JvmStatic
        var slotScale = 1f
    }
}
