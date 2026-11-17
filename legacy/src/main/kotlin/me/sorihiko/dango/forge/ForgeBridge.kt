package me.sorihiko.dango.forge

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.core.HudAboveScreenHook
import me.sorihiko.dango.events.HudRenderEvent
import me.sorihiko.dango.events.TickEvent
import me.sorihiko.dango.events.WorldRenderEndEvent
import me.sorihiko.dango.legacy.BedWarsRuntime
import me.sorihiko.dango.legacy.AntiSnipeRuntime
import me.sorihiko.dango.legacy.BloodParticlesRuntime
import me.sorihiko.dango.legacy.FloatingMusicPanel
import me.sorihiko.dango.legacy.FpsPortRuntime
import me.sorihiko.dango.legacy.KillIconRuntime
import me.sorihiko.dango.legacy.ChatTranslateRuntime
import me.sorihiko.dango.legacy.CustomHeldItemsRuntime
import me.sorihiko.dango.legacy.GaleAntiAimRuntime
import me.sorihiko.dango.legacy.LegacyGl
import me.sorihiko.dango.legacy.LegacyModuleRuntime
import me.sorihiko.dango.legacy.MechvibesRuntime
import me.sorihiko.dango.legacy.NametagStatsRenderer
import me.sorihiko.dango.module.impls.music.TabStatsHudModule
import me.sorihiko.dango.ui.HudReveal
import me.sorihiko.dango.ui.glass.GlassBackdrop

import net.minecraft.client.Minecraft
import net.minecraft.entity.EntityLivingBase
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.client.event.EntityViewRenderEvent
import net.minecraftforge.client.event.GuiOpenEvent
import net.minecraftforge.client.event.MouseEvent
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.client.event.RenderLivingEvent
import net.minecraftforge.client.event.RenderPlayerEvent
import net.minecraftforge.client.event.RenderWorldLastEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent
import net.minecraftforge.event.entity.living.LivingHurtEvent
import net.minecraftforge.event.entity.player.AttackEntityEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.fml.common.eventhandler.EventPriority
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.InputEvent
import net.minecraftforge.fml.common.gameevent.TickEvent as ForgeTickEvent

/**
 * 把 Forge 的事件转成 mod 自己的事件：
 *   ClientTickEvent(START) → TickEvent（对应原来注入在 Minecraft.runTick 开头）
 *   RenderWorldLastEvent   → WorldRenderEndEvent（对应原来注入在 renderWorldPass 末尾、画手之前）
 *
 * 另外把 PVP 工具需要的几个事件直接转给 [FpsPortRuntime]。
 */
class ForgeBridge {

    @SubscribeEvent
    fun onClientTick(e: ForgeTickEvent.ClientTickEvent) {
        if (e.phase != ForgeTickEvent.Phase.START) return
        try {
            LegacyGl.init()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music GL init failed: {}", t)
        }
        // HUD 呼出动画的起点：刚进世界那一 tick 记下来，所有模块共用同一条曲线
        try {
            HudReveal.tick(Minecraft.getMinecraft().theWorld != null)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music hud reveal tick failed: {}", t)
        }
        try {
            HitColorBridge.tick()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music hit color tick failed: {}", t)
        }
        // 静默旋转：tick 开始时确保输入修正钩子处于正确状态
        try {
            GaleAntiAimRuntime.tickStart()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music gale tick failed: {}", t)
        }
        DangoMusic.callEvent(TickEvent)
        // 床提醒 / Hypixel 统计：每 tick 取一次游戏数据（设置值已由 BedWarsModule 同步）
        try {
            BedWarsRuntime.tick()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music bedwars tick failed: {}", t)
        }
        try {
            FpsPortRuntime.tick()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music fps port tick failed: {}", t)
        }
        try {
            KillIconRuntime.tick()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music kill icon tick failed: {}", t)
        }
        try {
            CustomHeldItemsRuntime.tick()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music custom held items tick failed: {}", t)
        }
        try {
            LegacyModuleRuntime.tick()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music ported module tick failed: {}", t)
        }
        // 反作弊 / 自动黑名单 / 狙击预警
        try {
            AntiSnipeRuntime.tick()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music anti snipe tick failed: {}", t)
        }
    }

    /**
     * Mechvibes 机械键盘音效：按需求放在 ClientTickEvent 的 END 阶段驱动，
     * 与 START 阶段的其它 runtime 相互独立。
     */
    @SubscribeEvent
    fun onClientTickEnd(e: ForgeTickEvent.ClientTickEvent) {
        if (e.phase != ForgeTickEvent.Phase.END) return
        try {
            MechvibesRuntime.tick()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music mechvibes tick failed: {}", t)
        }
        // Blood Particles：每 tick 记录准星指向的生物，攻击事件里再喷粒子 / 播音效
        try {
            BloodParticlesRuntime.tick()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music blood particles tick failed: {}", t)
        }
        // 静默旋转：tick 结束恢复相机朝向；本地旋转按键边沿在这里处理
        try {
            GaleAntiAimRuntime.tickEnd()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music gale tick end failed: {}", t)
        }
    }

    /** 静默旋转的生命周期必须在 EntityLivingBase#onLivingUpdate 内完成 */
    @SubscribeEvent
    fun onLivingUpdate(e: LivingUpdateEvent) {
        try {
            GaleAntiAimRuntime.onLivingUpdate(e.entity)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music gale living update failed: {}", t)
        }
    }

    /** LocalSpin：取消原版渲染，绕 Y 轴旋转自身模型后重新绘制 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onRenderPlayerPre(e: RenderPlayerEvent.Pre) {
        try {
            GaleAntiAimRuntime.onRenderPlayerPre(e)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music local spin failed: {}", t)
        }
    }

    @SubscribeEvent
    fun onRenderWorldLast(e: RenderWorldLastEvent) {
        // 液态玻璃的抓帧主路径：此刻画面是完整的世界（不含聊天黑底 / ESC 渐变 / HUD），
        // 且 READ 源就是我们自己绑定的世界 FBO，不依赖容易被别的 mod 改掉的 GL_BACK 读缓冲。
        try {
            GlassBackdrop.shared().captureWorldPass()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music glass backdrop world capture failed: {}", t)
        }
        DangoMusic.callEvent(WorldRenderEndEvent(e.partialTicks))
        try {
            FpsPortRuntime.onRender3D(e.partialTicks)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music fps port world render failed: {}", t)
        }
        try {
            LegacyModuleRuntime.onRender3D(e.partialTicks)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music projectile preview failed: {}", t)
        }
        try {
            FloatingMusicPanel.onRenderWorld(e.partialTicks)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music floating panel failed: {}", t)
        }
    }

    /**
     * 自定义 TAB 统计面板打开时取消原版 TAB 列表。
     *
     * GuiIngameForge 在 PLAYER_LIST 层前会触发 Pre 事件、被取消就跳过
     * GuiPlayerTabOverlay；面板画在世界渲染末尾（更早），不取消就会被原版盖住。
     */
    @SubscribeEvent
    fun onRenderGameOverlayPre(e: RenderGameOverlayEvent.Pre) {
        if (e.type == RenderGameOverlayEvent.ElementType.ALL) {
            // 玻璃背景必须在原版 HUD 元素之前抓：聊天框打开时 GuiNewChat 会先给聊天区
            // 铺一层近乎不透明的黑底，等到 Post(ALL) 再抓就会把它当成"背后的世界"，
            // 液态玻璃面板整块变黑。帧首抓一次，HUD 阶段内所有画布复用这一张。
            try {
                GlassBackdrop.shared().captureForFrameStart()
            } catch (t: Throwable) {
                DangoLogger.error("Dango Music glass backdrop capture failed: {}", t)
            }
            return
        }
        if (e.type != RenderGameOverlayEvent.ElementType.PLAYER_LIST) return
        try {
            if (TabStatsHudModule.of()?.isOpen == true) e.isCanceled = true
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music tab list hide failed: {}", t)
        }
    }

    /**
     * HUD 全部画完之后叠加动态模糊。
     *
     * GuiIngameForge#renderGameOverlay 末尾除了 ALL 还会派发一次，等价于原客户端的
     * renderGameOverlay RETURN 处；Old / New 两种模式都走这里。
     */
    @SubscribeEvent
    fun onRenderGameOverlayPost(e: RenderGameOverlayEvent.Post) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) return
        lastHudPostEvent = e
        // 先叠加动态模糊等全屏效果，再画自定义 HUD，最后画击杀图标：
        // 保证 HUD 不会被原版界面（聊天栏 / 计分板 / TAB）或全屏滤镜盖住
        try {
            FpsPortRuntime.onRenderOverlay(e.partialTicks)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music fps port overlay failed: {}", t)
        }
        // 界面打开时 HUD 会被聊天黑底 / ESC 黑渐变压黑：此时推迟到 currentScreen 画完之后
        // 由 HudAboveScreenHook 补绘（见 LegacyHudPass），本帧不再画第二遍
        if (HudAboveScreenHook.shouldDefer()) return
        renderHudOverlay(e)
    }

    // ---------------------------------------------------------------- PVP 工具

    @SubscribeEvent
    fun onFov(e: EntityViewRenderEvent.FOVModifier) {
        FpsPortRuntime.onFov(e)
    }

    @SubscribeEvent
    fun onFogColors(e: EntityViewRenderEvent.FogColors) {
        FpsPortRuntime.onFogColors(e)
    }

    /** 致盲雾密度：防盲开启且处于失明状态时把密度压下来 */
    @SubscribeEvent
    fun onFogDensity(e: EntityViewRenderEvent.FogDensity) {
        try {
            FpsPortRuntime.onFogDensity(e)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music fog density bridge failed: {}", t)
        }
    }

    /** 自己受击：连击断连与击退方向提示 */
    @SubscribeEvent
    fun onLivingHurt(e: LivingHurtEvent) {
        try {
            FpsPortRuntime.onLivingHurt(e)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music living hurt bridge failed: {}", t)
        }
    }

    @SubscribeEvent
    fun onCameraSetup(e: EntityViewRenderEvent.CameraSetup) {
        FpsPortRuntime.onCameraSetup(e)
    }

    @SubscribeEvent
    fun onMouse(e: MouseEvent) {
        try {
            if (FloatingMusicPanel.onMouse(e)) return
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music floating panel mouse failed: {}", t)
        }
        FpsPortRuntime.onMouse(e)
    }

    @SubscribeEvent
    fun onAttack(e: AttackEntityEvent) {
        try {
            FloatingMusicPanel.onAttackEntity(e)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music floating panel attack failed: {}", t)
        }
        if (e.isCanceled) return
        FpsPortRuntime.onAttack(e)
        KillIconRuntime.onAttack(e.target)
        try {
            BloodParticlesRuntime.onAttackEntity(e.target)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music blood particles attack failed: {}", t)
        }
    }

    @SubscribeEvent
    fun onPlayerInteract(e: PlayerInteractEvent) {
        try {
            FloatingMusicPanel.onPlayerInteract(e)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music floating panel interact failed: {}", t)
        }
    }

    @SubscribeEvent
    fun onLivingDeath(e: LivingDeathEvent) {
        KillIconRuntime.onLivingDeath(e)
    }

    @SubscribeEvent
    fun onChat(e: ClientChatReceivedEvent) {
        FpsPortRuntime.onChat(e)
        if (ChatTranslateRuntime.onChatReceived(e)) {
            e.isCanceled = true
        }
    }

    @SubscribeEvent
    fun onKeyInput(e: InputEvent.KeyInputEvent) {
        try {
            FloatingMusicPanel.onKeyInput()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music floating panel key failed: {}", t)
        }
        FpsPortRuntime.onKeyInput()
    }

    @SubscribeEvent
    fun onGuiOpen(e: GuiOpenEvent) {
        try {
            FloatingMusicPanel.onGuiOpen(e)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music floating panel gui open failed: {}", t)
        }
    }

    @SubscribeEvent
    fun onLivingSpecial(e: RenderLivingEvent.Specials.Pre<EntityLivingBase>) {
        FpsPortRuntime.onNametag(e)
    }

    @SubscribeEvent
    fun onLivingSpecialPost(e: RenderLivingEvent.Specials.Post<EntityLivingBase>) {
        NametagStatsRenderer.onPost(e)
    }

    /** 实体渲染距离：取消超出 perfEntityDistance 的活体渲染 */
    @SubscribeEvent(priority = EventPriority.NORMAL, receiveCanceled = true)
    fun onRenderLivingPre(e: RenderLivingEvent.Pre<EntityLivingBase>) {
        FpsPortRuntime.onRenderLivingPre(e)
    }
}

/**
 * 当前帧 Post(ALL) 事件。屏幕之上的补绘不在事件回调里，需要复用它带的 resolution / partialTicks。
 */
@Volatile
private var lastHudPostEvent: RenderGameOverlayEvent.Post? = null

/**
 * HUD 绘制主体：HudRenderEvent + 模块叠加 + 击杀图标。
 * 正常时在 Post(ALL) 执行；界面打开时推迟到 GuiScreen 画完之后执行（[LegacyHudPass]）。
 */
private fun renderHudOverlay(e: RenderGameOverlayEvent.Post) {
    // HUD 阶段内不再抓玻璃背景（画面里已经混进聊天 / 计分板），
    // 所有画布统一复用 Pre(ALL) 抓好的那一张
    val backdrop = GlassBackdrop.shared()
    backdrop.beginHudPass()
    try {
        DangoMusic.callEvent(HudRenderEvent(e.partialTicks))
        try {
            LegacyModuleRuntime.onRenderOverlay()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music item overlay failed: {}", t)
        }
        try {
            KillIconRuntime.onRenderOverlay(e)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music kill icon overlay failed: {}", t)
        }
    } finally {
        backdrop.endHudPass()
    }
}

/**
 * 屏幕之上的 HUD 补绘（由 [HudAboveScreenHook] 从 EntityRenderer.updateCameraAndRender 末尾调用）。
 *
 * 原版是「HUD → GuiScreen.drawScreen」：聊天框会再铺一层近乎不透明的聊天黑底、ESC 菜单会铺
 * 全屏黑渐变，液态玻璃会被整块压黑 / 压灰。这里在界面画完之后再把 HUD 画一遍。
 */
object LegacyHudPass {

    @JvmStatic
    fun renderAboveScreen() {
        val e = lastHudPostEvent ?: return
        renderHudOverlay(e)
    }
}
