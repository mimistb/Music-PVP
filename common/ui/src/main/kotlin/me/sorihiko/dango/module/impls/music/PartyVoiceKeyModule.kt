package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.TickEvent
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.KeyBindValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.party.VoiceChat
import me.sorihiko.dango.platform.Keys

/**
 * 一起听 2.0 的语音按键：按键配置、按住监听、说话状态事件都在这里统一处理。
 * 只负责"键按着没有"，是否真的开麦由 [VoiceChat] 结合「按键说话」开关和聊天栏状态决定。
 */
@ModuleMeta(Category.MUSIC, "PartyVoiceKey")
class PartyVoiceKeyModule : Module() {

    @ModuleValue("语音按键", "按住说话的按键，默认 V，支持组合键；绑定时按 Esc 取消绑定（变成无按键）")
    val voiceKey = KeyBindValue(intArrayOf(Keys.V))

    /** 上一 tick 的按下状态，只在变化时通知 VoiceChat */
    private var wasDown = false

    // BUG-01: 默认打开走 defaultEnabled，不能在 init() 里直接写 enabled（配置缺键会被覆盖，
    // 且 init 阶段事件监听器还没注册）。
    override val defaultEnabled: Boolean get() = true

    override fun init() {
        instance = this
    }

    override fun onDisable() {
        // 关掉模块就把按键状态复位，避免一直卡在"正在说话"
        wasDown = false
        VoiceChat.setTalkKeyDown(false)
    }

    @EventHandler
    fun onTick(e: TickEvent) {
        val platform = DangoMusic.instance.platform
        val down = enabled && try {
            voiceKey.isDown(platform)
        } catch (_: Exception) {
            false
        }
        if (down == wasDown) return
        wasDown = down
        VoiceChat.setTalkKeyDown(down)
    }

    companion object {
        @Volatile
        private var instance: PartyVoiceKeyModule? = null

        fun of(): PartyVoiceKeyModule? = instance
    }
}
