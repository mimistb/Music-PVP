package me.sorihiko.dango.forge

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.events.TickEvent
import me.sorihiko.dango.events.WorldRenderEndEvent
import me.sorihiko.dango.legacy.BedWarsRuntime
import me.sorihiko.dango.legacy.FpsPortRuntime
import me.sorihiko.dango.legacy.LegacyGl
import me.sorihiko.dango.module.impls.music.TabStatsHudModule

import net.minecraft.entity.EntityLivingBase
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.client.event.EntityViewRenderEvent
import net.minecraftforge.client.event.MouseEvent
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.client.event.RenderLivingEvent
import net.minecraftforge.client.event.RenderWorldLastEvent
import net.minecraftforge.event.entity.player.AttackEntityEvent
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
        try {
            HitColorBridge.tick()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music hit color tick failed: {}", t)
        }
        DangoMusic.callEvent(TickEvent)
        // 床提醒 / Hypixel 统计：每 tick 取一次游戏数据（设置值已由 BedWarsModule 同步）
        try {
            BedWarsRuntime.tick()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music bedwars tick failed: {}", t)
        }
        FpsPortRuntime.tick()
    }

    @SubscribeEvent
    fun onRenderWorldLast(e: RenderWorldLastEvent) {
        DangoMusic.callEvent(WorldRenderEndEvent(e.partialTicks))
        FpsPortRuntime.onRender3D(e.partialTicks)
    }

    /**
     * 自定义 TAB 统计面板打开时取消原版 TAB 列表。
     *
     * GuiIngameForge 在 PLAYER_LIST 层前会触发 Pre 事件、被取消就跳过
     * GuiPlayerTabOverlay；面板画在世界渲染末尾（更早），不取消就会被原版盖住。
     */
    @SubscribeEvent
    fun onRenderGameOverlayPre(e: RenderGameOverlayEvent.Pre) {
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
        FpsPortRuntime.onRenderOverlay()
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

    @SubscribeEvent
    fun onCameraSetup(e: EntityViewRenderEvent.CameraSetup) {
        FpsPortRuntime.onCameraSetup(e)
    }

    @SubscribeEvent
    fun onMouse(e: MouseEvent) {
        FpsPortRuntime.onMouse(e)
    }

    @SubscribeEvent
    fun onAttack(e: AttackEntityEvent) {
        FpsPortRuntime.onAttack(e)
    }

    @SubscribeEvent
    fun onChat(e: ClientChatReceivedEvent) {
        FpsPortRuntime.onChat(e)
    }

    @SubscribeEvent
    fun onKeyInput(e: InputEvent.KeyInputEvent) {
        FpsPortRuntime.onKeyInput()
    }

    @SubscribeEvent
    fun onLivingSpecial(e: RenderLivingEvent.Specials.Pre<EntityLivingBase>) {
        FpsPortRuntime.onNametag(e)
    }

    /** 实体渲染距离：取消超出 perfEntityDistance 的活体渲染 */
    @SubscribeEvent(priority = EventPriority.NORMAL, receiveCanceled = true)
    fun onRenderLivingPre(e: RenderLivingEvent.Pre<EntityLivingBase>) {
        FpsPortRuntime.onRenderLivingPre(e)
    }
}
