package me.sorihiko.dango.legacy

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.legacy.gale.RotationEngine
import me.sorihiko.dango.legacy.gale.RotationInputHook
import me.sorihiko.dango.module.impls.music.GaleAntiAimModule
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.AbstractClientPlayer
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.Entity
import net.minecraftforge.client.event.RenderPlayerEvent

/**
 * GaleAntiAim 的 1.8.9 运行时接线。
 *
 * 时序完全按移植说明：
 *  - ClientTickEvent START → 输入钩子安装 / 卸载
 *  - LivingUpdateEvent（本人） → beginTick → request → apply → ensure
 *  - ClientTickEvent END → endTick
 *  - RenderPlayerEvent.Pre(HIGHEST) → LocalSpin 绕 Y 轴渲染
 *  - NetworkManager 收包前 → RotationEngine.interceptReceive
 *
 * 全局开关就是模块的 enabled：关闭后下一 tick 立即停止请求、摘掉输入钩子并恢复相机。
 */
object GaleAntiAimRuntime {

    private val mc = Minecraft.getMinecraft()

    /** 本地旋转的计时起点与上一次的开关状态 */
    private var spinEpoch = 0L
    private var spinWasOn = false
    private var spinNested = false

    /** 按键边沿状态（按下瞬间只触发一次） */
    private var spinKeyWasDown = false
    private var wasEnabled = false

    // ---------------------------------------------------------------- tick

    fun tickStart() {
        val module = GaleAntiAimModule.of() ?: return
        if (!module.enabled) {
            if (wasEnabled) {
                RotationEngine.reset()
                RotationInputHook.ensure(mc.thePlayer)
                spinKeyWasDown = false
                spinWasOn = false
                wasEnabled = false
            }
            return
        }
        wasEnabled = true
        syncSettings(module)
        RotationInputHook.ensure(mc.thePlayer)
    }

    fun tickEnd() {
        val module = GaleAntiAimModule.of() ?: return
        if (!module.enabled) return
        RotationEngine.endTick()

        // 本地旋转切换键：按下沿触发；打开任意界面时忽略，避免在设置里录键时误切换
        val inGame = mc.currentScreen == null
        val down = inGame && try {
            module.spinKey.isDown(DangoMusic.instance.platform)
        } catch (_: Throwable) {
            false
        }
        if (down && !spinKeyWasDown) module.toggleSpin()
        spinKeyWasDown = down

        // LocalSpin 打开时重置计时，保证每次都从 0° 开始
        val spinOn = module.localSpin.get()
        if (spinOn && !spinWasOn) spinEpoch = System.nanoTime()
        spinWasOn = spinOn
    }

    fun onLivingUpdate(entity: Entity?) {
        val module = GaleAntiAimModule.of() ?: return
        val player = mc.thePlayer ?: return
        if (!module.enabled || entity !== player) return
        syncSettings(module)
        RotationEngine.beginTick()
        // 按住静默转头键：服务端朝向伪装为相机背后，相机本身不动
        val held = try { module.silentKey.isDown(DangoMusic.instance.platform) } catch (_: Throwable) { false }
        if (held) {
            RotationEngine.request(this, player.rotationYaw + 180f, player.rotationPitch, PRIORITY)
        }
        RotationEngine.apply()
        RotationInputHook.ensure(player)
    }

    /** 设置项实时写入引擎：UI 改动后下一 tick 即生效，无需重启 */
    private fun syncSettings(module: GaleAntiAimModule) {
        RotationEngine.gcdFix = module.gcdFix.get()
        RotationEngine.releaseSpeed = module.releaseSpeed.value
        RotationEngine.defaultFix = when (module.moveFix.value) {
            1 -> RotationEngine.MoveFix.EXACT
            2 -> RotationEngine.MoveFix.NONE
            else -> RotationEngine.MoveFix.LEGIT
        }
    }

    // ---------------------------------------------------------------- render

    fun onRenderPlayerPre(event: RenderPlayerEvent.Pre) {
        val module = GaleAntiAimModule.of() ?: return
        if (!module.enabled || !module.localSpin.get()) return
        if (spinNested || event.entityPlayer != mc.thePlayer) return
        val player = event.entityPlayer
        if (!player.isEntityAlive) return
        // 第一人称绝不能重绘自身模型：CameraSetup / 事件派发的时机差异会让整张皮肤被画进第一人称画面
        if (mc.getRenderViewEntity() === player && mc.gameSettings.thirdPersonView == 0) return
        // 隐身交给原版处理，手动重绘会无视隐身把皮肤画出来
        if (player.isInvisible) return
        event.isCanceled = true
        spinNested = true

        // 与 RenderManager 一致：传插值后的 yaw。直接用 rotationYaw 会让模型（含披风层）
        // 在渲染帧边界来回抖动、看起来和身体错位/穿模。
        val partial = event.partialRenderTick
        val renderYaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partial
        val spin = spinAngle(module.spinSpeed.value)

        // 1.8.9 的 RenderPlayer#doRender 里，身体朝向取自 renderYawOffset，头部取自
        // rotationYawHead；披风层 LayerCape 的摆动方向也用 renderYawOffset 做投影。
        // 所以旋转量必须折进这两个字段，而不是在外层再 GL.rotate 一次：
        //   - 只做外层 GL 旋转 → 披风摆动方向仍是未旋转的身体坐标系，表现为「披风乱飘/方向随机」；
        //   - 外层旋转 + 改字段 → 身体被转两次，转得比设定速度还快。
        val oldOffset = player.renderYawOffset
        val oldPrevOffset = player.prevRenderYawOffset
        val oldHead = player.rotationYawHead
        val oldPrevHead = player.prevRotationYawHead
        // 符号取负：RenderPlayer 里画的是 rotate(180° - renderYawOffset)，
        // yaw 增大 = 模型逆时针转，所以只有减 spin 才和原来 GL.rotate(+spin) 的转动方向一致。
        player.renderYawOffset = oldOffset - spin
        player.prevRenderYawOffset = oldPrevOffset - spin
        player.rotationYawHead = oldHead - spin
        player.prevRotationYawHead = oldPrevHead - spin

        try {
            GlStateManager.color(1f, 1f, 1f, 1f)
            event.renderer.doRender(
                player as AbstractClientPlayer, event.x, event.y, event.z,
                renderYaw, partial,
            )
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music local spin render failed: {}", t)
        } finally {
            player.renderYawOffset = oldOffset
            player.prevRenderYawOffset = oldPrevOffset
            player.rotationYawHead = oldHead
            player.prevRotationYawHead = oldPrevHead
            spinNested = false
        }
    }

    private fun spinAngle(speed: Float): Float {
        if (spinEpoch == 0L) spinEpoch = System.nanoTime()
        return (((System.nanoTime() - spinEpoch) / 1.0e9 * speed) % 360.0).toFloat()
    }

    // ---------------------------------------------------------------- packets

    /** NetworkManager 收包前调用（ASM 注入），把 S08 换成 Teleport 包装 */
    @JvmStatic
    fun interceptPacket(packet: net.minecraft.network.Packet<*>): net.minecraft.network.Packet<*> =
        RotationEngine.interceptReceive(packet)

    private const val PRIORITY = 1000
}
