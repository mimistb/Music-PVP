package me.sorihiko.dango.module.impls.music

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.module.values.StringValue

/**
 * 聊天翻译（微软翻译免费通道）的配置容器。
 *
 * 只存设置，由 ConfigManager 自动存盘；每 tick 由 legacy 的 ChatTranslateRuntime
 * 同步进 fpsport.chattranslate.ChatTranslateModule 的 public 字段。
 * 不创建任何 UI，设置项由 SettingsPage 的「聊天翻译」分组展示。
 *
 * 目标语言拆成两项：接收消息（自动翻译 / ✎ 按钮 / /命令）与发送前置翻译各用各的，
 * 互不覆盖；旧配置里的单一 targetLanguage 由 [migrateLegacyConfig] 迁移到两项。
 */
@ModuleMeta(Category.MUSIC, "ChatTranslate")
class ChatTranslateSettingsModule : Module() {

    @ModuleValue("✎ 翻译按钮", "在每条聊天消息后显示绿色 ✎，点击翻译这条消息")
    val showTranslateButton = BooleanValue(true)

    @ModuleValue("自动翻译玩家话语", "收到其他玩家聊天时自动补一条译文（默认关闭）")
    val autoTranslateIncoming = BooleanValue(false)

    @ModuleValue("接收消息目标语言", "点击 ✎、/命令 与自动翻译的译文使用这个语言")
    val incomingLanguage = IntValue(0, 4, 0)

    @ModuleValue("发送前翻译", "自动翻译只作用于玩家自己发送的消息")
    val translateOutgoing = BooleanValue(true)

    @ModuleValue("发送消息目标语言", "回车发送前翻译成的语言，与接收消息语言互不影响")
    val outgoingLanguage = IntValue(0, 4, 0)

    @ModuleValue("英文缩写匹配", "发送前先把 gg / wp / ez / nt 等常用缩写展开成完整英文再翻译")
    val abbrevEnabled = BooleanValue(true)

    @ModuleValue("命令名", "点击 ✎ 时执行的客户端命令，默认 translate")
    val commandName = StringValue("translate")

    @ModuleValue("按钮文本", "消息末尾按钮显示的文字")
    val buttonText = StringValue("[\u270e]")

    @ModuleValue("按钮提示", "鼠标悬停在 ✎ 上时的提示")
    val buttonHoverText = StringValue("点击翻译这条消息")

    @ModuleValue("跳过命令", "以 / 或 . 开头的内容不翻译")
    val skipCommands = BooleanValue(true)

    /**
     * 旧版只有一个 targetLanguage：新键不存在时，把旧值同时灌进两项，
     * 保证升级后行为不变；之后两项再各自独立修改。
     */
    override fun migrateLegacyConfig(values: JsonObject) {
        val legacy = values.get("targetLanguage") as? JsonPrimitive ?: return
        if (!legacy.isNumber) return
        val index = legacy.asInt.coerceIn(0, LANGUAGE_NAMES.size - 1)
        if (!values.has("incomingLanguage")) incomingLanguage.setValue(index)
        if (!values.has("outgoingLanguage")) outgoingLanguage.setValue(index)
    }

    companion object {
        /** 与 fpsport.ChatTranslateModule.LANGUAGE_CODES 一一对应 */
        @JvmField
        val LANGUAGE_NAMES = arrayOf("日语", "英语", "简体中文", "繁体中文", "波兰语")
    }
}
