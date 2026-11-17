package me.sorihiko.dango.ui.glass

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.legacy.LegacyGl
import me.sorihiko.dango.legacy.mc
import me.sorihiko.dango.shader.impls.QuadShader
import me.sorihiko.dango.shader.impls.ui.GlassBlurShader
import me.sorihiko.dango.utils.render.RenderTarget

import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
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

    /** 抓帧时序：帧首抓、HUD 阶段复用（见 [GlassCaptureSchedule]） */
    private val schedule = GlassCaptureSchedule()

    /** 诊断日志：0 未出结果 / 1 已报成功 / 2 已报失败，避免每帧刷屏 */
    private var loggedOutcome = 0

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

    /**
     * 帧首（原版 HUD 元素绘制之前）调用一次。
     *
     * 聊天框打开时 `GuiNewChat` 会先铺一层近乎不透明的黑底，而 HUD 玻璃要到 `Post(ALL)`
     * 才画；如果等到那时再抓屏，"背后的世界"就是聊天黑底，玻璃整块变黑。
     * 所以玻璃背景必须在原版 HUD 之前抓，这里只在「上一帧确实画过玻璃」时才真的抓，
     * 没开玻璃 HUD 时完全不产生开销。
     */
    fun captureForFrameStart() {
        // 每帧都先清一次"已处理"标记：HUD 若因界面推迟补绘而没走 endHudPass，
        // 不重置会让抓帧永久停摆
        schedule.beginFrame()
        val want = schedule.onFrameStart()
        // 帧首只做兜底：正常情况下世界末尾（captureWorldPass）已经抓好图，
        // 这里再抓不但浪费，还可能抓到被原版 HUD 污染的画面
        if (!ready && want) captureFrom(0)
    }

    /**
     * 世界渲染末尾（RenderWorldLastEvent）抓帧 —— 主路径。
     *
     * 此刻画面是完整的世界（不含原版 HUD / 聊天黑底 / ESC 黑渐变），抓下来的就是面板
     * 背后真正要模糊的东西。读取源直接用「当前 DRAW 绑定」：原版 1.8.9 世界画在主缓冲上
     * （DRAW = 0），光影把世界画进自己的 FBO 时就是那个 FBO，两种情况都能正确取到画面。
     */
    fun captureWorldPass() {
        // 1.8.9 原版世界直接画在主缓冲上（updateCameraAndRender 里已经 unbindFramebuffer），
        // 所以这里 DRAW 绑定通常就是 0；光影 / 部分 mod 会把世界画进自己的 FBO，那就跟着它。
        // 两者都在「当前 DRAW 绑定」里，直接用即可，不需要再猜。
        val drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        captureFrom(drawFbo)
    }

    /**
     * 还没有任何可用纹理时的兜底抓取（主菜单 / 刚进世界 / 刚改窗口大小）。
     *
     * 只允许在"当前就在主缓冲上画"的时候抓：世界渲染 / 离屏 FBO 阶段画面还没合成，
     * 抓到的只能是上一帧。
     */
    fun captureIfIdle() {
        if (ready || schedule.inHudPass) return
        if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != 0) return
        captureFrom(0)
    }

    /** 本帧有画布请求玻璃背景：让下一帧帧首继续抓屏 */
    fun noteUsed() {
        schedule.noteUsed()
    }

    /** 是否处在原版 HUD 绘制阶段（此时画面里已混进聊天 / 计分板，不能再抓帧） */
    val inHudPass: Boolean
        get() = schedule.inHudPass

    /** 进入 HUD 绘制阶段：到 [endHudPass] 之前一律复用帧首抓好的纹理 */
    fun beginHudPass() {
        schedule.beginHudPass()
    }

    /** HUD 绘制结束：滚动「本帧用过」标记 */
    fun endHudPass() {
        schedule.endHudPass()
    }

    /**
     * @param sourceFbo 读取源：0 = 主缓冲（原版 1.8.9 世界 / 帧首兜底），非 0 = 光影等
     *                  第三方渲染世界用的 FBO
     */
    private fun captureFrom(sourceFbo: Int) {
        // HUD 阶段画面里已经混进原版聊天 / 计分板，再抓就会把它们的黑底当成背景。
        if (schedule.inHudPass && ready) return
        val w = mc.displayWidth
        val h = mc.displayHeight
        if (w <= 0 || h <= 0) return

        // 调用方（Pre(ALL) 帧首 / 主菜单 begin）所在时刻画面已经合成到 0 号缓冲；
        // 世界渲染与离屏 FBO 阶段禁止抓帧（见 captureIfIdle），所以这里只需要记住
        // 进来时的绑定，稍后原样还回去
        val drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        val readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)

        // 节流判断必须放在任何 GL 状态修改之前：没到间隔就直接复用上一帧的模糊纹理
        val q = quality()
        val now = System.nanoTime()
        if (lastCaptureNanos != 0L && now - lastCaptureNanos < minCaptureNanos(q)) return

        val needsResize = w != width || h != height || captureTexture == -1

        // 整段抓取都在 0 号纹理单元上裸绑纹理，GlStateManager 的缓存不会跟着变；
        // 进出时各同步一次，避免原版之后 bindTexture 被"缓存已绑"跳过而丢纹理
        val previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit)
        val previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)

        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT or GL11.GL_VIEWPORT_BIT or GL11.GL_COLOR_BUFFER_BIT or
                    GL11.GL_SCISSOR_BIT or GL11.GL_TEXTURE_BIT
        )
        try {
            if (needsResize) resize(w, h)

            // resize() 里的 GlassTarget.ensure 会把 READ / DRAW 一起改到内部 FBO，
            // 不显式扳回来就会拷到刚建的空纹理上（全黑）。这里按调用方给的源重新绑定：
            // 世界末尾传当时的 DRAW 绑定（原版 1.8.9 为 0），读主缓冲时把读缓冲强制回 GL_BACK，
            // 避免被别的 mod 留成 FRONT / NONE 而拷到空白。
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFbo)
            if (sourceFbo == 0) GL11.glReadBuffer(GL11.GL_BACK)

            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, captureTexture)
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)

            // 光影 / MSAA 的 FBO 不支持 CopyTexSubImage 时会静默失败（纹理是空的）。
            // 只在第一次抓帧后查一次错误码，既能写进日志定位问题，又不会拖慢每帧。
            if (loggedOutcome == 0) {
                val err = GL11.glGetError()
                if (err != 0) {
                    DangoLogger.warn(
                        "[Dango Music] glass backdrop copy error 0x{} srcFbo={}",
                        Integer.toHexString(err), sourceFbo,
                    )
                }
            }

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
            if (loggedOutcome != 1) {
                loggedOutcome = 1
                DangoLogger.info("[Dango Music] glass backdrop capture ok {}x{} srcFbo={}", w, h, sourceFbo)
            }
        } catch (_: Throwable) {
            // 刚 resize 过时纹理是空的（采样恒为黑），必须标记不可用；
            // 没 resize 就继续用上一帧的模糊纹理，比退回接近纯黑的 fallback 更好
            if (needsResize) ready = false
            if (loggedOutcome != 2) {
                loggedOutcome = 2
                DangoLogger.warn(
                    "[Dango Music] glass backdrop capture failed srcFbo={} size={}x{}: {}",
                    sourceFbo, w, h, "OpenGL copy/blur error",
                )
            }
        } finally {
            GL20.glUseProgram(0)
            // READ / DRAW 分开还原：统一绑回 GL_FRAMEBUFFER 会把调用方特意设置的
            // 「从主画面读取」的 READ 绑定一起改掉，下一次抓取就会读到 UI 自己的 FBO
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo)
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo)
            GL11.glPopAttrib()
            // 把 0 号单元的纹理绑定与当前单元同时还回去（缓存 + 真实 GL 各写一次）
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit)
            GlStateManager.bindTexture(previousTexture)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture)
            GlStateManager.setActiveTexture(previousUnit)
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
        // 抓帧时序也归零：离开世界后不能留着"HUD 阶段"或"上一帧用过"的残留
        schedule.reset()
        loggedOutcome = 0
        if (sharedInstance === this) sharedInstance = null
    }

    companion object {
        /**
         * 全进程共用的那一份背景抓取。
         *
         * 每个 HUD 模块都持有自己的 [me.sorihiko.dango.gfx.GuiCanvas]，原来各自一份
         * [GlassBackdrop]：同一帧里 10 个画布就会把整屏 CopyTexSubImage + 5 级降采样模糊
         * 重复执行 10 遍。背景本来就是"当前帧的画面"，所有画布拿同一张就够了。
         */
        @Volatile
        private var sharedInstance: GlassBackdrop? = null

        @JvmStatic
        fun shared(): GlassBackdrop =
            sharedInstance ?: GlassBackdrop().also { sharedInstance = it }

        /** 离开世界时释放整屏纹理（主菜单不需要一直占着几 MB 显存）。 */
        @JvmStatic
        fun releaseShared() {
            sharedInstance?.delete()
            sharedInstance = null
        }
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
