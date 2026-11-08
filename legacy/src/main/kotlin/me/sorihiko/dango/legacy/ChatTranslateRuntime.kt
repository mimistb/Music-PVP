package me.sorihiko.dango.legacy

import fpsport.chattranslate.ChatTranslateModule
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.module.impls.music.ChatTranslateSettingsModule

import net.minecraft.client.Minecraft
import net.minecraft.command.ICommand
import net.minecraft.command.ICommandSender
import net.minecraft.util.BlockPos
import net.minecraftforge.client.ClientCommandHandler
import net.minecraftforge.client.event.ClientChatReceivedEvent

/**
 * 聊天翻译（微软翻译免费通道）在 1.8.9 上的运行层。
 *
 * 三个时机：
 * 1. [onChatReceived]：收到聊天消息时追加 ✎ 或自动补译文；
 * 2. [onChatSend]：由 GuiScreen#sendChatMessage 的 ASM 钩子调用，返回 true 表示已接管；
 * 3. [handleCommand]：`/translate <文本>` 客户端命令。
 *
 * 所有入口吞异常打日志，不影响聊天本身；本类不创建任何 UI。
 */
object ChatTranslateRuntime {

    private val effect = ChatTranslateModule()

    private var installed = false
    private var registeredName: String? = null

    /** mod 初始化时调用一次：同步设置并注册 `/translate`。 */
    fun install() {
        if (installed) return
        installed = true
        try {
            sync()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music chat translate install failed: {}", t)
        }
    }

    /** 把宿主设置写进模块的 public 字段；每帧/每次事件前调用。 */
    fun sync() {
        val module = try {
            DangoMusic.instance.moduleManager.get(ChatTranslateSettingsModule::class.java)
        } catch (t: Throwable) {
            return
        }
        effect.enabled = module.enabled
        effect.showTranslateButton = module.showTranslateButton.get()
        effect.autoTranslateIncoming = module.autoTranslateIncoming.get()
        effect.translateOutgoing = module.translateOutgoing.get()
        effect.abbrevEnabled = module.abbrevEnabled.get()
        // 接收 / 发送两条链路各读各的语言配置，互不覆盖
        effect.incomingLanguage = ChatTranslateModule.LANGUAGE_CODES[
            module.incomingLanguage.value.coerceIn(0, ChatTranslateModule.LANGUAGE_CODES.size - 1)
        ]
        effect.outgoingLanguage = ChatTranslateModule.LANGUAGE_CODES[
            module.outgoingLanguage.value.coerceIn(0, ChatTranslateModule.LANGUAGE_CODES.size - 1)
        ]
        effect.commandName = module.commandName.get().ifBlank { "translate" }
        // StringValue 会去掉首尾空格，这里补一个前导空格，保证按钮不和正文黏在一起
        effect.buttonText = " " + module.buttonText.get().ifBlank { "[\u270e]" }
        effect.buttonHoverText = module.buttonHoverText.get().ifBlank { "点击翻译这条消息" }
        effect.skipCommands = module.skipCommands.get()
        registerCommand(effect.commandName)
    }

    /**
     * 收到聊天消息。返回 true 表示模块已经打印了处理后的消息，宿主应取消原版显示。
     */
    fun onChatReceived(e: ClientChatReceivedEvent): Boolean {
        return try {
            sync()
            effect.onChatReceived(e.message, e.type.toInt())
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music chat translate receive failed: {}", t)
            false
        }
    }

    /**
     * 玩家按回车发送聊天时调用（ASM 注入 GuiScreen#sendChatMessage）。
     * 返回 true 表示本次由模块异步翻译并自行发送，原版方法应直接返回。
     */
    fun onChatSend(message: String): Boolean {
        return try {
            sync()
            if (!effect.shouldTranslateOutgoing(message)) return false
            effect.onChatSend(message)
            true
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music chat translate send failed: {}", t)
            false
        }
    }

    /** `/translate <文本>`：参数按空格拼回原文交给模块。 */
    fun handleCommand(args: Array<String>) {
        try {
            sync()
            effect.handleCommand(args.joinToString(" "))
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music chat translate command failed: {}", t)
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun registerCommand(name: String) {
        val clean = name.trim().removePrefix("/").substringBefore(' ')
        if (clean.isEmpty() || clean == registeredName) return
        // 命令名改动可能发生在收到聊天包的网络线程上：ClientCommandHandler 不是线程安全的，
        // 统一切回主线程注册（主线程时 addScheduledTask 会立即执行）。
        val mc = Minecraft.getMinecraft() ?: return
        mc.addScheduledTask {
            try {
                ClientCommandHandler.instance.registerCommand(TranslateCommand())
                // 注册成功后才记录，失败时保持 null，下一次 sync() 会重试
                registeredName = clean
            } catch (t: Throwable) {
                registeredName = null
                DangoLogger.error("Dango Music chat translate command '{}' register failed: {}", clean, t)
            }
        }
    }

    /** 客户端命令：动态读取当前命令名，改设置后重新注册即可。 */
    private class TranslateCommand : ICommand {
        override fun getCommandName(): String = effect.commandName

        override fun getCommandUsage(sender: ICommandSender?): String = "/${effect.commandName} <文本>"

        override fun getCommandAliases(): List<String> = emptyList()

        override fun processCommand(sender: ICommandSender?, args: Array<String>) {
            handleCommand(args)
        }

        override fun canCommandSenderUseCommand(sender: ICommandSender?): Boolean = true

        override fun addTabCompletionOptions(
            sender: ICommandSender?,
            args: Array<String>?,
            pos: BlockPos?,
        ): List<String>? = null

        override fun isUsernameIndex(args: Array<String>?, index: Int): Boolean = false

        override fun compareTo(other: ICommand): Int = getCommandName().compareTo(other.commandName)
    }
}
