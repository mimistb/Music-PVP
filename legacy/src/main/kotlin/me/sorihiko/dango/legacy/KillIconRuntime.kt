package me.sorihiko.dango.legacy

import fpsport.killicon.KillIconModule
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.module.impls.music.KillIconHudModule

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiChat
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.world.World
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11

import net.minecraft.client.renderer.GlStateManager

import kotlin.math.abs

/**
 * 击杀图标（gd656killicon）在 1.8.9 上的运行层。
 *
 * 只负责把设置同步进 fpsport 的 public 字段，并在 4 个时机转调：
 * 每 tick / 击杀 / HUD 渲染 / 重置。所有回调都吞异常打日志，
 * 保证不会把异常抛进游戏循环；本类不创建任何 UI。
 */
object KillIconRuntime {

    private val mc: Minecraft get() = Minecraft.getMinecraft()

    private val effect = KillIconModule()

    /** 最近攻击过的目标与当时的 tick；用于客户端拿不到伤害来源时判定击杀 */
    private var lastAttacked: EntityLivingBase? = null
    private var lastAttackTick = 0
    private var ticks = 0

    private var lastWorld: World? = null
    private var wasEnabled = true

    init {
        try {
            effect.applyPreset(8)
            val names = effect.presets()
            if (names.isNotEmpty()) KillIconHudModule.presetLabels = names.copyOf()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music kill icon init failed: {}", t)
        }
    }

    /** 每个客户端 tick 调一次；漏掉会停在第一帧。 */
    fun tick() {
        try {
            ticks++
            val world = mc.theWorld
            if (world !== lastWorld) {
                lastWorld = world
                reset()
            }
            sync()

            if (!effect.enabled) {
                if (wasEnabled) reset()
                wasEnabled = false
            } else {
                wasEnabled = true
            }

            effect.onServerTick()
            checkAttackedDeath()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music kill icon tick failed: {}", t)
        }
    }

    /** 攻击事件：记录最近目标，作为击杀判定的兜底。 */
    fun onAttack(target: Entity) {
        try {
            if (!effect.enabled || target !is EntityLivingBase) return
            lastAttacked = target
            lastAttackTick = ticks
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music kill icon attack failed: {}", t)
        }
    }

    /** 死亡事件：自己死亡只清空；由自己造成的死亡播放击杀图标。 */
    fun onLivingDeath(e: LivingDeathEvent) {
        try {
            val victim = e.entity
            if (victim === mc.thePlayer) {
                reset()
                return
            }
            if (!effect.enabled) return
            if (isCredited(e.source.entity, victim)) {
                effect.onKill(1, victim.displayName.formattedText)
                lastAttacked = null
            }
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music kill icon death failed: {}", t)
        }
    }

    /** HUD 全部画完之后、动态模糊之前调用。宽高是 GUI 缩放后的像素。 */
    fun onRenderOverlay(e: RenderGameOverlayEvent.Post) {
        try {
            if (mc.thePlayer == null || mc.theWorld == null) return
            sync()
            val width = e.resolution.scaledWidth
            val height = e.resolution.scaledHeight
            // 打开聊天栏时允许直接拖动图标，锚点百分比随即写回设置
            handleDrag(width, height)
            effect.render(width, height, e.partialTicks)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music kill icon render failed: {}", t)
        }
    }

    // 设置页预览状态：上次预览的预设与上次触发动画的 tick
    private var previewPreset = -1
    private var previewTriggerTick = Int.MIN_VALUE / 2
    /** 最近一次预览的时间：预览期间压低音效，避免设置页一直响 */
    private var previewAt = 0L

    /**
     * 设置页里的实时预览：在窗口像素矩形内用真实渲染器画当前预设。
     * 会临时改锚点 / 尺寸，画完立刻还原，避免影响正常 HUD。
     */
    fun renderPreview(x: Int, y: Int, width: Int, height: Int, preset: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        // 先暂存再进 try：渲染抛异常时也要在 finally 里还原，避免预览锚点/尺寸残留到正常 HUD
        val oldX = effect.anchorXPercent
        val oldY = effect.anchorYPercent
        val oldSize = effect.size
        val oldDemo = effect.demoMode
        val oldEnabled = effect.enabled
        return try {
            val sr = ScaledResolution(mc)
            val gw = sr.scaledWidth
            val gh = sr.scaledHeight
            val dw = mc.displayWidth.toFloat().coerceAtLeast(1f)
            val dh = mc.displayHeight.toFloat().coerceAtLeast(1f)
            val sx = x.toFloat() / dw * gw
            val sy = y.toFloat() / dh * gh
            val sw = width.toFloat() / dw * gw
            val sh = height.toFloat() / dh * gh

            previewAt = System.currentTimeMillis()
            // 换预设或上一轮动画播完（约 5 秒）就重新触发一次，让预览持续有内容
            effect.enabled = true
            effect.demoMode = true
            if (previewPreset != preset) {
                previewPreset = preset
                effect.applyPreset(preset)
                previewTriggerTick = Int.MIN_VALUE / 2
            }
            if (ticks - previewTriggerTick > 100) {
                effect.onKill(1, "Dango")
                previewTriggerTick = ticks
            }
            effect.anchorXPercent = ((sx + sw / 2f) / gw) * 100f
            effect.anchorYPercent = ((sy + sh / 2f) / gh) * 100f
            effect.size = (sh * 1.15f).coerceIn(24f, 220f)

            GlStateManager.pushMatrix()
            GlStateManager.matrixMode(GL11.GL_PROJECTION)
            GlStateManager.pushMatrix()
            GlStateManager.loadIdentity()
            GlStateManager.ortho(0.0, gw.toDouble(), gh.toDouble(), 0.0, 1000.0, 3000.0)
            GlStateManager.matrixMode(GL11.GL_MODELVIEW)
            GlStateManager.pushMatrix()
            GlStateManager.loadIdentity()
            try {
                effect.render(gw, gh, 1f)
            } finally {
                GlStateManager.popMatrix()
                GlStateManager.matrixMode(GL11.GL_PROJECTION)
                GlStateManager.popMatrix()
                GlStateManager.matrixMode(GL11.GL_MODELVIEW)
                GlStateManager.popMatrix()
            }
            true
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music kill icon preview failed: {}", t)
            false
        } finally {
            effect.anchorXPercent = oldX
            effect.anchorYPercent = oldY
            effect.size = oldSize
            effect.demoMode = oldDemo
            effect.enabled = oldEnabled
        }
    }

    // 打开聊天栏时拖动图标：记录按下位置与偏移，移动时把锚点百分比写回设置
    private var draggingIcon = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    /** 模块实例注册后不会变：缓存起来，避免每帧都走 moduleManager 查找 */
    private var cachedModule: KillIconHudModule? = null

    private fun killIconModule(): KillIconHudModule? {
        cachedModule?.let { return it }
        return try {
            DangoMusic.instance.moduleManager.get(KillIconHudModule::class.java).also { cachedModule = it }
        } catch (_: Throwable) {
            null
        }
    }

    private fun handleDrag(width: Int, height: Int) {
        if (mc.currentScreen !is GuiChat) {
            draggingIcon = false
            return
        }
        val module = killIconModule() ?: run {
            draggingIcon = false
            return
        }
        if (!module.enabled) {
            draggingIcon = false
            return
        }
        val dw = mc.displayWidth.toFloat().coerceAtLeast(1f)
        val dh = mc.displayHeight.toFloat().coerceAtLeast(1f)
        val mx = Mouse.getX().toFloat() / dw * width
        val my = (mc.displayHeight - Mouse.getY() - 1).toFloat() / dh * height
        val cx = width * module.anchorXPercent.value / 100f
        val cy = height * module.anchorYPercent.value / 100f
        val half = (module.size.value / 2f).coerceAtLeast(18f)
        if (!Mouse.isButtonDown(0)) {
            draggingIcon = false
            return
        }
        if (!draggingIcon) {
            if (abs(mx - cx) <= half && abs(my - cy) <= half) {
                draggingIcon = true
                dragOffsetX = mx - cx
                dragOffsetY = my - cy
            }
        } else {
            module.anchorXPercent.setValue(((mx - dragOffsetX) / width * 100f).coerceIn(2f, 98f))
            module.anchorYPercent.setValue(((my - dragOffsetY) / height * 100f).coerceIn(2f, 98f))
        }
    }

    /** 清空当前播放状态；切服 / 死亡 / 关闭时调用。 */
    fun reset() {
        try {
            lastAttacked = null
            effect.reset()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music kill icon reset failed: {}", t)
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun sync() {
        val module = killIconModule() ?: return
        effect.enabled = module.enabled
        effect.playSound = module.playSound.get() && System.currentTimeMillis() - previewAt > 1500L
        effect.soundVolume = module.soundVolume.value
        effect.presetIndex = module.presetIndex.value
        effect.anchorXPercent = module.anchorXPercent.value
        effect.anchorYPercent = module.anchorYPercent.value
        effect.size = module.size.value
        effect.demoMode = module.demoMode.get()
    }

    private fun isCredited(sourceEntity: Entity?, victim: Entity): Boolean {
        if (sourceEntity === mc.thePlayer) return true
        val tracked = lastAttacked ?: return false
        return tracked === victim && ticks - lastAttackTick <= 200
    }

    /**
     * 1.8.9 客户端收到远程玩家死亡时不一定带伤害来源，
     * 所以每 tick 检查最近攻击目标是否已经死亡，避免漏掉击杀。
     */
    private fun checkAttackedDeath() {
        if (!effect.enabled) return
        val tracked = lastAttacked ?: return
        if (ticks - lastAttackTick > 200) {
            lastAttacked = null
            return
        }
        if (tracked.isDead || tracked.health <= 0f) {
            effect.onKill(1, tracked.displayName.formattedText)
            lastAttacked = null
        }
    }
}
