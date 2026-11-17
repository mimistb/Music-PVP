package me.sorihiko.dango.ui

import me.sorihiko.dango.DangoLogger

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 主菜单开场 Hello 手写动画。
 *
 * 复刻参考实现（fangduan.parrot 的 Apple hello）：
 *   1. SVG path（hell 连笔 / o / e 横）→ 折线采样（CURVE_STEP = 2.0）
 *   2. 7 色彩虹渐变贴图，横向铺满整个「hello」包围盒
 *   3. 写 → 停 → 擦 的 revealFrac 时间线（easeInOutCubic）
 *   4. 三遍描边（外发光 2.1× / 内发光 1.35× / 核心 1.0×）
 *
 * 与本项目的差异只在「怎么把线画出来」：
 * 参考用 NanoVG 的 nvgStroke，本项目没有线段原语，所以把三遍描边**预烘焙**成静态
 * 三角带顶点缓冲（每笔每遍一条 GL_TRIANGLE_STRIP），逐顶点只带纹理坐标；每帧用
 * glDrawArrays 的**前缀长度**做写/擦，零分配、零重新上传，最坏 27 次小 draw。
 *
 * 生命周期：启动后第一次进主菜单播一次，5.6s 完整周期结束自动淡出交接主菜单；
 * 播放中任意键 / 点击立即以 0.35s 淡出跳过（与原参考一致）。
 */
object HelloIntro {

    // ---------------------------------------------------------------- SVG 原始数据

    /** 苹果 hello 全局平移 translate(-199 -183)。 */
    private const val TX = -199f
    private const val TY = -183f

    /** path-1：h e l l 连笔主体（含 S 平滑三次贝塞尔）。 */
    private const val D1 =
        "M208,338c38-16.67,73.74-45.72,97.33-66,21.33-18.33,32.67-35.67,37.33-52.67" +
            "C347.12,203.12,344,192,332,192c-11,0-21,10.33-24.94,27.68-4.52,19.89-22.06,107.82-29.39,149 " +
            "15.67-72.33,36.33-81.33,53.67-81.33,22.33,0,24.67,18.67,19.42,39-5.43,21.07-7.42,44.32,17.91,44.32 " +
            "18,0,35.53-8.17,52.67-20,14-9.67,23-24,23-40,0-13.42-8-23.33-20.67-23.33s-24.33,12-24.33,33.33" +
            "c0,27,16.33,48,44,48,25.67,0,47.67-19.67,62-44.67,13.61-23.74,30.67-64.67,33.33-92.67" +
            "s-5.33-36-18.67-36-24.67,17.33-28.67,43.33S486,302,491.33,330s28,37.67,46,37.67" +
            "s38.17-15.67,52-37c16.54-25.51,35.87-67.45,38.67-102,2-24.67-8.67-33.33-20-33.33" +
            "c-14.67,0-23.33,13.33-28,38-4.5,23.81-8,64-2,94,4.64,23.21,25.33,40.33,44.67,40.33" +
            "s32.67-19,36.67-42.33"

    /** path-2：o。 */
    private const val D2 =
        "M697.33,287.33C672,287.33,661.33,305,659,327c-2.81,26.54,10.33,41.67,29.67,41.67" +
            "c22,0,34.54-20.78,36.67-40.67,2-18.67-7.39-39.13-28-40.67"

    /** path-3：e 的横。 */
    private const val D3 =
        "M714.8,295.12c12.11,12.26,43.53,9.55,56.53-5.79"

    /** 采样密度（原始坐标单位下的步长）。 */
    private const val CURVE_STEP = 2.0f

    /** 整段动画周期（秒），与参考源码一致。 */
    private const val CYCLE_SECONDS = 5.6f

    /**
     * 自动收尾起点：path-1 的擦除在 5.0s 结束，之后整屏本来就是空的，
     * 正好用剩下 0.6s 淡出，把总时长精确卡在 5.6s（与参考一个周期等长）。
     */
    private const val AUTO_FADE_AT = 5.0f

    /** 跳过时的淡出时长（参考 0.35s）。 */
    private const val SKIP_FADE = 0.35f

    /** 画笔基础宽度（设计坐标单位）。 */
    private const val STROKE_W = 12f

    /** 三遍描边的宽度倍数：外发光 / 内发光 / 核心亮线。 */
    private val PASS_WIDTH = floatArrayOf(2.1f, 1.35f, 1.0f)

    /** 三遍描边的基础透明度（对应参考 alpha*(0.40+0.30*pop) / alpha*(0.75+0.20*pop) / alpha）。 */
    private val PASS_ALPHA = floatArrayOf(0.40f, 0.75f, 1.0f)

    /** 写/擦时间窗（秒），顺序对应 D1 / D2 / D3，数值来自苹果 SVG 的 CSS keyframes。 */
    private val SCHEDULE = arrayOf(
        Schedule(0.00f, 1.80f, 3.00f, 5.00f),
        Schedule(1.35f, 1.80f, 2.70f, 3.25f),
        Schedule(1.70f, 1.80f, 2.50f, 2.85f),
    )

    /** 7 个彩虹 stop（与苹果 SVG 一致）：offset, r, g, b。 */
    private val STOPS = arrayOf(
        floatArrayOf(0.00f, 255f, 59f, 48f),
        floatArrayOf(0.18f, 255f, 149f, 0f),
        floatArrayOf(0.36f, 255f, 204f, 0f),
        floatArrayOf(0.54f, 52f, 199f, 89f),
        floatArrayOf(0.72f, 0f, 199f, 190f),
        floatArrayOf(0.88f, 0f, 122f, 255f),
        floatArrayOf(1.00f, 175f, 82f, 222f),
    )

    /** 暗底，与主菜单入口图标同一套色。 */
    private const val TOP_R = 0.042f
    private const val TOP_G = 0.048f
    private const val TOP_B = 0.066f
    private const val BOT_R = 0.011f
    private const val BOT_G = 0.013f
    private const val BOT_B = 0.019f
    private const val ACC_R = 0.039f
    private const val ACC_G = 0.518f
    private const val ACC_B = 1.0f

    // ---------------------------------------------------------------- 状态机

    private const val STATE_IDLE = 0
    private const val STATE_PLAYING = 1
    private const val STATE_FADING = 2
    private const val STATE_DONE = 3

    private var state = STATE_IDLE
    private var startNanos = 0L
    private var fadeStart = 0L
    private var fadeSpan = SKIP_FADE
    private var fadeFrom = 1f
    private var alpha = 1f
    private var drawnThisFrame = false
    private var errorLogged = false

    /** GuiMainMenu#drawScreen 头部调用：一帧只画一次（drawScreen 可能有多个 RETURN）。 */
    @JvmStatic
    fun beginFrame() {
        drawnThisFrame = false
    }

    /** 开场动画是否还在接管画面；true 时主菜单入口图标不画、点击只用于跳过。 */
    @JvmStatic
    fun isActive(): Boolean = state == STATE_PLAYING || state == STATE_FADING

    /** 任意键跳过（GuiScreen#keyTyped 注入）。 */
    @JvmStatic
    fun onKeyPress() {
        try {
            skip()
        } catch (t: Throwable) {
            // 输入钩子不能抛
        }
    }

    /** 点击 / 按键跳过：0.35s 淡出，与原参考一致。 */
    @JvmStatic
    fun skip() {
        if (state != STATE_PLAYING) return
        state = STATE_FADING
        fadeStart = System.nanoTime()
        fadeFrom = alpha
        fadeSpan = SKIP_FADE
    }

    /** GuiMainMenu#drawScreen 末尾调用：整屏接管主菜单。 */
    @JvmStatic
    fun render() {
        if (drawnThisFrame) return
        drawnThisFrame = true
        if (state == STATE_DONE) return

        val now = System.nanoTime()
        if (state == STATE_IDLE) {
            state = STATE_PLAYING
            startNanos = now
            alpha = 1f
        }

        val elapsed = (now - startNanos) / 1_000_000_000.0f
        if (state == STATE_PLAYING && elapsed >= AUTO_FADE_AT) {
            state = STATE_FADING
            fadeStart = now
            fadeFrom = 1f
            fadeSpan = CYCLE_SECONDS - AUTO_FADE_AT
        }
        if (state == STATE_FADING) {
            val p = ((now - fadeStart) / 1_000_000_000.0f / fadeSpan).coerceIn(0f, 1f)
            alpha = fadeFrom * (1f - p)
            if (p >= 1f) {
                state = STATE_DONE
                return
            }
        }
        if (alpha <= 0.002f) return

        try {
            draw(elapsed.coerceAtMost(CYCLE_SECONDS), alpha)
        } catch (t: Throwable) {
            // 动画失败绝不能挡住主菜单
            state = STATE_DONE
            if (!errorLogged) {
                errorLogged = true
                DangoLogger.warn("Hello intro failed: {}", t.toString())
            }
        }
    }

    // ---------------------------------------------------------------- 绘制

    private fun draw(t: Float, alpha: Float) {
        val mc = Minecraft.getMinecraft()
        val screen = mc.currentScreen ?: return
        val w = screen.width
        val h = screen.height
        val strokes = geometries()

        // 等比铺满：宽占 58%、高占 34%，整体略高于正中（和参考标题页一致）
        val scale = min(w * 0.58f / designW, h * 0.34f / designH)
        val offX = (w - designW * scale) * 0.5f - designMinX * scale
        val offY = h * 0.46f - (designMinY + designH * 0.5f) * scale

        drawBackdrop(w, h, alpha)
        drawHello(strokes, t, alpha, scale, offX, offY)
        drawWelcome(mc, w, alpha, t, offY + (designMinY + designH) * scale + 30f)
        restore()
    }

    /** 整屏暗底 + 两团缓慢游动的强调色柔光（淡出时跟着一起退，露出原版主菜单）。 */
    private fun drawBackdrop(w: Int, h: Int, alpha: Float) {
        GlStateManager.disableTexture2D()
        GlStateManager.enableBlend()
        GlStateManager.disableAlpha()
        GlStateManager.disableDepth()
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO
        )
        GlStateManager.color(1f, 1f, 1f, 1f)

        val wr = Tessellator.getInstance().worldRenderer
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR)
        wr.pos(0.0, h.toDouble(), 0.0).color(BOT_R, BOT_G, BOT_B, alpha).endVertex()
        wr.pos(w.toDouble(), h.toDouble(), 0.0).color(BOT_R, BOT_G, BOT_B, alpha).endVertex()
        wr.pos(w.toDouble(), 0.0, 0.0).color(TOP_R, TOP_G, TOP_B, alpha).endVertex()
        wr.pos(0.0, 0.0, 0.0).color(TOP_R, TOP_G, TOP_B, alpha).endVertex()
        Tessellator.getInstance().draw()

        val now = Minecraft.getSystemTime()
        val x1 = w * 0.22f + sin((now / 5200.0).toFloat()) * w * 0.05f
        val y1 = h * 0.30f + cos((now / 6100.0).toFloat()) * h * 0.04f
        glow(x1, y1, max(w, h) * 0.55f, ACC_R, ACC_G, ACC_B, 0.10f * alpha)

        val x2 = w * 0.82f + cos((now / 7000.0).toFloat()) * w * 0.04f
        val y2 = h * 0.74f + sin((now / 8100.0).toFloat()) * h * 0.05f
        glow(x2, y2, max(w, h) * 0.45f, 0.28f, 0.22f, 0.85f, 0.07f * alpha)
    }

    /** 三笔 × 三遍描边：前缀顶点做写/擦，笔尖补圆头。 */
    private fun drawHello(
        strokes: Array<Stroke>,
        t: Float,
        alpha: Float,
        scale: Float,
        offX: Float,
        offY: Float,
    ) {
        val tex = gradientTexture()
        if (tex <= 0) return

        GlStateManager.enableTexture2D()
        GlStateManager.enableBlend()
        GlStateManager.disableAlpha()
        GlStateManager.disableDepth()
        GlStateManager.disableLighting()
        GlStateManager.disableFog()
        GlStateManager.disableCull()
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO
        )
        GlStateManager.color(1f, 1f, 1f, 1f)
        GlStateManager.bindTexture(tex)

        GlStateManager.pushMatrix()
        GlStateManager.translate(offX, offY, 0f)
        GlStateManager.scale(scale, scale, 1f)

        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY)
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY)

        for (i in strokes.indices) {
            val s = strokes[i]
            val sched = SCHEDULE[i]
            val local = revealFrac(sched, t)
            if (local <= 0.002f) continue
            val pop = penPop(sched, t)
            val drawn = computeTip(s, local * s.total)
            val verts = min(s.verts, drawn * 2)
            if (verts < 2) continue

            for (pass in 0 until 3) {
                val a = (alpha * (PASS_ALPHA[pass] + (if (pass == 0) 0.30f else if (pass == 1) 0.20f else 0f) * pop))
                    .coerceIn(0f, 1f)
                GlStateManager.color(1f, 1f, 1f, a)
                GL11.glVertexPointer(2, 0, s.strip[pass])
                GL11.glTexCoordPointer(2, 0, s.uv)
                GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, verts)

                // 圆头（NanoVG 的 NVG_ROUND）：起点 + 当前笔尖
                val halfW = STROKE_W * PASS_WIDTH[pass] * 0.5f
                drawCap(s.pts[0], s.pts[1], halfW, uOf(s.pts[0]))
                drawCap(tipX, tipY, halfW, uOf(tipX))
            }
        }

        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY)
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY)
        GlStateManager.popMatrix()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    /** 圆头：单位圆 fan 缩放平移，纹理坐标取该点的彩虹位置。 */
    private fun drawCap(x: Float, y: Float, radius: Float, u: Float) {
        if (radius <= 0f) return
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY)
        GL11.glTexCoord2f(u, 0.5f)
        GL11.glVertexPointer(2, 0, circleBuf)
        GlStateManager.pushMatrix()
        GlStateManager.translate(x, y, 0f)
        GlStateManager.scale(radius, radius, 1f)
        GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, CIRCLE_VERTS)
        GlStateManager.popMatrix()
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY)
    }

    /** 欢迎文字：hello 画完后淡入，跟着整层一起淡出。 */
    private fun drawWelcome(mc: Minecraft, w: Int, alpha: Float, t: Float, textTop: Float) {
        val k = ((t - 1.70f) / 0.70f).coerceIn(0f, 1f)
        if (k <= 0f) return

        GlStateManager.enableTexture2D()
        GlStateManager.enableBlend()
        GlStateManager.disableAlpha()
        GlStateManager.disableDepth()
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO
        )
        GlStateManager.color(1f, 1f, 1f, 1f)

        val fr = mc.fontRendererObj
        val name = try {
            mc.session.username
        } catch (t2: Throwable) {
            ""
        }
        val line = if (name.isNullOrEmpty()) "Welcome back" else "Welcome back, " + name
        val a = alpha * k

        GlStateManager.pushMatrix()
        GlStateManager.translate(w * 0.5f, textTop, 0f)
        GlStateManager.scale(1.5f, 1.5f, 1f)
        fr.drawString(line, -fr.getStringWidth(line) / 2, 0, alphaColor(0xFFFFFF, a))
        GlStateManager.popMatrix()

        val sub = "DANGO MUSIC"
        GlStateManager.pushMatrix()
        GlStateManager.translate(w * 0.5f, textTop + 26f, 0f)
        fr.drawString(sub, -fr.getStringWidth(sub) / 2, 0, alphaColor(0x7FC3EE, a * 0.85f))
        GlStateManager.popMatrix()
    }

    private fun glow(cx: Float, cy: Float, radius: Float, r: Float, g: Float, b: Float, a: Float) {
        if (radius <= 0f || a <= 0f) return
        val wr = Tessellator.getInstance().worldRenderer
        wr.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR)
        wr.pos(cx.toDouble(), cy.toDouble(), 0.0).color(r, g, b, a).endVertex()
        val steps = 28
        for (i in 0..steps) {
            val angle = i * Math.PI * 2.0 / steps
            wr.pos(
                (cx + cos(angle).toFloat() * radius).toDouble(),
                (cy + sin(angle).toFloat() * radius).toDouble(),
                0.0
            ).color(r, g, b, 0f).endVertex()
        }
        Tessellator.getInstance().draw()
    }

    private fun restore() {
        try {
            GlStateManager.color(1f, 1f, 1f, 1f)
            GlStateManager.enableTexture2D()
            GlStateManager.disableBlend()
        } catch (t: Throwable) {
            // 恢复失败不影响主菜单
        }
    }

    private fun alphaColor(rgb: Int, a: Float): Int =
        ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (rgb and 0xFFFFFF)

    // ---------------------------------------------------------------- 时间线

    private class Schedule(
        val writeStart: Float,
        val writeEnd: Float,
        val eraseStart: Float,
        val eraseEnd: Float,
    )

    private fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) {
            4f * x * x * x
        } else {
            val y = -2f * x + 2f
            1f - y * y * y * 0.5f
        }
    }

    /** 某段在周期相位 t 下的「已画出比例」0..1（写 → 停 → 擦 → 空）。 */
    private fun revealFrac(s: Schedule, t: Float): Float {
        return when {
            t < s.writeStart -> 0f
            t < s.writeEnd -> easeInOutCubic((t - s.writeStart) / (s.writeEnd - s.writeStart))
            t < s.eraseStart -> 1f
            t < s.eraseEnd -> 1f - easeInOutCubic((t - s.eraseStart) / (s.eraseEnd - s.eraseStart))
            else -> 0f
        }
    }

    /** 落笔压感脉冲：写入阶段 0→1→0，其余为 0（参考 drawHello 的 pop 参数）。 */
    private fun penPop(s: Schedule, t: Float): Float {
        if (t <= s.writeStart || t >= s.writeEnd) return 0f
        val p = (t - s.writeStart) / (s.writeEnd - s.writeStart)
        return sin((p * Math.PI).toFloat())
    }

    // ---------------------------------------------------------------- 几何烘焙

    private class Stroke(
        val pts: FloatArray,
        val cum: FloatArray,
        val total: Float,
        /** 三遍描边各自的位置缓冲（每条 2 个顶点/点，横向偏移不同）。 */
        val strip: Array<FloatBuffer>,
        val uv: FloatBuffer,
        val verts: Int,
    )

    private var cache: Array<Stroke>? = null
    private var designMinX = 0f
    private var designMinY = 0f
    private var designW = 1f
    private var designH = 1f

    /** 当前笔尖位置（computeTip 的输出，避免每帧分配）。 */
    private var tipX = 0f
    private var tipY = 0f

    private fun geometries(): Array<Stroke> {
        cache?.let { return it }
        val raw = arrayOf(samplePath(D1), samplePath(D2), samplePath(D3))

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in raw) {
            var k = 0
            while (k + 1 < p.size) {
                minX = min(minX, p[k]); maxX = max(maxX, p[k])
                minY = min(minY, p[k + 1]); maxY = max(maxY, p[k + 1])
                k += 2
            }
        }
        if (minX > maxX) { minX = 0f; maxX = 1f; minY = 0f; maxY = 1f }
        designMinX = minX
        designMinY = minY
        designW = max(1e-3f, maxX - minX)
        designH = max(1e-3f, maxY - minY)

        val built = Array(raw.size) { bakeStroke(raw[it]) }
        cache = built
        return built
    }

    private fun bakeStroke(pts: FloatArray): Stroke {
        val n = max(1, pts.size / 2)
        val cum = FloatArray(n)
        var total = 0f
        for (k in 1 until n) {
            val dx = pts[k * 2] - pts[k * 2 - 2]
            val dy = pts[k * 2 + 1] - pts[k * 2 - 1]
            total += sqrt(dx * dx + dy * dy)
            cum[k] = total
        }

        val uv = ByteBuffer.allocateDirect(n * 2 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val strip = Array(3) { p ->
            ByteBuffer.allocateDirect(n * 2 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }

        for (k in 0 until n) {
            // 彩虹沿整段 hello 的包围盒横向铺开（苹果 SVG 的 linearGradient）
            val u = ((pts[k * 2] - designMinX) / designW).coerceIn(0f, 1f)
            uv.put(u); uv.put(0.5f)
            uv.put(u); uv.put(0.5f)

            val prev = if (k > 0) k - 1 else k
            val next = if (k < n - 1) k + 1 else k
            var dx = pts[next * 2] - pts[prev * 2]
            var dy = pts[next * 2 + 1] - pts[prev * 2 + 1]
            var len = sqrt(dx * dx + dy * dy)
            if (len < 1e-5f) { dx = 1f; dy = 0f; len = 1f }
            val nx = -dy / len
            val ny = dx / len

            for (p in 0 until 3) {
                val halfW = STROKE_W * PASS_WIDTH[p] * 0.5f
                val buf = strip[p]
                buf.put(pts[k * 2] + nx * halfW); buf.put(pts[k * 2 + 1] + ny * halfW)
                buf.put(pts[k * 2] - nx * halfW); buf.put(pts[k * 2 + 1] - ny * halfW)
            }
        }
        uv.flip()
        for (buf in strip) buf.flip()

        return Stroke(pts, cum, total, strip, uv, n * 2)
    }

    /** 按弧长定位笔尖，返回完全画出的点数（>=1），笔尖位置写进 tipX/tipY。 */
    private fun computeTip(s: Stroke, reveal: Float): Int {
        val n = s.cum.size
        if (n <= 1) {
            tipX = s.pts[0]; tipY = s.pts[1]
            return 1
        }
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (s.cum[mid] <= reveal) lo = mid else hi = mid - 1
        }
        if (lo >= n - 1) {
            tipX = s.pts[n * 2 - 2]; tipY = s.pts[n * 2 - 1]
            return n
        }
        val segLen = s.cum[lo + 1] - s.cum[lo]
        val f = if (segLen > 1e-5f) ((reveal - s.cum[lo]) / segLen).coerceIn(0f, 1f) else 0f
        tipX = s.pts[lo * 2] + (s.pts[(lo + 1) * 2] - s.pts[lo * 2]) * f
        tipY = s.pts[lo * 2 + 1] + (s.pts[(lo + 1) * 2 + 1] - s.pts[lo * 2 + 1]) * f
        return lo + 1
    }

    private fun uOf(x: Float): Float = ((x - designMinX) / designW).coerceIn(0f, 1f)

    // ---------------------------------------------------------------- SVG 解析

    /** 解析一条 path 成折线点串（绝对坐标，已平移 TX/TY）。 */
    private fun samplePath(d: String): FloatArray {
        val tokens = tokenize(d)
        val out = ArrayList<Float>(2048)
        var i = 0
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        var lastCtrlX = 0f
        var lastCtrlY = 0f
        var prevCmd = 'M'
        var cmd = 'M'
        while (i < tokens.size) {
            val tk = tokens[i]
            if (tk.length == 1 && tk[0].isLetter()) {
                cmd = tk[0]
                i++
            }
            when (cmd) {
                'M', 'm' -> {
                    val x = tokens[i++].toFloat()
                    val y = tokens[i++].toFloat()
                    val ax = if (cmd == 'm') cx + x else x
                    val ay = if (cmd == 'm') cy + y else y
                    cx = ax; cy = ay; startX = ax; startY = ay
                    out.add(ax); out.add(ay)
                    cmd = if (cmd == 'M') 'L' else 'l'
                }
                'L', 'l' -> {
                    val x = tokens[i++].toFloat()
                    val y = tokens[i++].toFloat()
                    val ax = if (cmd == 'l') cx + x else x
                    val ay = if (cmd == 'l') cy + y else y
                    out.add(ax); out.add(ay)
                    cx = ax; cy = ay
                }
                'C', 'c' -> {
                    val x1 = tokens[i++].toFloat(); val y1 = tokens[i++].toFloat()
                    val x2 = tokens[i++].toFloat(); val y2 = tokens[i++].toFloat()
                    val x = tokens[i++].toFloat(); val y = tokens[i++].toFloat()
                    val ax1 = if (cmd == 'c') cx + x1 else x1
                    val ay1 = if (cmd == 'c') cy + y1 else y1
                    val ax2 = if (cmd == 'c') cx + x2 else x2
                    val ay2 = if (cmd == 'c') cy + y2 else y2
                    val ax = if (cmd == 'c') cx + x else x
                    val ay = if (cmd == 'c') cy + y else y
                    sampleCubic(cx, cy, ax1, ay1, ax2, ay2, ax, ay, out)
                    lastCtrlX = ax2; lastCtrlY = ay2; prevCmd = 'C'
                    cx = ax; cy = ay
                }
                'S', 's' -> {
                    // 平滑三次贝塞尔：首控制点由上一个 C/S 的控制点反射而来
                    val refl = prevCmd == 'C' || prevCmd == 'S' || prevCmd == 'c' || prevCmd == 's'
                    val rx1 = if (refl) 2f * cx - lastCtrlX else cx
                    val ry1 = if (refl) 2f * cy - lastCtrlY else cy
                    val x2 = tokens[i++].toFloat(); val y2 = tokens[i++].toFloat()
                    val x = tokens[i++].toFloat(); val y = tokens[i++].toFloat()
                    val ax2 = if (cmd == 's') cx + x2 else x2
                    val ay2 = if (cmd == 's') cy + y2 else y2
                    val ax = if (cmd == 's') cx + x else x
                    val ay = if (cmd == 's') cy + y else y
                    sampleCubic(cx, cy, rx1, ry1, ax2, ay2, ax, ay, out)
                    lastCtrlX = ax2; lastCtrlY = ay2; prevCmd = 'S'
                    cx = ax; cy = ay
                }
                'Z', 'z' -> {
                    out.add(startX); out.add(startY)
                    cx = startX; cy = startY
                    cmd = 'L'; prevCmd = 'Z'
                }
                else -> {
                    if (i < tokens.size) i++
                    if (i < tokens.size) i++
                }
            }
        }
        val arr = out.toFloatArray()
        for (k in arr.indices) arr[k] = arr[k] + (if (k % 2 == 0) TX else TY)
        return arr
    }

    /** 拆成「指令字母 + 数字」的 token。 */
    private fun tokenize(d: String): List<String> {
        val tokens = ArrayList<String>(256)
        var i = 0
        val n = d.length
        while (i < n) {
            val c = d[i]
            when {
                c.isLetter() -> {
                    tokens.add(c.toString())
                    i++
                }
                c == ',' || c == ' ' || c == '\n' || c == '\r' || c == '\t' -> i++
                c == '-' || c == '+' || c == '.' || c.isDigit() -> {
                    var j = i + 1
                    while (j < n && (d[j].isDigit() || d[j] == '.' || d[j] == 'e' || d[j] == 'E'
                            || (d[j] == '-' && j + 1 < n && (d[j - 1] == 'e' || d[j - 1] == 'E'))
                            || (d[j] == '+' && j + 1 < n))) j++
                    tokens.add(d.substring(i, j))
                    i = j
                }
                else -> i++
            }
        }
        return tokens
    }

    /** 三次贝塞尔按弧长步长采样（只追加终点）。 */
    private fun sampleCubic(
        x0: Float, y0: Float, x1: Float, y1: Float,
        x2: Float, y2: Float, x3: Float, y3: Float,
        out: ArrayList<Float>,
    ) {
        val chord = hypot(x3 - x0, y3 - y0)
        val ctrlPoly = hypot(x1 - x0, y1 - y0) + hypot(x2 - x1, y2 - y1) + hypot(x3 - x2, y3 - y2)
        val est = (chord + ctrlPoly) * 0.5f
        val steps = max(2, (est / CURVE_STEP).toInt())
        for (k in 1..steps) {
            val t = k.toFloat() / steps
            val mt = 1f - t
            val a = mt * mt * mt
            val b = 3f * mt * mt * t
            val c = 3f * mt * t * t
            val e = t * t * t
            out.add(a * x0 + b * x1 + c * x2 + e * x3)
            out.add(a * y0 + b * y1 + c * y2 + e * y3)
        }
    }

    // ---------------------------------------------------------------- 彩虹贴图

    private var gradTex = -1

    /** 256x1 横向彩虹贴图，与参考的 7 个 stop 逐像素一致。 */
    private fun gradientTexture(): Int {
        if (gradTex > 0) return gradTex
        val buf = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until 256) {
            val f = i / 255f
            var seg = 0
            while (seg < STOPS.size - 2 && f > STOPS[seg + 1][0]) seg++
            val o0 = STOPS[seg]
            val o1 = STOPS[seg + 1]
            val t = if (o1[0] > o0[0]) (f - o0[0]) / (o1[0] - o0[0]) else 0f
            buf.put(i * 4, (o0[1] + (o1[1] - o0[1]) * t).toInt().coerceIn(0, 255).toByte())
            buf.put(i * 4 + 1, (o0[2] + (o1[2] - o0[2]) * t).toInt().coerceIn(0, 255).toByte())
            buf.put(i * 4 + 2, (o0[3] + (o1[3] - o0[3]) * t).toInt().coerceIn(0, 255).toByte())
            buf.put(i * 4 + 3, 0xFF.toByte())
        }

        val id = GL11.glGenTextures()
        if (id <= 0) return 0
        GlStateManager.bindTexture(id)
        GlStateManager.enableTexture2D()
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 256, 1, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf
        )
        gradTex = id
        return id
    }

    // ---------------------------------------------------------------- 圆头几何

    private const val CIRCLE_SEG = 20
    private const val CIRCLE_VERTS = CIRCLE_SEG + 2

    private val circleBuf: FloatBuffer by lazy {
        val b = ByteBuffer.allocateDirect(CIRCLE_VERTS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(0f); b.put(0f)
        for (i in 0..CIRCLE_SEG) {
            val a = i * Math.PI * 2.0 / CIRCLE_SEG
            b.put(cos(a).toFloat()); b.put(sin(a).toFloat())
        }
        b.flip()
        b
    }
}
