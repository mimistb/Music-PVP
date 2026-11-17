package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.KeyBindValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.module.values.StringValue

/**
 * 反作弊（移植自 Meowtils 的 antisnipe/AntiCheat）。
 *
 * 只负责配置；检测逻辑在 legacy 的 AntiSnipeRuntime，数值与阈值和原模组完全一致。
 * 颜色项保留原版的 EnumChatFormatting 颜色名（RED / AQUA / GRAY ...），
 * 字段名刻意不以 Color 结尾——设置页会把 *Color 键当成 IntValue 色板控件。
 */
@ModuleMeta(Category.MUSIC, "AntiCheat")
class AntiCheatModule : Module() {

    @ModuleValue("开关按键", "游戏内按下切换模块开关；留空表示不绑定")
    val toggleKey = KeyBindValue()

    @ModuleValue("违规阈值", "同一检查累计触发多少次才判定（0 = 第一次就判定）")
    val violationLevel = IntValue(0, 10, 0)

    @ModuleValue("标记音效", "判定成功后播放提示音")
    val flagSound = BooleanValue(true)

    @ModuleValue("WDR 按钮", "标记消息后附带可点击的 [WDR] 按钮")
    val wdrButton = BooleanValue(true)

    @ModuleValue("检测 AutoBlock", "边挥砍边格挡的自动格挡行为")
    val autoBlock = BooleanValue(true)

    @ModuleValue("检测 NoSlow", "进食/喝药时仍保持冲刺速度")
    val noSlow = BooleanValue(true)

    @ModuleValue("检测 Killaura", "进食后立刻挥砍")
    val killaura = BooleanValue(true)

    @ModuleValue("检测 Legit Scaffold", "快速蹲起配合贴地放方块")
    val legitScaffold = BooleanValue(true)

    @ModuleValue("消息颜色", "EnumChatFormatting 颜色名，如 RED / YELLOW / LIGHT_PURPLE")
    val componentColorName = StringValue("RED")

    @ModuleValue("按钮颜色", "WDR 按钮文字的颜色名")
    val buttonColorName = StringValue("AQUA")

    @ModuleValue("括号颜色", "WDR 按钮方括号的颜色名")
    val bracketColorName = StringValue("GRAY")

    override fun init() {
        instance = this
    }

    companion object {
        @Volatile
        private var instance: AntiCheatModule? = null

        fun of(): AntiCheatModule? = instance
    }
}
