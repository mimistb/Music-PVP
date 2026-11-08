package fpsport.chattranslate

import me.sorihiko.dango.legacy.ChatTranslateRuntime

/**
 * GuiScreen#sendChatMessage 的 ASM 钩子入口。
 *
 * 返回 true 表示本次发送已由 ChatTranslateRuntime 接管（异步翻译后会自行发送），
 * 原版方法应直接 RETURN；返回 false 则原版照常发送。
 */
object ChatTranslateHook {

    @JvmStatic
    fun onChatSend(message: String): Boolean = ChatTranslateRuntime.onChatSend(message)
}
