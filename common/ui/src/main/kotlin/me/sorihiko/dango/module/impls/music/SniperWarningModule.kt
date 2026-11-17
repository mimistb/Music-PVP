package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.KeyBindValue
import me.sorihiko.dango.module.values.ModuleValue

/**
 * 狙击预警（移植自 Meowtils 的 antisnipe/SniperWarning）。
 *
 * 三个判定来源：装备（铁剑 + 链甲）、名字关键词、战绩（fkdr 与 finals 的关系）。
 * 扫描频率、关键词表与阈值和原模组一致；检测逻辑在 legacy 的 AntiSnipeRuntime。
 */
@ModuleMeta(Category.MUSIC, "SniperWarning")
class SniperWarningModule : Module() {

    @ModuleValue("开关按键", "游戏内按下切换模块开关；留空表示不绑定")
    val toggleKey = KeyBindValue()

    @ModuleValue("附带战绩", "预警时在聊天里附加该玩家的战绩摘要")
    val fetchStats = BooleanValue(false)

    @ModuleValue("预警音效", "触发预警时播放提示音")
    val sound = BooleanValue(true)

    @ModuleValue("检查装备", "链甲 + 铁剑的狙击手配置")
    val checkGear = BooleanValue(true)

    @ModuleValue("检查名字", "名字包含已知狙击手关键词")
    val checkName = BooleanValue(true)

    @ModuleValue("检查战绩", "fkdr 相对 finals 异常偏高")
    val checkStats = BooleanValue(false)

    override fun init() {
        instance = this
    }

    companion object {
        @Volatile
        private var instance: SniperWarningModule? = null

        fun of(): SniperWarningModule? = instance
    }
}
