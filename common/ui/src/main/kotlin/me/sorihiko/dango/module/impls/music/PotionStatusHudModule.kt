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
import me.sorihiko.dango.ui.GlassStyle
import me.sorihiko.dango.ui.HudSnap
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.utils.Color

import kotlin.math.max
import kotlin.math.min

/**
 * 药水状态：把当前生效的药水效果排成一列，带原版效果图标、剩余时间和等级。
 *
 * 1.8.9 的药水数据在 legacy（PotionStatusRuntime）每 tick 读取一次，通过 [publish] 写入本模块；
 * 图标由 legacy 在 HUD 画完之后用原版 inventory.png 贴图画，本模块只负责面板、文字和图标位置。
 * 效果结束时 legacy 不再发布该条目，面板立即移除对应行（无残留）。
 */
@ModuleMeta(Category.MUSIC, "Potion Status")
class PotionStatusHudModule : Module() {

    @ModuleValue("液态玻璃", "液态玻璃背景；关闭后为纯黑面板")
    private val glass = BooleanValue(true)

    @ModuleValue("紧凑模式", "更小的行高与图标")
    private val compact = BooleanValue(false)

    @ModuleValue("缩放", "面板大小")
    private val scale = FloatValue(0.6f, 3f, 1f, 2)

    @ModuleValue("X 坐标", "面板位置（打开聊天栏后可拖动）")
    private val posX = FloatValue(0f, 1f, 0.015f, 3)

    @ModuleValue("Y 坐标", "面板位置（打开聊天栏后可拖动）")
    private val posY = FloatValue(0f, 1f, 0.16f, 3)

    private var context: GuiCanvas? = null
    private val mt = MixedText()

    /** 图标矩形缓冲，渲染线程独占，避免每帧分配 */
    private val rectBuffer = IntArray(MAX_ICONS * 4)

    private var dragging = false
    private var dragDX = 0f
    private var dragDY = 0f

    /** 面板样式：原来每帧 copy 一次 GlassStyle / 构造一次深色 ShapeStyle */
    private val panelGlass = Theme.GLASS_WINDOW.copy(
        radius = 10f,
        fallback = Color(0.40f, 0.42f, 0.46f, 0.82f),
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.18f), 8f, Vec2(0f, -2f)),
    )
    private val panelDark = ShapeStyle(
        Color(0.02f, 0.02f, 0.03f, 0.82f), 10f, 1f, Theme.HAIRLINE,
        ShadowStyle(Color(0f, 0f, 0f, 0.18f), 8f, Vec2(0f, -2f)),
    )
    /** 图标底：所有行共用 */
    private val iconBack = ShapeStyle(Theme.TRACK, 4f)

    override fun init() {
        instance = this
    }

    @EventHandler
    fun render(e: HudRenderEvent) {
        if (!enabled) {
            syncIcons(emptyArray(), 0f)
            return
        }
        val rows = entries
        if (rows.isEmpty()) {
            syncIcons(emptyArray(), 0f)
            return
        }
        val platform = DangoClient.platform
        val k = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f) * scale.value
        val lw = platform.windowWidth / k
        val lh = platform.windowHeight / k
        val mx = platform.mouseX / k
        val my = platform.mouseY / k

        val rowH = if (compact.get()) 18f else 22f
        val iconSize = if (compact.get()) 14f else 16f
        val padX = 8f
        val padY = 6f

        mt.clear()
        var nameW = 0f
        var durW = 0f
        for (r in rows) {
            nameW = max(nameW, mt.width(r.name, 10f, Theme.FONT_SEMIBOLD))
            durW = max(durW, mt.width(r.duration, 9f, Theme.FONT_REGULAR))
        }
        val w = (padX + iconSize + 8f + nameW + 14f + durW + padX).coerceAtLeast(96f)
        val h = padY * 2 + rowH * rows.size

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
                    "potion",
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
        HudSnap.register("potion", panel)
        // 复用矩形缓冲：HUD 每帧渲染，避免 200fps 下每帧新建数组
        val rects = rectBuffer
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
            // rectBuffer 只按 MAX_ICONS 分配：绘制与登记都要按上限收住，否则多效果时会越界中断整帧
            val shown = min(rows.size, MAX_ICONS)
            for (i in 0 until shown) {
                val row = rows[i]
                val top = panel.y + padY + rowH * i
                val icon = LayoutBounds(panel.x + padX, top + (rowH - iconSize) / 2f, iconSize, iconSize)
                ctx.drawShape(icon, iconBack)
                // 记录窗口像素矩形，legacy 在 HUD 之后把原版效果图标盖上去
                rects[i * 4] = (icon.x * k).toInt()
                rects[i * 4 + 1] = (icon.y * k).toInt()
                rects[i * 4 + 2] = (icon.width * k).toInt()
                rects[i * 4 + 3] = (icon.height * k).toInt()

                val tx = icon.right + 8f
                val ty = top + (rowH - 10f) / 2f + 1f
                mt.text(ctx, row.name, tx, ty, 10f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                val dw = mt.width(row.duration, 9f, Theme.FONT_REGULAR)
                mt.text(ctx, row.duration, panel.right - padX - dw, ty + 0.5f, 9f, Theme.TEXT_SECONDARY, Theme.FONT_REGULAR)
            }
            HudSnap.drawGuides(ctx)
            ctx.popTransform()
            mt.flush(k)
        } finally {
            ctx.end()
        }
        syncIcons(rows, k, rects)
    }

    /** 把图标位置与本帧缩放交给 legacy 的原版贴图叠加层 */
    private fun syncIcons(rows: Array<PotionRow>, k: Float, rects: IntArray? = null) {
        iconCount = rows.size
        iconScale = k
        iconRects = rects ?: IntArray(0)
        val n = min(rows.size, MAX_ICONS)
        for (i in 0 until n) iconIndices[i] = rows[i].icon
    }

    companion object {
        private const val MAX_ICONS = 24

        @Volatile
        private var instance: PotionStatusHudModule? = null

        fun of(): PotionStatusHudModule? = instance

        /** 当前生效的药水效果（legacy 每 tick 写入） */
        @Volatile
        @JvmStatic
        var entries: Array<PotionRow> = emptyArray()
            private set

        /** 图标矩形（窗口像素，x/y/w/h 四元组） */
        @Volatile
        @JvmStatic
        var iconRects: IntArray = IntArray(0)

        /** 原版效果图标序号（见 Potion#getStatusIconIndex） */
        @JvmField
        val iconIndices = IntArray(MAX_ICONS)

        @Volatile
        @JvmStatic
        var iconCount = 0

        @Volatile
        @JvmStatic
        var iconScale = 1f

        @JvmStatic
        fun publish(rows: Array<PotionRow>) {
            entries = rows
        }
    }
}

/** 一条药水状态：显示名（已带等级）、剩余时间、原版图标序号 */
class PotionRow(val name: String, val duration: String, val icon: Int)
