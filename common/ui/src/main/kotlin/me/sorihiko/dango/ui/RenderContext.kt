package me.sorihiko.dango.ui

import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.utils.Color

interface RenderContext {
    fun getMouseX(): Int
    fun getMouseY(): Int
    fun isMouseOver(bounds: LayoutBounds): Boolean
    fun drawShape(bounds: LayoutBounds, style: ShapeStyle = ShapeStyle())
    fun drawText(text: String, x: Float, y: Float, style: FontStyle)
    fun drawImage(bounds: LayoutBounds, image: Texture)

    /**
     * 贴图的一部分（UV 子区，0..1）。默认实现退化成整张贴图，
     * 1.8.9 的可视玻璃画布会用真实 UV 画玩家皮肤头像。
     */
    fun drawImageRegion(bounds: LayoutBounds, image: Texture, u0: Float, v0: Float, u1: Float, v1: Float) {
        drawImage(bounds, image)
    }

    /**
     * 液态玻璃面板：采样模糊后的背景，边缘带折射/色散/高光。
     * 没有可用背景时（例如 HUD 中）退化为半透明圆角矩形。
     */
    fun drawGlass(bounds: LayoutBounds, style: GlassStyle = GlassStyle())
    fun pushClip(bounds: LayoutBounds)
    fun popClip()
    fun pushOpacity(value: Float)
    fun popOpacity()
    fun getOpacity(): Float
    fun popTransform()
    fun pushTranslate(x: Float, y: Float, z: Float)
    fun pushScale(x: Float, y: Float, z: Float)
    fun pushRotate(angle: Float, x: Float, y: Float, z: Float)
}

data class ShapeStyle(
    val fillColor: Color = Color.TRANSPARENT,
    val radius: Float = 0f,
    val borderWidth: Float = 0f,
    val borderColor: Color = Color.TRANSPARENT,
    val shadow: ShadowStyle = ShadowStyle()
)

data class GlassStyle(
    val radius: Float = 0f,
    /** rgb = 染色, a = 染色强度 */
    val tint: Color = Color(1f, 1f, 1f, 0.06f),
    /** 背景亮度上限，保证白字在亮背景（天空/雪地）上也可读 */
    val maxLuma: Float = 0.58f,
    val saturation: Float = 1.35f,
    /** 0 = 清透, 1 = 磨砂 */
    val frost: Float = 0.6f,
    /** 边缘折射位移（逻辑像素） */
    val refraction: Float = 14f,
    /** 透镜斜面宽度（逻辑像素） */
    val bevel: Float = 16f,
    val dispersion: Float = 0.25f,
    val highlight: Float = 1f,
    val shadow: ShadowStyle = ShadowStyle(),
    /**
     * 没有背景可采样时的退化填充色。
     *
     * 必须是中性灰：深色兜底会让面板整块变黑，过亮的兜底（0.72+）配上白字会整块发白、
     * 文字看不清。0.40 左右的中灰既不像黑块，也能让白色文字保持对比度。
     */
    val fallback: Color = Color(0.40f, 0.42f, 0.46f, 0.82f),
)

data class FontStyle(
    /** 菜单字体文件名（[Theme.FONT_REGULAR] 等） */
    val font: String,
    val color: Color,
    val fontSize: Float,
)

data class ShadowStyle(
    val color: Color = Color.BLACK,
    val size: Float = 0f,
    val offset: Vec2 = Vec2(0f, 0f)
)

data class Vec2(val x: Float, val y: Float)
