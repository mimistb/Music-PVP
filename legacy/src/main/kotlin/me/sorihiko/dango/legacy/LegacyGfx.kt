package me.sorihiko.dango.legacy

import me.sorihiko.dango.font.CjkFont
import me.sorihiko.dango.gfx.CjkRun
import me.sorihiko.dango.gfx.Gfx
import me.sorihiko.dango.gfx.GlowPass
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.gfx.HeadTexture
import me.sorihiko.dango.gfx.HudCanvas
import me.sorihiko.dango.gfx.TextCanvas
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.gfx.Textures
import me.sorihiko.dango.gfx.WorldView
import me.sorihiko.dango.lyrics.DeepGlow
import me.sorihiko.dango.ui.GlassRenderContext
import me.sorihiko.dango.utils.render.GlCaps
import me.sorihiko.dango.utils.render.GlStateSnapshot

import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.renderer.Tessellator
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL20

import kotlin.math.max

/** 1.8.9 的绘图实现：OpenGL 固定管线 + 原版的 FontRenderer / TextureManager */
object LegacyGfx : Gfx {

    override val textures: Textures = LegacyTextures

    override fun newCanvas(): GuiCanvas = GlassRenderContext()

    /** 皮肤头像缓存：同一个目标不每帧新建包装（UV 查询只在贴图就绪前重试） */
    private var headLoc: ResourceLocation? = null
    private var headTex: LegacyHeadTexture? = null

    override fun headTexture(head: Any?): HeadTexture? {
        val loc = head as? ResourceLocation ?: return null
        var tex = this.headTex
        if (tex == null || this.headLoc != loc) {
            tex = LegacyHeadTexture(loc)
            this.headLoc = loc
            this.headTex = tex
        }
        return if (tex.refresh()) tex else null
    }

    override fun uiTextWidth(text: String, size: Float, font: String): Float =
        LegacyGl.font(font).getTextWidth(text, size)

    override fun cjkWidth(text: String, size: Float, style: Int): Float = CjkFont.width(text, size, style)

    override fun cjkPreload(c: Char, style: Int) {
        CjkFont.glyph(c, style)
    }

    override fun drawCjkRuns(runs: List<CjkRun>, scale: Float) {
        if (runs.isEmpty()) return
        val k = scale
        GL20.glUseProgram(0)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, mc.displayWidth.toDouble(), mc.displayHeight.toDouble(), 0.0, 1000.0, 3000.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glTranslatef(0f, 0f, -2000f)
        GL11.glScalef(k, k, 1f)

        // 全部直接设 GL 状态（不走 MC 的状态缓存，缓存可能和实际状态对不上），画完原样恢复
        val caps = GlCaps.save()
        GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit)
        GL11.glDisable(GL11.GL_TEXTURE_2D)                 // 关掉光照贴图那一层，否则字会被压暗
        GL13.glActiveTexture(OpenGlHelper.defaultTexUnit)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE)
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_LIGHTING)
        GL11.glDisable(GL11.GL_FOG)
        GL11.glDisable(GL11.GL_CULL_FACE)
        GL11.glEnable(GL11.GL_BLEND)
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL14.glBlendEquation(GL14.GL_FUNC_ADD)

        for (r in runs) {
            val c = r.clip
            if (c != null) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST)
                GL11.glScissor(
                    (c.x * k).toInt(),
                    (mc.displayHeight - c.bottom * k).toInt(),
                    max(0, (c.width * k).toInt()),
                    max(0, (c.height * k).toInt()),
                )
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST)
            }
            GL11.glColor4f(r.r, r.g, r.b, r.a.coerceIn(0f, 1f))
            if (r.centered) CjkFont.drawCentered(r.s, r.x, r.y, r.size, r.style)
            else CjkFont.draw(r.s, r.x, r.y, r.size, r.style)
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GlCaps.restore(caps)
        resetColorAndTexture()
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
    }

    override fun guarded(block: () -> Unit) {
        val snap = GlStateSnapshot()
        GL20.glUseProgram(0)
        try {
            block()
        } finally {
            snap.restore()
        }
    }

    override val text: TextCanvas = LegacyText
    override val world: WorldView = LegacyWorld
    override val glow: GlowPass = DeepGlow()
    override val hud: HudCanvas = LegacyHud

    /** 直接用 GL 绑过贴图 / 设过颜色之后，同步回 MC 的状态缓存，免得后面 MC 字体画错 */
    internal fun resetColorAndTexture() {
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GlStateManager.color(1f, 1f, 1f, 1f)
        GlStateManager.bindTexture(0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
    }

    /** 画文字前的通用状态（关光照贴图、雾、光照、背面剔除，开混合） */
    internal fun prepareTextState() {
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit)
        GlStateManager.disableTexture2D()
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit)
        GlStateManager.enableTexture2D()
        GlStateManager.disableLighting()
        GlStateManager.disableFog()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
        GlStateManager.disableCull()
    }
}

// ---------------------------------------------------------------- 歌词文字

object LegacyText : TextCanvas {
    override fun push() = GL11.glPushMatrix()
    override fun pop() = GL11.glPopMatrix()
    override fun translate(x: Float, y: Float) = GL11.glTranslatef(x, y, 0f)
    override fun rotate(degrees: Float) = GL11.glRotatef(degrees, 0f, 0f, 1f)
    override fun scale(x: Float, y: Float) = GL11.glScalef(x, y, 1f)

    override fun color(r: Float, g: Float, b: Float, a: Float) = GL11.glColor4f(r, g, b, a)

    override fun smoothText(text: String, x: Float, baseline: Float, size: Float, style: Int) =
        CjkFont.draw(text, x, baseline, size, style)

    override fun smoothWidth(text: String, size: Float, style: Int): Float = CjkFont.width(text, size, style)

    override fun beginSmooth() {
        // 平滑字体走固定管线，直接设 GL 状态
        GlStateManager.disableAlpha()
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE)
    }

    override fun endSmooth() = LegacyGfx.resetColorAndTexture()

    override fun pixelText(text: String, x: Float, y: Float, argb: Int, shadow: Boolean) {
        mc.fontRendererObj.drawString(text, x, y, argb, shadow)
    }

    override fun pixelWidth(text: String): Float = mc.fontRendererObj.getStringWidth(text).toFloat()

    override fun pixelCharWidth(c: Char): Float = mc.fontRendererObj.getCharWidth(c).toFloat()

    private var screenCaps: GlCaps.Saved? = null

    override fun beginScreen() {
        screenCaps = GlCaps.save()
        GL20.glUseProgram(0)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, mc.displayWidth.toDouble(), mc.displayHeight.toDouble(), 0.0, 1000.0, 3000.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glTranslatef(0f, 0f, -2000f)

        GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL13.glActiveTexture(OpenGlHelper.defaultTexUnit)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE)
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_LIGHTING)
        GL11.glDisable(GL11.GL_FOG)
        GL11.glDisable(GL11.GL_CULL_FACE)
        GL11.glEnable(GL11.GL_BLEND)
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL14.glBlendEquation(GL14.GL_FUNC_ADD)
    }

    override fun endScreen() {
        screenCaps?.let { GlCaps.restore(it) }
        screenCaps = null
        LegacyGfx.resetColorAndTexture()
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPopMatrix()
    }
}

// ---------------------------------------------------------------- 世界里的歌词

object LegacyWorld : WorldView {
    override val cameraX: Double get() = mc.renderManager.viewerPosX
    override val cameraY: Double get() = mc.renderManager.viewerPosY
    override val cameraZ: Double get() = mc.renderManager.viewerPosZ

    override fun begin() {
        LegacyGfx.prepareTextState()
        GlStateManager.disableDepth()
        GlStateManager.depthMask(false)
    }

    /**
     * 成对还原 [begin] 改过的状态。
     *
     * 世界文字画在 RenderWorldLast，同一帧紧接着就是原版 renderHand（第一人称手部）：
     * 深度测试关着、depthMask 为 false、光照与 lightmap 纹理单元被关掉的话，手部会
     * 无深度排序、双面、无光照地画出来 —— 表现就是一块纯黑剪影。
     */
    override fun end() {
        GlStateManager.depthMask(true)
        GlStateManager.enableDepth()
        GlStateManager.enableCull()
        GlStateManager.enableLighting()
        GlStateManager.enableFog()
        GlStateManager.disableBlend()
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
        // prepareTextState() 关掉了 lightmap 那一层的贴图，这里必须打开：
        // 物品 / 手部的亮度靠 GL_TEXTURE1 的 lightmap 采样
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit)
        GlStateManager.enableTexture2D()
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit)
        GlStateManager.enableTexture2D()
        LegacyGfx.resetColorAndTexture()
    }

    override fun push(rx: Double, ry: Double, rz: Double, yaw: Float, pitch: Float, scale: Float) {
        GL11.glPushMatrix()
        GL11.glTranslated(rx, ry, rz)
        GL11.glRotatef(-yaw, 0f, 1f, 0f)
        GL11.glRotatef(pitch, 1f, 0f, 0f)
        GL11.glScalef(-scale, -scale, scale)
    }

    override fun pop() = GL11.glPopMatrix()

    override fun resync() {
        // 发光是直接用 GL 画的，同步一下 MC 的状态缓存，免得后面 MC 字体画错
        GlStateManager.bindTexture(0)
        GlStateManager.color(1f, 1f, 1f, 1f)
        LegacyGfx.prepareTextState()
    }
}

// ---------------------------------------------------------------- 左上角 HUD

object LegacyHud : HudCanvas {
    /**
     * 矩形绘制批次。
     *
     * 原实现每个矩形都走一次 `Gui.drawRect`：内部各自 begin/draw 一次 Tessellator，
     * 期间反复开关混合、纹理，HUD 一帧几十个矩形就是几十次状态切换 + 几十次 draw call。
     * 这里把同一帧的矩形攒进缓冲，在需要切换绘制类型（文字 / 纹理）或帧结束时一次性提交，
     * 绘制顺序与颜色完全不变，因此画面无差异；只有「完全不透明且完全重合」的矩形会被去重
     * （不透明重复绘制是幂等的），半透明矩形一律保留，避免叠加浓度变化。
     */
    private const val QUADS = 7
    /**
     * 扁平矩形批次：每个矩形占 5 个 int（x1,y1,x2,y2,argb）。
     * 原实现是 `ArrayList<IntArray>`，HUD 一帧几十个矩形就是几十个小数组 + 一次扩容。
     */
    private var rects = IntArray(5 * 128)
    private var rectCount = 0

    /**
     * 「不透明矩形」去重表：开放寻址的原始类型表，key → argb。
     * 原来是 `HashMap<Long, Int>`，每次 put 都会把 Long / Int 装箱。
     * 坐标全 0 的矩形在 [rect] 里就被 x1 == x2 拦掉了，所以 key = 0 可以安全地当空槽。
     */
    private var seenKeys = LongArray(512)
    private var seenValues = IntArray(512)
    private var seenUsed = 0

    /** 上一帧的统计，供自检 / 调试读取 */
    @JvmStatic
    var lastFrameRects = 0
        private set
    @JvmStatic
    var lastFrameDrawn = 0
        private set
    @JvmStatic
    var lastFrameMerged = 0
        private set

    /** begin() 时的开关快照，end() 原样还原 */
    private var caps: GlCaps.Saved? = null

    override fun begin() {
        // 先记下进去前的开关：begin() 会关深度、改贴图单元，end() 必须原样还原，
        // 否则 HUD 画完之后的状态会漏给 GUI / 下一帧的世界渲染
        caps = GlCaps.save()
        val sr = ScaledResolution(mc)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, sr.scaledWidth_double, sr.scaledHeight_double, 0.0, 1000.0, 3000.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glTranslatef(0f, 0f, -2000f)
        LegacyGfx.prepareTextState()
        GlStateManager.disableDepth()
        rectCount = 0
        clearSeen()
        lastFrameRects = 0
        lastFrameDrawn = 0
        lastFrameMerged = 0
    }

    override fun end() {
        flushRects()
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPopMatrix()
        caps?.let { GlCaps.restore(it) }
        caps = null
    }

    override fun rect(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) {
        if (x1 == x2 || y1 == y2) return
        lastFrameRects++
        if ((argb ushr 24) == 0xFF) {
            val key = packKey(x1, y1, x2, y2)
            val prev = seenPut(key, argb)
            if (prev == argb) {
                lastFrameMerged++
                return
            }
        }
        addRect(x1, y1, x2, y2, argb)
    }

    private fun addRect(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) {
        val need = (rectCount + 1) * 5
        if (need > rects.size) rects = rects.copyOf(maxOf(need, rects.size * 2))
        val o = rectCount * 5
        rects[o] = x1
        rects[o + 1] = y1
        rects[o + 2] = x2
        rects[o + 3] = y2
        rects[o + 4] = argb
        rectCount++
    }

    /** 返回之前记录的 argb；0 表示这个 key 还没出现过（不透明色的 argb 不可能是 0） */
    private fun seenPut(key: Long, argb: Int): Int {
        // 装载因子到 3/4 就直接清空：去重只是省绘制，清掉不影响正确性，也避免 rehash 逻辑
        if (seenUsed * 4 >= seenKeys.size * 3) clearSeen()
        var i = (((key xor (key ushr 32)).toInt()) * -1640531535) and (seenKeys.size - 1)
        while (true) {
            val k = seenKeys[i]
            if (k == 0L) {
                seenKeys[i] = key
                seenValues[i] = argb
                seenUsed++
                return 0
            }
            if (k == key) {
                val prev = seenValues[i]
                seenValues[i] = argb
                return prev
            }
            i = (i + 1) and (seenKeys.size - 1)
        }
    }

    private fun clearSeen() {
        if (seenUsed == 0) return
        java.util.Arrays.fill(seenKeys, 0L)
        seenUsed = 0
    }

    /** 坐标打包成 64 位键（HUD 坐标远小于 65536，不会溢出） */
    private fun packKey(x1: Int, y1: Int, x2: Int, y2: Int): Long =
        (x1.toLong() shl 48) or
            ((y1.toLong() and 0xFFFFL) shl 32) or
            ((x2.toLong() and 0xFFFFL) shl 16) or
            (y2.toLong() and 0xFFFFL)

    private fun flushRects() {
        if (rectCount == 0) return
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        val tessellator = Tessellator.getInstance()
        val w = tessellator.worldRenderer
        w.begin(QUADS, DefaultVertexFormats.POSITION_COLOR)
        for (i in 0 until rectCount) {
            val o = i * 5
            var l = rects[o]
            var t = rects[o + 1]
            var rt = rects[o + 2]
            var b = rects[o + 3]
            // 与 Gui.drawRect 相同的上下左右归一化
            if (l > rt) { val s = l; l = rt; rt = s }
            if (t > b) { val s = t; t = b; b = s }
            val argb = rects[o + 4]
            val a = (argb ushr 24 and 0xFF) / 255f
            val red = (argb ushr 16 and 0xFF) / 255f
            val g = (argb ushr 8 and 0xFF) / 255f
            val bl = (argb and 0xFF) / 255f
            w.pos(rt.toDouble(), b.toDouble(), 0.0).color(red, g, bl, a).endVertex()
            w.pos(l.toDouble(), b.toDouble(), 0.0).color(red, g, bl, a).endVertex()
            w.pos(l.toDouble(), t.toDouble(), 0.0).color(red, g, bl, a).endVertex()
            w.pos(rt.toDouble(), t.toDouble(), 0.0).color(red, g, bl, a).endVertex()
        }
        tessellator.draw()
        // 对齐原版 Gui.drawRect 的收尾：批次化之后这两步丢了，后续 HUD 文字 / 贴图
        // 会沿用「纹理关闭 + 混合开启」的状态，表现为背景 / 文字绘制异常
        GlStateManager.disableBlend()
        GlStateManager.enableTexture2D()
        lastFrameDrawn += rectCount
        rectCount = 0
        clearSeen()
    }

    override fun texture(texture: Texture, x: Int, y: Int, width: Int, height: Int, alpha: Float) {
        flushRects()
        GlStateManager.enableTexture2D()
        GlStateManager.enableBlend()
        // 交给 LegacyTexture 自己决定过滤方式：这里是全项目唯一一处无条件把贴图改成
        // GL_LINEAR 的地方，玩家皮肤一旦走这里就会被永久改成双线性 → 世界里整个人糊掉。
        (texture as LegacyTexture).bind()
        GlStateManager.color(1f, 1f, 1f, alpha)
        Gui.drawModalRectWithCustomSizedTexture(x, y, 0f, 0f, width, height, width.toFloat(), height.toFloat())
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    override fun text(text: String, x: Float, y: Float, argb: Int, shadow: Boolean) {
        flushRects()
        // Gui.drawRect 画完会关掉混合，文字前重新打开
        GlStateManager.enableTexture2D()
        GlStateManager.enableBlend()
        mc.fontRendererObj.drawString(text, x, y, argb, shadow)
    }
}
