package me.sorihiko.dango.ui

import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.legacy.LegacyGl
import me.sorihiko.dango.legacy.LegacyTexture
import me.sorihiko.dango.legacy.mc
import me.sorihiko.dango.shader.impls.ui.FontShader
import me.sorihiko.dango.shader.impls.ui.GlassShader
import me.sorihiko.dango.shader.impls.ui.ImageShader
import me.sorihiko.dango.shader.impls.ui.ShapeShader
import me.sorihiko.dango.ui.glass.GlassBackdrop
import me.sorihiko.dango.ui.glass.GlassBackdropPolicy
import me.sorihiko.dango.utils.Color
import me.sorihiko.dango.utils.render.GlStateSnapshot

import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.util.vector.Matrix4f
import org.lwjgl.util.vector.Vector3f
import org.lwjgl.util.vector.Vector4f

import kotlin.math.abs

/** 1.8.9 的液态玻璃画布：直接用 OpenGL 2 + 自己的着色器画 */
class GlassRenderContext : GuiCanvas {
    private val shapeShader = LegacyGl.shaderManager.get<ShapeShader>()
    private val fontShader = LegacyGl.shaderManager.get<FontShader>()
    private val imageShader = LegacyGl.shaderManager.get<ImageShader>()
    private val glassShader = LegacyGl.shaderManager.get<GlassShader>()

    /** 全进程共享的背景抓取：每个画布 `begin(captureBackdrop)` 原来都会各抓一次整屏 */
    private val backdrop: GlassBackdrop get() = GlassBackdrop.shared()
    /** 复用同一份快照对象：HUD 每帧有十来个画布，每帧 new 一次会白白制造垃圾 */
    private val snapshot = GlStateSnapshot()

    /** 本帧是否需要液态玻璃模糊（`begin()` 时读一次设置，`drawGlass` 不再逐块查询） */
    private var glassBlur = true

    /** 是否处于 begin()/end() 之间：未配对时就恢复快照会把过期状态写回去 */
    private var active = false

    private val clipStack = ArrayDeque<LayoutBounds>()
    private val opacityStack = ArrayDeque<Float>()
    private val transformStack = ArrayDeque<Matrix4f>()

    private var _mouseX: Int = 0
    private var _mouseY: Int = 0

    /** 没有变换时的单位矩阵：每帧几百次绘制，避免每次 new 一个 Matrix4f */
    private val identity = Matrix4f()

    /** 单位轴向量：pushRotate 绝大多数调用都是绕 Z 轴（HUD / 图标），复用避免每帧 new Vector3f */
    private val axisX = Vector3f(1f, 0f, 0f)
    private val axisY = Vector3f(0f, 1f, 0f)
    private val axisZ = Vector3f(0f, 0f, 1f)
    private val scratchAxis = Vector3f()

    /** transformBounds 的复用角点（Matrix4f.transform 支持原地写） */
    private val cornerA = Vector4f()
    private val cornerB = Vector4f()

    /**
     * drawGlass 阴影 / fallback 通道的 ShapeStyle 缓存（4 槽）。
     * 这两条通道每条玻璃每帧都会走一次，原来每次 new 一个 ShapeStyle（fallback 还要再 new 一个 Color）。
     * 键是半径 + 阴影 / 兜底色，样式对象本身不可变，命中即可安全复用。
     */
    private val glassStyleRadius = FloatArray(4)
    private val glassStyleKey = arrayOfNulls<Any>(4)
    private val glassStyleValue = arrayOfNulls<ShapeStyle>(4)
    private val glassStyleKind = IntArray(4)
    private var glassStyleSlot = 0
    private val fallbackBorder = Color(1f, 1f, 1f, 0.10f)

    /** kind: 0 = 阴影通道, 1 = fallback 填充通道 */
    private fun cachedGlassShape(kind: Int, radius: Float, key: Any): ShapeStyle? {
        for (i in 0 until 4) {
            val v = glassStyleValue[i] ?: continue
            if (glassStyleKind[i] == kind && glassStyleRadius[i] == radius && glassStyleKey[i] === key) return v
        }
        return null
    }

    private fun storeGlassShape(kind: Int, radius: Float, key: Any, style: ShapeStyle) {
        val slot = glassStyleSlot
        glassStyleSlot = (slot + 1) and 3
        glassStyleKind[slot] = kind
        glassStyleRadius[slot] = radius
        glassStyleKey[slot] = key
        glassStyleValue[slot] = style
    }

    /** 屏幕尺寸（着色器 uniform 用）：只在窗口大小变化时重建 */
    private var screenW = -1f
    private var screenH = -1f
    private var screenSizeValue = Size(0f, 0f)

    private fun screenSize(): Size {
        val w = mc.displayWidth.toFloat()
        val h = mc.displayHeight.toFloat()
        if (w != screenW || h != screenH) {
            screenW = w
            screenH = h
            screenSizeValue = Size(w, h)
        }
        return screenSizeValue
    }

    override fun begin(captureBackdrop: Boolean, mouseX: Int, mouseY: Int) {
        snapshot.capture()
        active = true
        // 画布是长生命周期对象：上一帧若在 push/pop 之间抛过异常，残留的裁剪 / 透明度 / 变换
        // 会跨帧累积，最终让 pop 在空栈上抛 NoSuchElementException。每帧开始强制归零。
        clipStack.clear()
        opacityStack.clear()
        transformStack.clear()
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL20.glUseProgram(0)
        // 聊天 / 其它界面或光影可能把 colorMask 留在关闭状态，玻璃会整块不可见或发黑
        GlStateManager.colorMask(true, true, true, true)
        if (captureBackdrop) {
            // 本帧有画布要用玻璃背景：下一帧帧首才会在「原版 HUD 之前」抓屏
            backdrop.noteUsed()
            // 稳态下背景只在帧首（Pre(ALL)）抓一次；这里只在还没有任何可用纹理
            // （刚进世界 / 刚改窗口大小 / 主菜单）时补抓。世界渲染与离屏 FBO 阶段
            // 画面尚未合成，captureIfIdle 会直接拒绝，避免把上一帧的聊天黑底 /
            // ESC 黑渐变当成背景（玻璃整块变黑的来源）。
            if (!backdrop.ready) backdrop.captureIfIdle()
        }
        // 每帧只读一次模块设置，避免每个玻璃面板都走一遍模块查询
        glassBlur = try {
            me.sorihiko.dango.module.impls.music.NeteaseMusicModule.of()?.liquidBlur?.get() ?: true
        } catch (_: Throwable) {
            true
        }

        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_CULL_FACE)
        _mouseX = mouseX
        _mouseY = mouseY
    }

    override fun end() {
        // 没有配对 begin() 时不能恢复快照：那是上一帧（甚至更早）的状态，
        // 写回去会让手部 / HUD 的纹理、深度、颜色全部错位
        if (!active) return
        active = false
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        snapshot.restore()
    }

    override fun scissor(x: Int, y: Int, width: Int, height: Int) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor(x, y, width, height)
    }

    override fun noScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
    }

    override fun getMouseX(): Int {
        return _mouseX
    }

    override fun getMouseY(): Int {
        return _mouseY
    }

    override fun isMouseOver(bounds: LayoutBounds): Boolean {
        return bounds.contains(_mouseX, _mouseY)
    }

    override fun drawShape(bounds: LayoutBounds, style: ShapeStyle) {
        shapeShader.bind()
        shapeShader.setup(
            getTransform(),
            screenSize(),
            bounds,
            style,
            getOpacity()
        )
        val renderPadding =
            style.borderWidth + style.shadow.size + maxOf(abs(style.shadow.offset.x), abs(style.shadow.offset.y))

        val x1 = bounds.x - renderPadding
        val y1 = bounds.y - renderPadding
        val x2 = bounds.x + bounds.width + renderPadding
        val y2 = bounds.y + bounds.height + renderPadding

        GL11.glBegin(GL11.GL_QUADS)

        GL11.glVertex2f(x1, y1)
        GL11.glVertex2f(x1, y2)
        GL11.glVertex2f(x2, y2)
        GL11.glVertex2f(x2, y1)

        GL11.glEnd()

        shapeShader.unbind()
    }

    override fun drawText(text: String, x: Float, y: Float, style: FontStyle) {
        val uiFont = LegacyGl.font(style.font)

        fontShader.bind()
        fontShader.setup(
            getTransform(),
            screenSize(),
            style.color,
            getOpacity()
        )
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit)
        bindTextureSynced(uiFont.textureId)

        val scale = style.fontSize / uiFont.fontSize
        // 设备像素对齐：HUD 的 uiScale 通常不是整数（比如 1.25），字形起点落在
        // 半像素上时双线性采样会把整段文字糊掉。把起点吸附到设备像素网格再画，
        // 字距与字号保持不变，只消除亚像素采样带来的模糊。
        val m = getTransform()
        val dsx = m.m00
        val dsy = m.m11
        var curX = if (dsx > 0f) Math.round(x * dsx) / dsx else x
        val topY = if (dsy > 0f) Math.round(y * dsy) / dsy else y
        GL11.glBegin(GL11.GL_QUADS)
        for (c in text) {
            val glyph = uiFont.getGlyph(c) ?: continue

            val x0 = curX + glyph.bearingX * scale
            val y0 = topY - glyph.bearingY * scale
            val x1 = x0 + glyph.width * scale
            val y1 = y0 + glyph.height * scale

            GL11.glTexCoord2f(glyph.u0, glyph.v0)
            GL11.glVertex2f(x0, y0)
            GL11.glTexCoord2f(glyph.u0, glyph.v1)
            GL11.glVertex2f(x0, y1)
            GL11.glTexCoord2f(glyph.u1, glyph.v1)
            GL11.glVertex2f(x1, y1)
            GL11.glTexCoord2f(glyph.u1, glyph.v0)
            GL11.glVertex2f(x1, y0)

            curX += glyph.advance * scale
        }

        GL11.glEnd()
        fontShader.unbind()
    }

    override fun drawImage(bounds: LayoutBounds, image: Texture) {
        drawImageRegion(bounds, image, 0f, 0f, 1f, 1f)
    }

    override fun drawImageRegion(bounds: LayoutBounds, image: Texture, u0: Float, v0: Float, u1: Float, v1: Float) {
        imageShader.bind()
        imageShader.setup(
            getTransform(),
            screenSize(),
            getOpacity()
        )
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit)
        (image as LegacyTexture).bind()

        GL11.glBegin(GL11.GL_QUADS)
        GL11.glTexCoord2f(u0, v0)
        GL11.glVertex2f(bounds.x, bounds.y)
        GL11.glTexCoord2f(u0, v1)
        GL11.glVertex2f(bounds.x, bounds.bottom)
        GL11.glTexCoord2f(u1, v1)
        GL11.glVertex2f(bounds.right, bounds.bottom)
        GL11.glTexCoord2f(u1, v0)
        GL11.glVertex2f(bounds.right, bounds.y)
        GL11.glEnd()

        imageShader.unbind()
    }

    override fun drawGlass(bounds: LayoutBounds, style: GlassStyle) {
        if (style.shadow.size > 0f) {
            val shadow = style.shadow
            val shadowStyle = cachedGlassShape(0, style.radius, shadow)
                ?: ShapeStyle(Color.TRANSPARENT, style.radius, shadow = shadow)
                    .also { storeGlassShape(0, style.radius, shadow, it) }
            drawShape(bounds, shadowStyle)
        }

        val backdrop = this.backdrop
        // 背景没抓过 / 纹理已被释放（离开世界）时必须退回 fallback 填充：
        // 采样无效纹理按 OpenGL 规定恒返回 (0,0,0)，玻璃面板会整块变成纯黑
        if (!glassBlur ||
            !GlassBackdropPolicy.canSample(backdrop.ready, backdrop.softTexture, backdrop.frostTexture)
        ) {
            val fallback = style.fallback
            val fallbackStyle = cachedGlassShape(1, style.radius, fallback)
                ?: ShapeStyle(fallback, style.radius, 1f, fallbackBorder)
                    .also { storeGlassShape(1, style.radius, fallback, it) }
            drawShape(bounds, fallbackStyle)
            return
        }

        glassShader.bind()
        glassShader.setup(
            getTransform(),
            screenSize(),
            bounds,
            style,
            getOpacity()
        )

        // 1 号纹理单元就是原版的光照贴图单元。原来这里用裸 GL 绑 frost、结束时又绑 0，
        // GlStateManager 的纹理缓存不会跟着变：原版随后 bindTexture(lightmap) 会因为
        // "缓存认为已经绑着" 被跳过 → 光照贴图彻底丢失，世界 / 手部 / 玻璃背景全黑。
        // 所以切单元走 GlStateManager，绑定同时写缓存与真实 GL，结束时原样还回去。
        val previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit)
        val previousFrost = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        bindTextureSynced(backdrop.frostTexture)
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit)
        val previousSoft = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        bindTextureSynced(backdrop.softTexture)
        try {
            val x1 = bounds.x - 1f
            val y1 = bounds.y - 1f
            val x2 = bounds.right + 1f
            val y2 = bounds.bottom + 1f

            GL11.glBegin(GL11.GL_QUADS)
            GL11.glVertex2f(x1, y1)
            GL11.glVertex2f(x1, y2)
            GL11.glVertex2f(x2, y2)
            GL11.glVertex2f(x2, y1)
            GL11.glEnd()
        } finally {
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit)
            bindTextureSynced(previousFrost)
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit)
            bindTextureSynced(previousSoft)
            GlStateManager.setActiveTexture(previousUnit)
            glassShader.unbind()
        }
    }

    /** 同时写 GlStateManager 缓存与真实 GL 绑定：只设一边都会让原版后续的绑定被跳过 */
    private fun bindTextureSynced(texture: Int) {
        GlStateManager.bindTexture(texture)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
    }

    override fun pushClip(bounds: LayoutBounds) {
        val transformed = transformBounds(bounds)

        val clip = if (clipStack.isEmpty()) {
            transformed
        } else {
            clipStack.last().intersect(transformed)
        }

        clipStack.addLast(clip)

        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        applyScissor(clip)
    }

    override fun popClip() {
        clipStack.removeLastOrNull()

        if (clipStack.isEmpty()) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
        } else {
            applyScissor(clipStack.last())
        }
    }

    override fun pushOpacity(value: Float) {
        val value = value.coerceIn(0f, 1f)
        opacityStack.addLast(getOpacity() * value)
    }

    override fun popOpacity() {
        opacityStack.removeLastOrNull()
    }

    override fun getOpacity(): Float {
        return opacityStack.lastOrNull() ?: 1f
    }

    private fun pushTransform(transform: Matrix4f) {
        val result = Matrix4f.mul(getTransform(), transform, null)
        transformStack.addLast(result)
    }

    override fun popTransform() {
        transformStack.removeLastOrNull()
    }

    private fun getTransform(): Matrix4f {
        return transformStack.lastOrNull() ?: identity
    }

    override fun pushTranslate(x: Float, y: Float, z: Float) {
        // 新建矩阵是单位阵，平移等价于直接写第 4 列：省掉每次一个 Vector3f
        val matrix = Matrix4f()
        matrix.m30 = x
        matrix.m31 = y
        matrix.m32 = z
        pushTransform(matrix)
    }

    override fun pushScale(x: Float, y: Float, z: Float) {
        // 单位阵上的缩放等价于直接写对角线：省掉每次一个 Vector3f
        val matrix = Matrix4f()
        matrix.m00 = x
        matrix.m11 = y
        matrix.m22 = z
        pushTransform(matrix)
    }

    override fun pushRotate(angle: Float, x: Float, y: Float, z: Float) {
        val axis = when {
            x == 1f && y == 0f && z == 0f -> axisX
            x == 0f && y == 1f && z == 0f -> axisY
            x == 0f && y == 0f && z == 1f -> axisZ
            else -> {
                scratchAxis.set(x, y, z)
                scratchAxis
            }
        }
        val matrix = Matrix4f()
        matrix.rotate(
            Math.toRadians(angle.toDouble()).toFloat(),
            axis
        )
        pushTransform(matrix)
    }

    private fun applyScissor(bounds: LayoutBounds) {
        GL11.glScissor(
            bounds.x.toInt(),
            (mc.displayHeight - bounds.y - bounds.height).toInt(),
            bounds.width.toInt(),
            bounds.height.toInt()
        )
    }

    // 同时变换两个角，缩放变换下裁剪区域也正确
    private fun transformBounds(bounds: LayoutBounds): LayoutBounds {
        val matrix = getTransform()
        cornerA.set(bounds.x, bounds.y, 0f, 1f)
        cornerB.set(bounds.right, bounds.bottom, 0f, 1f)
        Matrix4f.transform(matrix, cornerA, cornerA)
        Matrix4f.transform(matrix, cornerB, cornerB)
        val left = minOf(cornerA.x, cornerB.x)
        val top = minOf(cornerA.y, cornerB.y)
        return LayoutBounds(left, top, maxOf(cornerA.x, cornerB.x) - left, maxOf(cornerA.y, cornerB.y) - top)
    }
}