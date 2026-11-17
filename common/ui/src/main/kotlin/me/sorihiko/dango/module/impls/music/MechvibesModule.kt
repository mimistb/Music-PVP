package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue

/**
 * Mechvibes：为键盘 / 鼠标输入播放机械键盘音效（无 HUD）。
 *
 * 只负责保存 5 个配置项；按键边沿检测、wav 加载与播放都在 legacy 的
 * MechvibesRuntime 里，按 ClientTickEvent Phase.END 每 tick 驱动。
 */
@ModuleMeta(Category.MUSIC, "Mechvibes")
class MechvibesModule : Module() {

    @ModuleValue("Keyboard", "键盘按键音效")
    private val keyboard = BooleanValue(true)

    @ModuleValue("Type", "轴体音效包")
    private val type = IntValue(0, TYPE_NAMES.size - 1, 0)

    @ModuleValue("Keyboard Volume", "键盘音效音量")
    private val keyboardVolume = FloatValue(0f, 1f, 0.5f, 2)

    @ModuleValue("Mouse", "鼠标左右键音效")
    private val mouse = BooleanValue(true)

    @ModuleValue("Mouse Volume", "鼠标音效音量")
    private val mouseVolume = FloatValue(0f, 1f, 0.5f, 2)

    override fun init() {
        instance = this
    }

    fun keyboardEnabled(): Boolean = keyboard.get()

    fun mouseEnabled(): Boolean = mouse.get()

    fun keyboardVolumeValue(): Float = keyboardVolume.value

    fun mouseVolumeValue(): Float = mouseVolume.value

    fun typeIndex(): Int = type.value.coerceIn(0, TYPE_NAMES.size - 1)

    /** 资产目录名：与原 jar 的 assets/glidemods/mechvibes 子目录一一对应（HyperX 保留大写 X）。 */
    fun typeDir(): String = DIRS[typeIndex()]

    companion object {
        /** 顺序必须与设置项 Type 的取值一致 */
        val TYPE_NAMES = arrayOf(
            "NK Cream", "MX Blue", "MX Silver", "Razer Green", "HyperX Aqua", "MX Black", "Topre Purple",
        )

        val DIRS = arrayOf(
            "nk_cream", "mx_blue", "mx_silver", "razer_green", "hyperX_aqua", "mx_black", "topre_purple",
        )

        @Volatile
        private var instance: MechvibesModule? = null

        fun of(): MechvibesModule? = instance
    }
}
