package me.sorihiko.dango.ui.glass

import me.sorihiko.dango.legacy.LegacyGl
import me.sorihiko.dango.legacy.mc
import me.sorihiko.dango.shader.impls.QuadShader
import me.sorihiko.dango.shader.impls.ui.GlassBlurShader
import me.sorihiko.dango.utils.render.RenderTarget

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

import java.nio.ByteBuffer

/**
 * 液态玻璃的"背后世界"：每帧把当前画面拷贝下来，生成两级模糊纹理。
 *  - soft ：1/4 分辨率轻度模糊（清透玻璃、折射边缘）
 *  - frost：1/8 分辨率重度模糊（磨砂玻璃）
 * 必须在绘制菜单之前调用 [capture]，这样菜单自身不会出现在背景里。
 */
class GlassBackdrop {
    private val quadShader = LegacyGl.shaderManager.get<QuadShader>()
    private val blurShader = LegacyGl.shaderManager.get<GlassBlurShader>()

    private var captureTexture = -1
    private var width = 0
    private var height = 0

    private val half = GlassTarget()
    private val softA = GlassTarget()
    private val softB = GlassTarget()
    private val frostA = GlassTarget()
    private val frostB = GlassTarget()

    val softTexture: Int get() = softA.texture
    val frostTexture: Int get() = frostA.texture

    var ready = false
        private set

    /** 上次成功捕获的 nanoTime：0 表示还没抓过（第一次必须真的抓一次） */
    private var lastCaptureNanos = 0L

    /** 玻璃质量档：0 高 / 1 中 / 2 低（读设置模块，模块没加载时按"中"处理） */
    private fun quality(): Int = try {
        me.sorihiko.dango.module.impls.music.FpsToolsModule.of()?.perfGlassQuality?.value ?: 1
    } catch (_: Throwable) {
        1
    }

    /** 质量档对应的最小捕获间隔（纳秒）：高 16ms / 中 33ms / 低 50ms */
    private fun minCaptureNanos(q: Int): Long = when (q) {
        0 -> 16_000_000L
        1 -> 33_000_000L
        else -> 50_000_000L
    }

    fun capture() {
        val w = mc.displayWidth
        val h = mc.displayHeight
        if (w <= 0 || h <= 0) return

        // 节流判断必须放在任何 GL 状态修改之前：没到间隔就直接复用上一帧的模糊纹理
        val q = quality()
        val now = System.nanoTime()
        if (lastCaptureNanos != 0L && now - lastCaptureNanos < minCaptureNanos(q)) return

        val previousFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT or GL11.GL_VIEWPORT_BIT or GL11.GL_COLOR_BUFFER_BIT or
                    GL11.GL_SCISSOR_BIT or GL11.GL_TEXTURE_BIT
        )
        try {
            if (w != width || h != height || captureTexture == -1) resize(w, h)

            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, captureTexture)
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)

            GL11.glDisable(GL11.GL_BLEND)
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDisable(GL11.GL_ALPHA_TEST)
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            GL11.glDisable(GL11.GL_CULL_FACE)
            GL11.glColorMask(true, true, true, true)

            // 逐级 2x 降采样（双线性正好取 2x2 平均，画面移动时不闪）
            // 模糊迭代次数按质量档减少：高 (2,3) / 中 (1,2) / 低 (1,1)
            val softIterations = if (q == 0) 2 else 1
            val frostIterations = when (q) {
                0 -> 3
                1 -> 2
                else -> 1
            }

            blit(captureTexture, half)
            blit(half.texture, softA)
            blur(softA, softB, 1f, softIterations)

            blit(softA.texture, frostA)
            blur(frostA, frostB, 1.6f, frostIterations)

            ready = true
            lastCaptureNanos = now
        } catch (_: Throwable) {
            // 抓取 / 着色器出问题时退化为纯玻璃面，避免拿空纹理画出一块黑
            ready = false
        } finally {
            GL20.glUseProgram(0)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo)
            GL11.glPopAttrib()
        }
    }

    private fun resize(w: Int, h: Int) {
        width = w
        height = h

        if (captureTexture != -1) GL11.glDeleteTextures(captureTexture)
        captureTexture = GlassTarget.createTexture(w, h)

        half.ensure(w / 2, h / 2)
        softA.ensure(w / 4, h / 4)
        softB.ensure(w / 4, h / 4)
        frostA.ensure(w / 8, h / 8)
        frostB.ensure(w / 8, h / 8)
    }

    private fun blit(source: Int, target: GlassTarget) {
        target.bind()
        quadShader.bind()
        quadShader.setup()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, source)
        fullscreenQuad()
    }

    /** 可分离高斯：A -> B（横向）-> A（纵向），结果留在 A */
    private fun blur(a: GlassTarget, b: GlassTarget, spread: Float, iterations: Int) {
        blurShader.bind()
        repeat(iterations) {
            b.bind()
            blurShader.setup(spread / a.width, 0f)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, a.texture)
            fullscreenQuad()

            a.bind()
            blurShader.setup(0f, spread / b.height)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, b.texture)
            fullscreenQuad()
        }
    }

    private fun fullscreenQuad() {
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(-1f, -1f)
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(1f, -1f)
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(1f, 1f)
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(-1f, 1f)
        GL11.glEnd()
    }

    fun delete() {
        if (captureTexture != -1) GL11.glDeleteTextures(captureTexture)
        captureTexture = -1
        listOf(half, softA, softB, frostA, frostB).forEach { it.delete() }
        width = 0
        height = 0
        ready = false
        // 纹理没了，下一次 capture() 必须真的重新抓一帧（否则会被节流挡掉）
        lastCaptureNanos = 0L
    }
}

/** 线性过滤 + 边缘钳制的颜色 FBO（RenderTarget 是 NEAREST，不适合降采样） */
class GlassTarget {
    var fbo = -1
        private set
    var texture = -1
        private set
    var width = 0
        private set
    var height = 0
        private set

    fun ensure(w: Int, h: Int) {
        val nw = w.coerceAtLeast(1)
        val nh = h.coerceAtLeast(1)
        if (nw == width && nh == height && fbo != -1) return
        delete()
        width = nw
        height = nh

        texture = createTexture(nw, nh)
        fbo = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0
        )
        val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Glass framebuffer create failed: $status")
        }
    }

    fun bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL11.glViewport(0, 0, width, height)
    }

    fun delete() {
        if (fbo != -1) GL30.glDeleteFramebuffers(fbo)
        if (texture != -1) GL11.glDeleteTextures(texture)
        fbo = -1
        texture = -1
        width = 0
        height = 0
    }

    companion object {
        fun createTexture(w: Int, h: Int): Int {
            val id = GL11.glGenTextures()
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?
            )
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            return id
        }
    }
}
