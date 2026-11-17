package me.sorihiko.dango.legacy

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.utils.render.GlCaps
import me.sorihiko.dango.utils.render.GlStateSnapshot
import me.sorihiko.dango.utils.render.RenderTarget

import net.minecraft.client.gui.GuiScreen
import org.lwjgl.BufferUtils
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL30
import org.lwjgl.util.vector.Matrix4f
import org.lwjgl.util.vector.Vector3f
import org.lwjgl.util.vector.Vector4f
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tan

/** 内置播放器在 1.8.9 里的外壳：把原版界面的事件转给共用的 [me.sorihiko.dango.music.MusicScreen] */
class LegacyMusicScreen @JvmOverloads constructor(
    /** 关闭后回到哪个界面（null = 回到游戏） */
    private val parent: GuiScreen? = null,
    /** 打开后直接进入设置页（主菜单右上角的功能入口用） */
    private val openSettings: Boolean = false,
    /**
     * 悬浮面板：把整块播放器界面渲染进纹理，再以透视画到游戏画面上。
     * 主菜单入口没有游戏画面，传 false 走原来的全屏界面。
     */
    private val floating: Boolean = false,
) : GuiScreen() {

    private val screen = DangoClient.newMusicScreen { mc.displayGuiScreen(parent) }
    private var openedOnce = false

    init {
        if (floating) screen.floating = true
    }

    // ---------------------------------------------------------------- 悬浮面板

    private var target: RenderTarget? = null
    private var targetW = 0
    private var targetH = 0
    private val panelOpenedAt = System.currentTimeMillis()
    private val matrixBuffer = BufferUtils.createFloatBuffer(16)
    private val viewportBuffer = BufferUtils.createIntBuffer(16)
    /** 一次性诊断：悬浮面板看不见时把关键状态写到 config 目录，便于定位 */
    private var debugDumped = false
    private val debugPixels = BufferUtils.createByteBuffer(4)
    /** 鼠标是否落在面板上，以及投影后的窗口像素坐标 */
    private var pointerInside = false
    private var pointerX = 0f
    private var pointerY = 0f

    override fun initGui() {
        Keyboard.enableRepeatEvents(true)
        // 悬浮面板打开时世界照常运行：允许原版把 WASD / 跳跃 / 潜行的按键状态喂给玩家
        // （和聊天框打开时能走路是同一套逻辑，纯客户端读取，不改变任何移动数值）
        if (floating) allowUserInput = true
        // 窗口 resize 会重新走 initGui；只有第一次打开才做加载与跳转设置页
        if (!openedOnce) {
            openedOnce = true
            screen.opened()
            if (openSettings) screen.openSettings()
        }
        debugDumped = false
    }

    override fun onGuiClosed() {
        Keyboard.enableRepeatEvents(false)
        // 被断线 / 其它界面顶掉时也要提交输入并落盘
        screen.closed()
        target?.delete()
        target = null
        targetW = 0
        targetH = 0
    }

    override fun doesGuiPauseGame() = false

    /**
     * 界面开着时世界仍在跑，但原版只在"游戏界面或允许输入的界面"里更新移动输入。
     * 这里再手动喂一次，保证悬浮面板打开时角色可以正常走动（重复调用是幂等的）。
     */
    override fun updateScreen() {
        super.updateScreen()
        if (!floating) return
        try {
            mc.thePlayer?.movementInput?.updatePlayerMoveState()
        } catch (_: Throwable) {
            // 玩家还没生成 / 界面被顶掉时不处理
        }
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (!floating) {
            screen.render()
            return
        }
        val w = mc.displayWidth
        val h = mc.displayHeight
        if (w <= 0 || h <= 0) return

        val p = panelFor(w, h)
        mapPointer(p, w, h)
        // 鼠标在面板外时给一个不可能命中的坐标，避免界面按真实鼠标位置误判悬停
        screen.pointerActive = true
        screen.pointerX = if (pointerInside) pointerX.toInt() else -100000
        screen.pointerY = if (pointerInside) pointerY.toInt() else -100000

        val outer = GlStateSnapshot()
        var fboPixel = "n/a"
        var fboError = 0
        try {
            // 先记录当前 FBO / 视口，再创建（创建过程会把绑定重置为 0）
            val previousFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
            GL11.glGetInteger(GL11.GL_VIEWPORT, viewportBuffer)
            val rt = ensureTarget(w, h)
            try {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, rt.fbo)
                // 玻璃背景仍从主画面抓：绘制 FBO 与读取 FBO 分开绑定
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousFbo)
                GL11.glViewport(0, 0, w, h)
                GL11.glClearColor(0f, 0f, 0f, 0f)
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
                screen.render()
                // 采样 FBO 中心像素：判断界面到底有没有画进纹理
                debugPixels.clear()
                GL11.glReadPixels(w / 2, h / 2, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, debugPixels)
                fboPixel = "${debugPixels.get(0).toInt() and 0xFF},${debugPixels.get(1).toInt() and 0xFF}," +
                    "${debugPixels.get(2).toInt() and 0xFF},${debugPixels.get(3).toInt() and 0xFF}"
                fboError = GL11.glGetError()
            } finally {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo)
                GL11.glViewport(viewportBuffer.get(0), viewportBuffer.get(1), viewportBuffer.get(2), viewportBuffer.get(3))
            }
            drawPanel(rt, p)
            dumpDebug(
                "draw-ok fbo=${rt.fbo} tex=${rt.framebufferTexture} size=${w}x$h " +
                    "half=${p.halfW}x${p.halfH} fboPixel=$fboPixel fboErr=$fboError quadErr=${GL11.glGetError()}"
            )
        } catch (t: Throwable) {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            dumpDebug("draw-throw\n$sw")
            // 兜底：悬浮渲染失败时退回全屏界面，至少保证能用
            try {
                screen.render()
            } catch (_: Throwable) {
            }
        } finally {
            outer.restore()
        }
    }

    /** 只在悬浮面板第一帧写一次诊断文件，路径：config/Music PVP/panel_debug.txt */
    private fun dumpDebug(text: String) {
        if (debugDumped) return
        debugDumped = true
        try {
            val dir = me.sorihiko.dango.DangoMusic.instance.clientDir
            java.io.File(dir.toFile(), "panel_debug.txt").writeText(text)
        } catch (_: Throwable) {
        }
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        screen.mouseClicked(mouseButton)
    }

    override fun mouseClickMove(mouseX: Int, mouseY: Int, clickedMouseButton: Int, timeSinceLastClick: Long) {
        screen.mouseDragged()
    }

    override fun mouseReleased(mouseX: Int, mouseY: Int, state: Int) {
        super.mouseReleased(mouseX, mouseY, state)
        screen.mouseReleased()
    }

    override fun handleMouseInput() {
        super.handleMouseInput()
        val wheel = Mouse.getEventDWheel()
        if (wheel == 0) return
        if (floating) {
            if (pointerInside) screen.scroll(wheel, pointerX.toInt(), pointerY.toInt())
        } else {
            screen.scroll(wheel, Mouse.getEventX(), mc.displayHeight - Mouse.getEventY() - 1)
        }
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        screen.keyTyped(typedChar, keyCode)
    }

    // ---------------------------------------------------------------- 悬浮面板：渲染

    private class Panel(
        val projection: Matrix4f,
        val modelView: Matrix4f,
        val inverse: Matrix4f?,
        val halfW: Float,
        val halfH: Float,
    )

    /**
     * 面板每帧的透视参数：画面正前方约 1.6 格处的平面，占屏高约 80%，
     * 轻微左右 / 上下偏转 + 很慢的呼吸摆动，和视频里的悬浮感一致。
     */
    private fun panelFor(w: Int, h: Int): Panel {
        val aspect = w.toFloat() / h.toFloat()
        val fovY = Math.toRadians(50.0).toFloat()
        val near = 0.05f
        val far = 50f
        val dist = 1.6f

        // 打开后 320ms 从稍远、更偏的角度落到最终姿态
        val age = (System.currentTimeMillis() - panelOpenedAt).coerceAtLeast(0L)
        val k = (age / 320f).coerceIn(0f, 1f)
        val ease = 1f - (1f - k) * (1f - k) * (1f - k)
        val t = age / 1000f
        val yaw = (-6f + sin(t * 0.62f) * 1.4f) * ease
        val pitch = (2.4f + sin(t * 0.47f) * 0.8f) * ease

        val tanHalf = tan(fovY / 2.0).toFloat()
        val visibleHalfH = dist * tanHalf
        val scale = 0.82f + (1f - ease) * 0.05f
        val halfH = visibleHalfH * 0.80f * scale
        val halfW = halfH * aspect

        val projection = Matrix4f()
        projection.m00 = 1f / (aspect * tanHalf)
        projection.m11 = 1f / tanHalf
        projection.m22 = (far + near) / (near - far)
        // LWJGL 的 Matrix4f 是列主序命名：m23 是「第 2 列、第 3 行」= w 行的 z 系数，
        // m32 是「第 3 列、第 2 行」= z 行的常数项。写反会让整个面板被裁到屏幕外。
        projection.m23 = -1f
        projection.m32 = 2f * far * near / (near - far)
        projection.m33 = 0f

        val modelView = Matrix4f()
        modelView.translate(Vector3f(visibleHalfH * aspect * 0.035f, visibleHalfH * 0.01f, -dist))
        modelView.rotate(Math.toRadians(yaw.toDouble()).toFloat(), Vector3f(0f, 1f, 0f))
        modelView.rotate(Math.toRadians(pitch.toDouble()).toFloat(), Vector3f(1f, 0f, 0f))

        val inverse = try {
            Matrix4f.invert(Matrix4f.mul(projection, modelView, null), null)
        } catch (_: Throwable) {
            null
        }
        return Panel(projection, modelView, inverse, halfW, halfH)
    }

    private fun ensureTarget(w: Int, h: Int): RenderTarget {
        var rt = target
        if (rt == null) {
            rt = RenderTarget(true, false)
            target = rt
        }
        if (rt.fbo == -1 || w != targetW || h != targetH) {
            rt.create(w, h)
            targetW = w
            targetH = h
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            rt.bindTexture()
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        }
        return rt
    }

    private fun drawPanel(rt: RenderTarget, p: Panel) {
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        loadMatrix(p.projection)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        loadMatrix(p.modelView)
        // 下面全用裸 GL 改开关，必须原样还原：RenderWorldLast 之后同帧紧接着就是原版
        // renderHand，depth / cull / lighting 留在这里没恢复的话，第一人称手部会无深度排序、
        // 双面、无光照地画出来，表现为一块纯黑剪影（而且 GlStateManager 缓存会被弄脏）。
        val caps = GlCaps.save()
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDisable(GL11.GL_CULL_FACE)
            GL11.glDisable(GL11.GL_ALPHA_TEST)
            GL11.glDisable(GL11.GL_FOG)
            GL11.glDisable(GL11.GL_LIGHTING)
            GL11.glEnable(GL11.GL_BLEND)
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
            GL11.glEnable(GL11.GL_TEXTURE_2D)
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, rt.framebufferTexture)
            GL11.glColor4f(1f, 1f, 1f, 1f)

            GL11.glBegin(GL11.GL_QUADS)
            // 纹理 v=0 是 FBO 底部（界面最下面），v=1 是界面顶部
            GL11.glTexCoord2f(0f, 0f); GL11.glVertex3f(-p.halfW, -p.halfH, 0f)
            GL11.glTexCoord2f(1f, 0f); GL11.glVertex3f(p.halfW, -p.halfH, 0f)
            GL11.glTexCoord2f(1f, 1f); GL11.glVertex3f(p.halfW, p.halfH, 0f)
            GL11.glTexCoord2f(0f, 1f); GL11.glVertex3f(-p.halfW, p.halfH, 0f)
            GL11.glEnd()

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        } finally {
            GlCaps.restore(caps)
            GL11.glMatrixMode(GL11.GL_MODELVIEW)
            GL11.glPopMatrix()
            GL11.glMatrixMode(GL11.GL_PROJECTION)
            GL11.glPopMatrix()
        }
    }

    private fun loadMatrix(m: Matrix4f) {
        matrixBuffer.clear()
        m.store(matrixBuffer)
        matrixBuffer.flip()
        GL11.glLoadMatrix(matrixBuffer)
    }

    // ---------------------------------------------------------------- 悬浮面板：鼠标投影

    /** 把鼠标射线投到面板平面上，得到界面里的窗口像素坐标 */
    private fun mapPointer(p: Panel, w: Int, h: Int) {
        pointerInside = false
        val inv = p.inverse ?: return
        val nx = Mouse.getX() * 2f / w - 1f
        // Mouse.getY() 原点在左下，正好是 NDC 的方向
        val ny = Mouse.getY() * 2f / h - 1f
        val near = unproject(inv, nx, ny, -1f) ?: return
        val far = unproject(inv, nx, ny, 1f) ?: return
        val dz = far.z - near.z
        if (abs(dz) < 1e-6f) return
        val t = -near.z / dz
        if (t < 0f || t > 1f) return
        val lx = near.x + (far.x - near.x) * t
        val ly = near.y + (far.y - near.y) * t
        val u = lx / p.halfW * 0.5f + 0.5f
        val v = ly / p.halfH * 0.5f + 0.5f
        if (u < 0f || u > 1f || v < 0f || v > 1f) return
        pointerX = u * w
        pointerY = (1f - v) * h
        pointerInside = true
    }

    private fun unproject(inv: Matrix4f, x: Float, y: Float, z: Float): Vector3f? {
        val v = Vector4f(x, y, z, 1f)
        Matrix4f.transform(inv, v, v)
        if (v.w == 0f) return null
        return Vector3f(v.x / v.w, v.y / v.w, v.z / v.w)
    }
}
