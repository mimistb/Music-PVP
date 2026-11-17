package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.ModuleValue

/**
 * Custom Held Items：调整第一人称手持物品的位置与缩放。
 *
 * 无 HUD。数值由 legacy 的 CustomHeldItemsRuntime 每 tick 同步到 CustomHeldItemsHooks，
 * ASM 在 ItemRenderer#renderItemInFirstPerson 起始处调用钩子：先 translate 后 scale，
 * 与 Glide 原实现顺序一致；关闭模块时钩子直接返回。
 */
@ModuleMeta(Category.MUSIC, "Custom Held Items")
class CustomHeldItemsModule : Module() {

    @ModuleValue("X", "手持物品左右位置（-1.0 ~ 1.0）")
    private val xSetting = FloatValue(-1f, 1f, 0.75f, 2)

    @ModuleValue("Y", "手持物品上下位置（-1.0 ~ 1.0）")
    private val ySetting = FloatValue(-1f, 1f, -0.15f, 2)

    @ModuleValue("Z", "手持物品前后位置（-1.0 ~ 1.0）")
    private val zSetting = FloatValue(-1f, 1f, -1f, 2)

    @ModuleValue("X Scale", "横向缩放（0.0 ~ 1.0，只能缩小）")
    private val xScaleSetting = FloatValue(0f, 1f, 1f, 2)

    @ModuleValue("Y Scale", "纵向缩放（0.0 ~ 1.0，只能缩小）")
    private val yScaleSetting = FloatValue(0f, 1f, 1f, 2)

    @ModuleValue("Z Scale", "前后缩放（0.0 ~ 1.0，只能缩小）")
    private val zScaleSetting = FloatValue(0f, 1f, 1f, 2)

    override fun init() {
        instance = this
    }

    fun x(): Float = xSetting.value

    fun y(): Float = ySetting.value

    fun z(): Float = zSetting.value

    fun xScale(): Float = xScaleSetting.value

    fun yScale(): Float = yScaleSetting.value

    fun zScale(): Float = zScaleSetting.value

    companion object {
        @Volatile
        private var instance: CustomHeldItemsModule? = null

        fun of(): CustomHeldItemsModule? = instance
    }
}
