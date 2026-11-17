package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.KeyBindValue
import me.sorihiko.dango.module.values.ModuleValue

/**
 * 自动黑名单（移植自 Meowtils 的 antisnipe/AutoBlacklist）。
 *
 * 触发来源有两组：反作弊标记、以及 /report 或 /wdr 举报。配置项与默认值与原模组一致；
 * 黑名单文件与 UUID 解析在 legacy 的 BlacklistStore。
 */
@ModuleMeta(Category.MUSIC, "AutoBlacklist")
class AutoBlacklistModule : Module() {

    @ModuleValue("开关按键", "游戏内按下切换模块开关；留空表示不绑定")
    val toggleKey = KeyBindValue()

    @ModuleValue("显示提示", "拉黑成功后发送聊天提示")
    val showNotifications = BooleanValue(true)

    @ModuleValue("按标记拉黑", "反作弊标记触发时自动拉黑")
    val forFlags = BooleanValue(true)

    @ModuleValue("AutoBlock 标记", "AutoBlock 判定时拉黑")
    val flagAutoblock = BooleanValue(true)

    @ModuleValue("NoSlow 标记", "NoSlow 判定时拉黑")
    val flagNoslow = BooleanValue(true)

    @ModuleValue("Killaura 标记", "Killaura 判定时拉黑")
    val flagKillaura = BooleanValue(true)

    @ModuleValue("Legit Scaffold 标记", "Legit Scaffold 判定时拉黑")
    val flagLegitScaffold = BooleanValue(true)

    @ModuleValue("按举报拉黑", "执行举报命令时自动拉黑")
    val forReports = BooleanValue(true)

    @ModuleValue("执行 /report 时", "拦截 /report 并拉黑被举报玩家")
    val whenReportCommand = BooleanValue(true)

    @ModuleValue("执行 /wdr 时", "拦截 /wdr 并拉黑被举报玩家")
    val whenWdrCommand = BooleanValue(true)

    override fun init() {
        instance = this
    }

    companion object {
        @Volatile
        private var instance: AutoBlacklistModule? = null

        fun of(): AutoBlacklistModule? = instance
    }
}
