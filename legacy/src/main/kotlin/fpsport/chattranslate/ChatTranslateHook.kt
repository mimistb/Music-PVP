package fpsport.chattranslate

import me.sorihiko.dango.legacy.AntiSnipeRuntime
import me.sorihiko.dango.legacy.ChatTranslateRuntime

/**
 * GuiScreen#sendChatMessage 的 ASM 钩子入口。
 *
 * 返回 true 表示本次发送已被接管（原版方法应直接 RETURN）；返回 false 则原版照常发送。
 * 先给反狙击模块拦 /report 与 /wdr，再交给聊天翻译。
 */
object ChatTranslateHook {

    @JvmStatic
    fun onChatSend(message: String): Boolean {
        if (AntiSnipeRuntime.onChatSend(message)) return true
        return ChatTranslateRuntime.onChatSend(message)
    }
}
