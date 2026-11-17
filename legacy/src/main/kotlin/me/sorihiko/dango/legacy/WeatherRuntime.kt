package me.sorihiko.dango.legacy

import me.sorihiko.dango.core.WeatherHooks
import me.sorihiko.dango.module.impls.music.WeatherChangerModule
import net.minecraft.client.Minecraft
import net.minecraft.util.EnumParticleTypes
import java.util.Random

/**
 * 天气切换：客户端每 tick 覆盖 WorldInfo 的雨/雷标志与计时，并同步 ASM hook。
 * 雪天额外补雪花粒子；晴天 / 雨天 / 雷暴的降水与亮度全部由 hook 改写后的
 * World#getRainStrength / getThunderStrength 驱动，关闭模块即恢复原版。
 */
internal object WeatherRuntime {

    private val random = Random()
    private var wasEnabled = false

    fun tick() {
        val module = WeatherChangerModule.of()
        val mc = Minecraft.getMinecraft()
        val world = mc.theWorld
        if (module == null || !module.enabled || world == null) {
            WeatherHooks.enabled = false
            // 关闭时把客户端 WorldInfo 归零，避免残留下雨标记（服务器下一次同步会覆盖）
            if (wasEnabled && world != null) {
                world.worldInfo.setRaining(false)
                world.worldInfo.setThundering(false)
                world.worldInfo.setRainTime(0)
                world.worldInfo.setThunderTime(0)
            }
            wasEnabled = false
            return
        }
        wasEnabled = true
        val mode = module.mode()
        WeatherHooks.enabled = true
        WeatherHooks.mode = mode
        WeatherHooks.rain = module.rain().coerceIn(0f, 1f)
        WeatherHooks.thunder = module.thunder().coerceIn(0f, 1f)

        val info = world.worldInfo
        val raining = mode != WeatherChangerModule.CLEAR
        info.setRaining(raining)
        info.setRainTime(if (raining) 1_000_000 else 0)
        info.setThundering(mode == WeatherChangerModule.STORM)
        info.setThunderTime(if (mode == WeatherChangerModule.STORM) 1_000_000 else 0)

        // 雪天：原版雨粒子被 getRainStrength=0 抑制，自己补一层可见的雪花。
        // 每 tick 10 个（原 24 个）即可维持 16 格半径内的雪感，粒子总量下降约 58%
        if (mode == WeatherChangerModule.SNOW) {
            val player = mc.thePlayer ?: return
            for (i in 0 until 10) {
                val x = player.posX + (random.nextDouble() - 0.5) * 16.0
                val z = player.posZ + (random.nextDouble() - 0.5) * 16.0
                val y = player.posY + random.nextDouble() * 8.0 + 1.0
                world.spawnParticle(
                    EnumParticleTypes.SNOW_SHOVEL,
                    x, y, z,
                    (random.nextDouble() - 0.5) * 0.12,
                    -0.12 - random.nextDouble() * 0.1,
                    (random.nextDouble() - 0.5) * 0.12,
                )
            }
        }
    }
}
