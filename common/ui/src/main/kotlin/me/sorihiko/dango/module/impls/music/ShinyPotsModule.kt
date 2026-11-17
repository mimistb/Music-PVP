package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta

/**
 * 闪光药水（Shiny Pots）：去掉附魔光效的线性过滤，让药水/附魔物品的光泽变成颗粒感闪光。
 *
 * 原版 RenderItem#renderEffect 里两次把 GL_TEXTURE_MIN/MAG_FILTER 设为 GL_LINEAR；
 * ASM（ShinyPotsTransformer）把这两处常量换成 ShinyPotsHooks.filter()，
 * 开启时返回 GL_NEAREST（颗粒感），关闭时返回原来的 GL_LINEAR，平滑回退，无需重启。
 */
@ModuleMeta(Category.MUSIC, "Shiny Pots")
class ShinyPotsModule : Module() {
    override fun init() {
        instance = this
    }

    companion object {
        @Volatile
        private var instance: ShinyPotsModule? = null

        fun of(): ShinyPotsModule? = instance
    }
}
