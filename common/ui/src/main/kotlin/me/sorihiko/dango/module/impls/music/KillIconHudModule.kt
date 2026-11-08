package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue

/**
 * 击杀图标（gd656killicon）的配置容器。
 *
 * 只存设置、由 ConfigManager 自动存盘；每 tick 由 legacy 的 KillIconRuntime 读出，
 * 同步进 fpsport.killicon.KillIconModule 的 public 字段。这里不画任何东西，
 * 设置项由 SettingsPage 的「击杀图标」分组展示。
 */
@ModuleMeta(Category.MUSIC, "KillIcon")
class KillIconHudModule : Module() {

    @ModuleValue("音效", "击杀时播放图标音效")
    val playSound = BooleanValue(true)

    @ModuleValue("音效音量", "0 ~ 100%")
    val soundVolume = FloatValue(0f, 1f, 0.7f, 2)

    @ModuleValue("预设", "42 套击杀图标预设")
    val presetIndex = IntValue(0, 41, 8)

    @ModuleValue("横向锚点", "屏幕宽度百分比，5 ~ 95")
    val anchorXPercent = FloatValue(5f, 95f, 50f, 0)

    @ModuleValue("纵向锚点", "屏幕高度百分比，10 ~ 90")
    val anchorYPercent = FloatValue(10f, 90f, 66f, 0)

    @ModuleValue("图标尺寸", "64 ~ 200，基准 96")
    val size = FloatValue(64f, 200f, 96f, 0)

    @ModuleValue("预览循环", "无击杀时也循环播放当前预设")
    val demoMode = BooleanValue(false)

    companion object {
        /** legacy 启动后由 KillIconRuntime 写入真实预设名；读取前先显示占位 */
        @JvmField
        var presetLabels: Array<String> = Array(42) { "第 ${it + 1} 套" }
    }
}
