package me.sorihiko.dango.module.impls.music

import glidehud.modules.HitColorModule
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.utils.Color

/**
 * 打击颜色：只改实体受伤时那一下红闪的颜色，不产生任何 HUD 元素。
 * 颜色值由 1.8.9 的 coremod（HitColorTransformer）在渲染层读取，关掉时恢复原版 1/0/0/0.3。
 * 模块总开关就是这个功能的总开关。
 */
@ModuleMeta(Category.MUSIC, "HitColor")
class HitColorHudModule : Module() {

    @ModuleValue("色调", "红 / 橙 / 黄 / 绿 / 青 / 蓝 / 紫")
    private val palette = IntValue(0, 6, 0)

    @ModuleValue("透明度", "受伤闪光的不透明度")
    private val alpha = FloatValue(0f, 1f, 0.45f, 2)

    // 旧版的「自定义颜色 / 颜色值」两个参数保留在配置里（旧配置不会被重置），
    // 颜色现在统一由上面的「色调」决定，设置页不再显示这两行。
    @ModuleValue("自定义颜色", "旧版参数：保留在配置里，颜色由「色调」决定")
    private val customColor = BooleanValue(true)

    @ModuleValue("颜色值", "旧版参数：保留在配置里，颜色由「色调」决定")
    private val color = IntValue(0, 0xFFFFFF, 0xFF0000)

    /** glidehud 的计算逻辑保持原样，这里只做参数适配 */
    private val core = HitColorModule()

    /** 调色板里的颜色（RRGGBB） */
    private fun paletteColor(): Int = PALETTE[palette.value.coerceIn(0, PALETTE.size - 1)]

    override fun init() {
        instance = this
        sync()
        core.update()
    }

    /** 每 tick 由平台层调用：把设置同步进去并重算 rgba */
    fun update() {
        sync()
        core.update()
    }

    private fun sync() {
        core.enabled = enabled
        // 色调就是实际生效的颜色；关掉模块时 core 会自己回到原版红色
        core.customColor = true
        core.color = paletteColor()
        core.alpha = alpha.value
    }

    /** {red, green, blue, alpha}，0..1 */
    fun rgba(): FloatArray = core.rgba()

    companion object {
        @Volatile
        private var instance: HitColorHudModule? = null

        fun of(): HitColorHudModule? = instance

        /** 红 / 橙 / 黄 / 绿 / 青 / 蓝 / 紫（iOS 系统色） */
        private val PALETTE = intArrayOf(0xFF3B30, 0xFF9500, 0xFFD60A, 0x34C759, 0x32ADE6, 0x0A84FF, 0xAF52DE)

        /** 设置页里的颜色选择块（顺序和 [PALETTE] 一致，只建一次） */
        private val SWATCHES: List<Color> = PALETTE.map { Color.fromRGB(it) }

        fun paletteSwatches(): List<Color> = SWATCHES
    }
}
