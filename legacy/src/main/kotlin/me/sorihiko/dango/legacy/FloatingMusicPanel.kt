package me.sorihiko.dango.legacy

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.utils.render.GlStateSnapshot
import me.sorihiko.dango.utils.render.RenderTarget

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiIngameMenu
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.entity.player.EntityPlayer
import net.minecraftforge.client.event.GuiOpenEvent
import net.minecraftforge.client.event.MouseEvent
import net.minecraftforge.event.entity.player.AttackEntityEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent

import org.lwjgl.BufferUtils
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.Display
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 悬浮音乐面板（事件驱动，不走 GuiScreen）：
 *
 * 打开瞬间把面板钉在玩家眼前的世界坐标上（法线 = 打开时的水平朝向，无 pitch 倾斜）。
 * 之后无论玩家怎么走动，面板都停在原位不动，靠近 / 远离完全由玩家自己决定；
 * 准星（屏幕中心）射线与这块固定的面板平面求交，命中后把像素坐标喂给 [MusicScreen] 完成点击。
 *
 * 关闭时清掉锚点，下次打开按当时的眼睛位置和朝向重新生成。
 *
 * 渲染：在 RenderWorldLastEvent 里（相机矩阵已就位），把整块 [MusicScreen] 画进 FBO 纹理，
 * 再按世界坐标（顶点 = 世界坐标 - renderManager.viewerPos）把纹理 quad 画到相机前。
 */
object FloatingMusicPanel {

    private val mc: Minecraft get() = Minecraft.getMinecraft()

    /** 配置缺失时的兜底距离（格） */
    private const val DEFAULT_DIST = 2.2

    /** 面板占屏幕高度的比例 */
    private const val HEIGHT_SCALE = 0.78

    private var open = false

    /** 打开瞬间冻结的面板世界坐标 / 朝向 / 尺寸；关闭时整体清空 */
    private var anchored = false
    private var anchorCX = 0.0
    private var anchorCY = 0.0
    private var anchorCZ = 0.0
    private var anchorNX = 0.0
    private var anchorNZ = 0.0
    private var anchorHalfW = 0.0
    private var anchorHalfH = 0.0

    /** 生成锚点时使用的距离，用于检测配置是否被改动 */
    private var anchorDist = DEFAULT_DIST
    /** 生成锚点时的窗口宽高比：窗口尺寸变化后必须重锚，否则面板会被拉扁甚至出屏 */
    private var anchorAspect = 0.0
    /** 生成锚点时的世界 / 玩家实例：换世界、重生后旧锚点可能离玩家几万格，等于永远看不见 */
    private var anchorWorld: Any? = null
    private var anchorPlayer: EntityPlayer? = null

    private var screen: MusicScreen? = null
    private var target: RenderTarget? = null
    private var targetW = 0
    private var targetH = 0

    private val viewportBuffer = BufferUtils.createIntBuffer(16)
    /** GL 状态快照复用：面板每帧都要抓一次状态再还原，没必要每帧 new */
    private val snap = GlStateSnapshot()

    /** 按下被面板消费的鼠标键（-1 = 无），用于把 mouseReleased 对齐到之前消费过的按下 */
    private var heldButton = -1
    private var pendingLeftClick = false
    private var pendingRightClick = false
    private var pendingRelease = false

    // ---------------------------------------------------------------- 开关

    fun toggle() {
        if (open) close() else openPanel()
    }

    fun openPanel() {
        if (open) return
        val player = mc.thePlayer ?: return
        open = true
        // 按打开瞬间的位置把面板钉住，之后不再跟随
        anchorPanel(player)
        val s = screen ?: DangoClient.newMusicScreen { close() }.also {
            it.floating = true
            screen = it
        }
        s.opened()
    }

    fun close() {
        if (!open) return
        open = false
        // 清除固定位置缓存，下次打开重新按玩家位置生成
        anchored = false
        heldButton = -1
        pendingLeftClick = false
        pendingRightClick = false
        pendingRelease = false
        try {
            screen?.closed()
        } catch (_: Throwable) {
        }
        try {
            target?.delete()
        } catch (_: Throwable) {
        }
        target = null
        targetW = 0
        targetH = 0
    }

    // ---------------------------------------------------------------- 几何

    /**
     * 面板几何 / 命中结果都做成可复用对象：这两个东西每帧各 new 一次（面板打开时），
     * 完全是稳定的小对象垃圾。字段逐个赋值，语义与原不可变版本一致。
     */
    private class PanelGeom {
        var cx = 0.0
        var cy = 0.0
        var cz = 0.0
        var nx = 0.0
        var nz = 0.0          // toPanel（ny = 0）
        var rx = 0.0
        var rz = 0.0          // right（ry = 0）
        var halfW = 0.0
        var halfH = 0.0
    }

    private class Hit {
        var u = 0f
        var v = 0f
    }

    private val geom = PanelGeom()
    private val hitResult = Hit()

    /** 当前配置的面板距离（格）；设置界面改动后即时生效 */
    private fun distance(): Double =
        NeteaseMusicModule.of()?.panelDistance?.value?.toDouble() ?: DEFAULT_DIST

    /** 实际使用的距离：夹到合理区间，避免配置为 0 / 负数 / 极端大值时面板贴脸或在雾外 */
    private fun panelDistance(): Double = distance().coerceIn(0.6, 16.0)

    /**
     * 相机（含第三人称）视线：眼睛坐标 + 单位朝向，按 partialTicks 插值。
     *
     * <p>必须用渲染相机而不是玩家实体：F5 前置视角下相机朝向与玩家朝向相反，
     * 用玩家 yaw/pitch 判断「面板在不在前方」会把可见的面板判成背后并整帧跳过渲染，
     * 准星拾取也会左右颠倒。
     */
    private class Cam {
        var eyeX = 0.0
        var eyeY = 0.0
        var eyeZ = 0.0
        var fx = 0.0
        var fy = 0.0
        var fz = 0.0
    }

    private val cam = Cam()

    private fun camera(partialTicks: Float): Cam? {
        val view = mc.renderViewEntity ?: mc.thePlayer ?: return null
        val pt = partialTicks.toDouble()
        val yaw = Math.toRadians(view.prevRotationYaw + (view.rotationYaw - view.prevRotationYaw) * pt)
        val pitch = Math.toRadians(view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * pt)
        val cosPitch = cos(pitch)
        cam.eyeX = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt
        cam.eyeY = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt + view.getEyeHeight()
        cam.eyeZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt
        cam.fx = -sin(yaw) * cosPitch
        cam.fy = -sin(pitch)
        cam.fz = cos(yaw) * cosPitch
        return cam
    }

    /** 把面板钉在玩家眼前的固定世界坐标上；之后玩家移动不影响它 */
    private fun anchorPanel(player: EntityPlayer) {
        val yaw = Math.toRadians(player.rotationYaw.toDouble())
        val nx = -sin(yaw)
        val nz = cos(yaw)
        val dist = panelDistance()

        val eyeX = player.posX
        val eyeY = player.posY + player.getEyeHeight()
        val eyeZ = player.posZ

        // 超宽 / 超窄窗口（分屏、窗口拖到极小）会让宽高比变成 0 或无穷，夹到合理区间
        val aspect = (mc.displayWidth.toDouble() / mc.displayHeight.coerceAtLeast(1).toDouble())
            .coerceIn(0.25, 4.0)
        val hFov = mc.gameSettings.fovSetting.toDouble().coerceIn(30.0, 120.0)
        val tanHalfH = tan(Math.toRadians(hFov / 2.0))
        val tanHalfV = tanHalfH / aspect
        anchorHalfH = dist * tanHalfV * HEIGHT_SCALE
        anchorHalfW = anchorHalfH * aspect

        anchorCX = eyeX + nx * dist
        anchorCY = eyeY
        anchorCZ = eyeZ + nz * dist
        anchorNX = nx
        anchorNZ = nz
        anchorDist = dist
        anchorAspect = aspect
        anchorWorld = mc.theWorld
        anchorPlayer = player
        anchored = true
    }

    /** 已冻结的面板几何；面板未打开时返回 null */
    private fun panelGeom(): PanelGeom? {
        if (!anchored) return null
        val g = geom
        g.cx = anchorCX
        g.cy = anchorCY
        g.cz = anchorCZ
        g.nx = anchorNX
        g.nz = anchorNZ
        g.rx = -anchorNZ
        g.rz = anchorNX
        g.halfW = anchorHalfW
        g.halfH = anchorHalfH
        return g
    }

    /** 准星射线与面板求交；[g] 由 [panelGeom] 得到 */
    private fun raycast(c: Cam, g: PanelGeom): Hit? {
        val dx = c.fx
        val dy = c.fy
        val dz = c.fz

        val eyeX = c.eyeX
        val eyeY = c.eyeY
        val eyeZ = c.eyeZ

        val denom = dx * g.nx + dz * g.nz
        if (denom <= 1e-6) return null
        // 与面板所在的竖直平面求交：面板中心已固定，不再按「距眼睛 DIST」推算
        val t = ((g.cx - eyeX) * g.nx + (g.cz - eyeZ) * g.nz) / denom
        if (t <= 0.0) return null
        val hx = eyeX + dx * t
        val hy = eyeY + dy * t
        val hz = eyeZ + dz * t

        val ox = hx - g.cx
        val oy = hy - g.cy
        val oz = hz - g.cz
        val x = ox * g.rx + oz * g.rz
        val y = oy

        val u = (x / g.halfW * 0.5 + 0.5).toFloat()
        val v = (y / g.halfH * 0.5 + 0.5).toFloat()
        if (u < 0f || u > 1f || v < 0f || v > 1f) return null
        val h = hitResult
        h.u = u
        h.v = v
        return h
    }

    private fun isCrosshairOnPanel(): Boolean {
        if (!open) return false
        val c = camera(1f) ?: return false
        val g = panelGeom() ?: return false
        return raycast(c, g) != null
    }

    /** 面板中心是否位于相机前方（点积 > 0）；背后的面板被裁掉，没必要渲染 */
    private fun panelInFront(c: Cam, g: PanelGeom): Boolean {
        val vx = g.cx - c.eyeX
        val vy = g.cy - c.eyeY
        val vz = g.cz - c.eyeZ
        return c.fx * vx + c.fy * vy + c.fz * vz > 0.0
    }

    // ---------------------------------------------------------------- 输入

    /**
     * 有界面打开（聊天栏 / 背包 / 设置等）时鼠标是自由光标。
     *
     * 此时面板的世界射线拾取没有任何意义：射线是从准星发出的，跟光标位置无关，
     * 因此点击屏幕任意位置（包括 HUD / HUD2 界面）都会被误判成「点中面板」。
     * 必须整段拒绝。
     *
     * 窗口失焦（Alt+Tab / 点出窗口）时原版不再处理鼠标事件，等同于「界面打开」。
     */
    private fun guiOpen(): Boolean =
        mc.currentScreen != null || !mc.inGameHasFocus || !Display.isActive()

    /** 返回 true 表示本次鼠标事件被面板消费（ForgeBridge 据此跳过其它处理） */
    fun onMouse(e: MouseEvent): Boolean {
        if (!open) return false
        if (guiOpen()) {
            heldButton = -1
            return false
        }
        val s = screen ?: return false
        val c = camera(1f) ?: return false

        // 滚轮：只做界面内的滚动，不改变面板的空间位置
        if (e.dwheel != 0) {
            val hit = raycast(c, panelGeom() ?: return false) ?: return false
            val w = mc.displayWidth
            val h = mc.displayHeight
            s.scroll(e.dwheel, (hit.u * w).toInt(), ((1f - hit.v) * h).toInt())
            if (e.isCancelable) e.isCanceled = true
            return true
        }

        // Forge 1.8.9 会对「纯移动」也派发 MouseEvent（button == -1、buttonstate == false、
        // dwheel == 0）。这类事件必须整段忽略，否则会被下面的松开分支当成 mouseReleased。
        if (e.button < 0) return false

        if (e.buttonstate) {
            if (e.button != 0 && e.button != 1) return false
            if (raycast(c, panelGeom() ?: return false) == null) return false
            // 按下只记录按键；面板不支持长按选中 / 拖动，松开时按一次普通点击转发
            heldButton = e.button
            if (e.isCancelable) e.isCanceled = true
            return true
        }

        // 只处理「本次按下被面板消费过的那一个按键」的松开
        if (heldButton < 0 || e.button != heldButton) return false
        if (heldButton == 0) pendingLeftClick = true else pendingRightClick = true
        pendingRelease = true
        heldButton = -1
        if (e.isCancelable) e.isCanceled = true
        return true
    }

    fun onKeyInput() {
        if (!open) return
        val s = screen ?: return
        if (!Keyboard.getEventKeyState()) return
        if (Keyboard.isRepeatEvent()) return
        val key = Keyboard.getEventKey()
        if (key == 0) return
        // Esc 走 onGuiOpen：这里不处理，否则面板关闭后本 tick 的暂停菜单仍会弹出
        if (key == Keyboard.KEY_ESCAPE) return
        s.keyTyped(Keyboard.getEventCharacter(), key)
    }

    /** Esc 关闭面板：拦截原版暂停菜单（currentScreen == null 时按 Esc 会打开 GuiIngameMenu） */
    fun onGuiOpen(e: GuiOpenEvent) {
        if (!open) return
        if (e.gui is GuiIngameMenu) {
            e.isCanceled = true
            close()
        }
    }

    // ---------------------------------------------------------------- 攻击 / 交互抑制

    /** 准星落在面板上时，点击面板不应同时挖方块 / 打怪 / 放方块 */
    fun onAttackEntity(e: AttackEntityEvent) {
        if (!open) return
        if (isCrosshairOnPanel()) e.isCanceled = true
    }

    fun onPlayerInteract(e: PlayerInteractEvent) {
        if (!open) return
        if (isCrosshairOnPanel()) e.isCanceled = true
    }

    // ---------------------------------------------------------------- 渲染

    fun onRenderWorld(partialTicks: Float) {
        if (!open) return
        val player = mc.thePlayer ?: return
        val w = mc.displayWidth
        val h = mc.displayHeight
        if (w <= 0 || h <= 0) return
        val s = screen ?: return

        // 设置里的「悬浮距离」被改动时，按玩家当前位置重新锚定一次（打开期间仍保持固定）；
        // 换世界 / 重生 / 改窗口尺寸后旧锚点会失效（离玩家几万格或被拉成冰箱贴），
        // 这三种情况同样必须重锚，否则表现为「面板打开了但屏幕上看不到」。
        val aspect = w.toDouble() / h.toDouble()
        if (panelDistance() != anchorDist || mc.theWorld !== anchorWorld
                || mc.thePlayer !== anchorPlayer || kotlin.math.abs(aspect - anchorAspect) > 0.01) {
            anchorPanel(player)
        }
        val c = camera(partialTicks) ?: return
        val g = panelGeom() ?: return
        // 面板转到相机背后时完全不可见：跳过整帧「全分辨率 FBO + 整套 UI 排版」。
        // 这部分开销跟屏幕分辨率同阶，玩家背对面板时没必要付。
        if (!panelInFront(c, g)) {
            s.pointerActive = false
            return
        }
        // 界面打开时鼠标是自由光标：不要再用准星坐标覆盖它（否则悬停高亮会跟着准星乱跳）
        val gui = guiOpen()
        val hit = if (gui) null else raycast(c, g)

        // 每帧把准星命中的像素坐标喂给界面（未命中给一个不可能命中的值）
        s.pointerActive = !gui
        if (hit != null) {
            s.pointerX = (hit.u * w).toInt()
            s.pointerY = ((1f - hit.v) * h).toInt()
        } else {
            s.pointerX = -100000
            s.pointerY = -100000
        }

        snap.capture()
        val previousFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewportBuffer)
        var rt: RenderTarget? = null
        var rendered = false
        try {
            try {
                // 1) UI 画进 FBO
                val rtNow = ensureTarget(w, h)
                rt = rtNow
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, rtNow.fbo)
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousFbo)
                GL11.glViewport(0, 0, w, h)
                GL11.glClearColor(0f, 0f, 0f, 0f)
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
                s.render()
                rendered = true
            } catch (t: Throwable) {
                me.sorihiko.dango.DangoLogger.error("Dango Music floating panel render failed: {}", t)
            } finally {
                // 2) 恢复 FBO / 视口：s.render() 抛异常时也必须还原，否则本帧之后的世界、手、
                //    HUD 会全部画进面板纹理里（画面像是卡死 / 全黑），直到关掉面板才恢复
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo)
                GL11.glViewport(viewportBuffer.get(0), viewportBuffer.get(1), viewportBuffer.get(2), viewportBuffer.get(3))
            }
            // 3) 世界空间画纹理 quad（相机矩阵已就位）
            val texTarget = rt
            if (rendered && texTarget != null && texTarget.fbo != -1) {
                try {
                    drawQuad(g, texTarget.framebufferTexture)
                } catch (t: Throwable) {
                    me.sorihiko.dango.DangoLogger.error("Dango Music floating panel quad failed: {}", t)
                }
            }
        } finally {
            // 4) 统一还原 GL 状态。drawQuad 用裸 GL 关掉了 depth / depthMask / cull / lighting，
            //    而 RenderWorldLast 之后同帧紧接着就是原版 renderHand：不还原的话手部会
            //    无深度排序、双面、无光照地画出来，表现为一块纯黑剪影。
            snap.restore()
        }

        // 点击要等 s.render() 刷新 ui.mx/ui.my 之后再转发
        if (pendingLeftClick) {
            pendingLeftClick = false
            s.mouseClicked(0)
        }
        if (pendingRightClick) {
            pendingRightClick = false
            s.mouseClicked(1)
        }
        if (pendingRelease) {
            pendingRelease = false
            s.mouseReleased()
        }
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

    private fun drawQuad(g: PanelGeom, texId: Int) {
        val rm = mc.renderManager
        val vx = rm.viewerPosX
        val vy = rm.viewerPosY
        val vz = rm.viewerPosZ

        GL20.glUseProgram(0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDepthMask(false)
        GL11.glDisable(GL11.GL_CULL_FACE)
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glDisable(GL11.GL_LIGHTING)
        GL11.glDisable(GL11.GL_FOG)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId)
        GL11.glColor4f(1f, 1f, 1f, 1f)

        val hw = g.halfW
        val hh = g.halfH
        val rx = g.rx
        val rz = g.rz
        try {
            val tess = Tessellator.getInstance()
            val wr = tess.worldRenderer
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX)
            wr.pos(g.cx - rx * hw - vx, g.cy - hh - vy, g.cz - rz * hw - vz).tex(0.0, 0.0).endVertex()
            wr.pos(g.cx + rx * hw - vx, g.cy - hh - vy, g.cz + rz * hw - vz).tex(1.0, 0.0).endVertex()
            wr.pos(g.cx + rx * hw - vx, g.cy + hh - vy, g.cz + rz * hw - vz).tex(1.0, 1.0).endVertex()
            wr.pos(g.cx - rx * hw - vx, g.cy + hh - vy, g.cz - rz * hw - vz).tex(0.0, 1.0).endVertex()
            tess.draw()
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        }
    }
}
