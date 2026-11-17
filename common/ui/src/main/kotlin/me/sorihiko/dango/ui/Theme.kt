package me.sorihiko.dango.ui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.utils.Color

/**
 * 液态玻璃主题：颜色、字体、玻璃材质预设，以及常用绘制辅助。
 * 所有尺寸都是"逻辑像素"，整个菜单会按屏幕大小统一缩放。
 */
object Theme {
    // ---- 布局 ----
    const val MENU_WIDTH = 880f
    const val MENU_HEIGHT = 560f

    // ---- HUD：拖动吸附与安全区（逻辑像素，播放器与 HUD 共用同一套刻度） ----
    /** 拖动吸附的基准网格 */
    const val HUD_GRID = 8f
    /** 拖动时与屏幕边缘保留的最小边距 */
    const val HUD_SAFE = 8f

    // ---- 页面标题区（所有页面共用同一套标题节奏） ----
    /** 页面主标题字号 */
    const val PAGE_TITLE_SIZE = 22f
    /** 副标题相对主标题顶部的偏移 */
    const val PAGE_SUB_Y = 30f
    /** 标题块整体高度：内容区统一从这条线开始 */
    const val PAGE_HEADER_H = 54f

    // ---- 字体 ----
    const val FONT_REGULAR = "Inter-Regular-cut.ttf"
    const val FONT_MEDIUM = "Inter-Medium-cut.ttf"
    const val FONT_SEMIBOLD = "Inter-SemiBold-cut.ttf"
    const val FONT_DISPLAY = "InterDisplay-Bold-cut.ttf"

    /** Inter 的大写字高 / 字号，用于文字垂直居中 */
    private const val CAP_HEIGHT = 0.727f

    // ---- 颜色（iOS 深色模式系统色） ----
    val TEXT_PRIMARY = Color(1f, 1f, 1f, 0.96f)
    val TEXT_SECONDARY = Color(0.92f, 0.92f, 0.96f, 0.62f)
    val TEXT_TERTIARY = Color(0.92f, 0.92f, 0.96f, 0.38f)

    val ACCENT = Color.fromHex("#0A84FF")
    val GREEN = Color.fromHex("#30D158")
    val SWITCH_OFF = Color(0.47f, 0.47f, 0.50f, 0.36f)
    val TRACK = Color(0.47f, 0.47f, 0.50f, 0.30f)

    val CARD = Color(1f, 1f, 1f, 0.055f)
    val CARD_HOVER = Color(1f, 1f, 1f, 0.085f)
    val HAIRLINE = Color(1f, 1f, 1f, 0.08f)
    val SEPARATOR = Color(1f, 1f, 1f, 0.07f)

    val ICON_DISABLED = Color(0.38f, 0.38f, 0.42f, 1f)

    // ---- 交互状态：悬停提亮、按下加深，全界面共用同一套反馈 ----
    val HAIRLINE_HOVER = Color(1f, 1f, 1f, 0.15f)
    val CARD_PRESS = Color(1f, 1f, 1f, 0.13f)
    /** 页面遮罩：让玻璃窗与背景拉开一层 */
    val OVERLAY = Color(0f, 0f, 0f, 0.22f)
    /** 加载占位块与流光 */
    val SKELETON = Color(1f, 1f, 1f, 0.045f)
    val SKELETON_SHINE = Color(1f, 1f, 1f, 0.07f)

    // ---- 间距梯度（4 的倍数，所有页面统一引用，避免散落魔数） ----
    const val SPACE_2 = 2f
    const val SPACE_4 = 4f
    const val SPACE_6 = 6f
    const val SPACE_8 = 8f
    const val SPACE_10 = 10f
    const val SPACE_12 = 12f
    const val SPACE_14 = 14f
    const val SPACE_16 = 16f
    const val SPACE_20 = 20f
    const val SPACE_24 = 24f
    const val SPACE_28 = 28f

    // ---- 圆角梯度 ----
    const val RADIUS_WINDOW = 30f
    const val RADIUS_CARD = 14f
    const val RADIUS_CONTROL = 10f
    const val RADIUS_CHIP = 8f

    // ---- 动效时长（毫秒）：快反馈 / 常规 / 慢速入场 ----
    const val DUR_FAST = 140
    const val DUR_BASE = 220
    const val DUR_SLOW = 320

    // ---- 玻璃材质：越靠前的层越亮 ----
    val GLASS_WINDOW = GlassStyle(
        radius = 30f,
        tint = Color(1f, 1f, 1f, 0.07f),
        maxLuma = 0.56f,
        saturation = 1.45f,
        frost = 0.85f,
        refraction = 22f,
        bevel = 26f,
        dispersion = 0.30f,
        highlight = 1f,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.30f), 36f, Vec2(0f, -10f)),
        fallback = Color(0.40f, 0.42f, 0.46f, 0.82f),
    )

    val GLASS_SIDEBAR = GlassStyle(
        radius = 22f,
        tint = Color(1f, 1f, 1f, 0.06f),
        maxLuma = 0.60f,
        saturation = 1.4f,
        frost = 0.55f,
        refraction = 16f,
        bevel = 18f,
        dispersion = 0.25f,
        highlight = 0.9f,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.18f), 18f, Vec2(0f, -4f)),
    )

    val GLASS_SELECTION = GlassStyle(
        radius = 12f,
        tint = Color(1f, 1f, 1f, 0.08f),
        maxLuma = 0.66f,
        saturation = 1.5f,
        frost = 0.2f,
        refraction = 10f,
        bevel = 12f,
        dispersion = 0.35f,
        highlight = 1.1f,
    )

    // ---- 绘制辅助 ----

    fun textWidth(text: String, size: Float, fontPath: String): Float =
        DangoClient.gfx.uiTextWidth(text, size, fontPath)

    /**
     * 以 centerY 为视觉中线时，MixedText 需要的 y 参数。
     * 所有"图标 / 按钮文字与控件中线对齐"的地方都走这里，避免各处各写一套偏移。
     */
    fun textCenterY(centerY: Float, size: Float): Float = centerY - size * 0.39f - 0.5f

    /** 以 centerY 为视觉中线绘制文字（按大写字高居中） */
    fun drawText(
        context: RenderContext,
        text: String,
        x: Float,
        centerY: Float,
        size: Float,
        fontPath: String,
        color: Color,
    ) {
        context.drawText(
            text,
            x,
            centerY + size * CAP_HEIGHT / 2f,
            FontStyle(fontPath, color, size)
        )
    }

    /** 细 chevron（">"），angle 为顺时针旋转角度 */
    fun drawChevron(context: RenderContext, cx: Float, cy: Float, size: Float, angle: Float, color: Color) {
        val thickness = size * 0.22f
        val length = size * 0.62f
        context.pushTranslate(cx, cy, 0f)
        context.pushRotate(angle, 0f, 0f, 1f)
        // 尖端在原点、两臂向左展开；右移让图形视觉居中
        context.pushTranslate(length * 0.35f, 0f, 0f)
        for (dir in intArrayOf(-1, 1)) {
            context.pushRotate(45f * dir, 0f, 0f, 1f)
            context.drawShape(
                LayoutBounds(-length + thickness / 2f, -thickness / 2f, length, thickness),
                ShapeStyle(color, thickness / 2f)
            )
            context.popTransform()
        }
        context.popTransform()
        context.popTransform()
        context.popTransform()
    }
}
