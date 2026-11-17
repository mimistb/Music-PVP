package me.sorihiko.dango.legacy

import me.sorihiko.dango.core.CustomHeldItemsHooks
import me.sorihiko.dango.module.impls.music.CustomHeldItemsModule

/**
 * Custom Held Items 运行时：每 tick 把模块配置同步到 ASM 钩子。
 *
 * 由 ForgeBridge 在 ClientTickEvent(Phase.START) 调用；
 * ItemRenderer#renderItemInFirstPerson 起始处读取钩子里的数值做 translate + scale。
 * 模块关闭时立即把 enabled 置 false，手持物品恢复原版变换。
 */
internal object CustomHeldItemsRuntime {

    fun tick() {
        val module = CustomHeldItemsModule.of()
        if (module == null || !module.enabled) {
            CustomHeldItemsHooks.enabled = false
            return
        }
        CustomHeldItemsHooks.x = module.x()
        CustomHeldItemsHooks.y = module.y()
        CustomHeldItemsHooks.z = module.z()
        CustomHeldItemsHooks.xScale = module.xScale()
        CustomHeldItemsHooks.yScale = module.yScale()
        CustomHeldItemsHooks.zScale = module.zScale()
        CustomHeldItemsHooks.enabled = true
    }
}
