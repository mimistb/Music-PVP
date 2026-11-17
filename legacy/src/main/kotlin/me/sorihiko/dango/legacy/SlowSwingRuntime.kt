package me.sorihiko.dango.legacy

import me.sorihiko.dango.core.SlowSwingHooks
import me.sorihiko.dango.module.impls.music.ShinyPotsModule
import me.sorihiko.dango.module.impls.music.SlowSwingModule
import me.sorihiko.dango.module.impls.music.SlowSwingRuntimeBridge
import me.sorihiko.dango.core.ShinyPotsHooks

/**
 * 慢挥 / 闪光药水的运行时同步：
 *  - 慢挥：把配置写进 ASM hook；开启「平滑过渡」时实际 tick 数每 tick 向目标靠近 1，约 0.25 秒到位
 *  - 闪光药水：只同步一个布尔开关
 */
internal object SlowSwingRuntime {

    private var current = 6f
    private var initialized = false

    fun tick() {
        val module = SlowSwingModule.of()
        if (module != null && module.enabled) {
            val target = module.configuredDelay().toFloat()
            if (!initialized) {
                current = target
                initialized = true
            }
            if (module.smoothEnabled()) {
                val delta = target - current
                if (delta > 0.5f) current += 1f
                else if (delta < -0.5f) current -= 1f
                else current = target
                current = current.coerceIn(2f, 20f)
            } else {
                current = target
            }
            SlowSwingHooks.ticks = current.toInt().coerceIn(2, 20)
            SlowSwingHooks.enabled = true
            SlowSwingRuntimeBridge.currentTicks = SlowSwingHooks.ticks
        } else {
            SlowSwingHooks.enabled = false
            initialized = false
        }

        val shiny = ShinyPotsModule.of()
        ShinyPotsHooks.enabled = shiny != null && shiny.enabled
    }
}
