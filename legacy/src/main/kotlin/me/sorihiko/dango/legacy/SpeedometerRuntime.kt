package me.sorihiko.dango.legacy

import me.sorihiko.dango.module.impls.music.SpeedometerHudModule
import net.minecraft.client.Minecraft
import kotlin.math.sqrt

/**
 * 速度计：每 tick 把本地玩家的水平位移速度（块/秒）发布给 HUD。
 * 1.8.9 的 motionX/motionZ 是「每 tick 位移」，乘 20 即每秒速度。
 */
internal object SpeedometerRuntime {
    fun tick() {
        val module = SpeedometerHudModule.of() ?: return
        if (!module.enabled) return
        val player = Minecraft.getMinecraft().thePlayer ?: return
        val dx = player.motionX
        val dz = player.motionZ
        SpeedometerHudModule.publish(sqrt(dx * dx + dz * dz).toFloat() * 20f)
    }
}
