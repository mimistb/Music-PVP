package me.sorihiko.dango.legacy

import me.sorihiko.dango.DangoMusic
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.FontRenderer
import net.minecraft.client.gui.Gui
import net.minecraft.client.renderer.GlStateManager
import org.lwjgl.opengl.GL11

/**
 * 原版聊天栏（GuiChat）右上角的聊天翻译控制条。
 *
 * 由 ChatTranslateBarTransformer 在 GuiChat#drawScreen 头部调用 [render]、
 * 在 GuiChat#mouseClicked 头部调用 [mouseClicked]。
 *
 * 两个胶囊：
 *  - 「发送翻译: 开/关」：直接读写 ChatTranslateSettingsModule.translateOutgoing；
 *    从关切到开时顺带启用模块（默认模块是关闭的，否则点了也不生效）。
 *  - 「→ 语言」：循环切换发送消息目标语言，与设置页共用同一个 IntValue。
 *
 * 位置固定在聊天输入框上方、屏幕最右侧（y = height - 28，右边缘 width - 4），
 * 即「原版聊天栏最右侧偏上」。只在聊天界面打开时渲染，状态没有额外每帧开销。
 */
object ChatTranslateBar {

    /** 与 ChatTranslateSettingsModule.LANGUAGE_NAMES / ChatTranslateModule.LANGUAGE_CODES 对齐 */
    private val LANGUAGE_CODES = arrayOf("JP", "EN", "ZH", "CT", "PL")
    private val LANGUAGE_NAMES = arrayOf("日语", "英语", "简体中文", "繁体中文", "波兰语")

    private const val BAR_HEIGHT = 12

    private var visible = false
    private var toggleX = 0
    private var toggleY = 0
    private var toggleW = 0
    private var langX = 0
    private var langW = 0

    /** 在 GuiChat.drawScreen 头部调用；mouseX / mouseY 为缩放后的 GUI 坐标。 */
    fun render(mouseX: Int, mouseY: Int) {
        visible = false
        val module = ChatTranslateRuntime.settings() ?: return
        val mc = Minecraft.getMinecraft()
        val screen = mc.currentScreen ?: return
        val font = mc.fontRendererObj ?: return

        val unicode = font.getUnicodeFlag()
        val on = module.enabled && module.translateOutgoing.get()
        val toggleText = when {
            unicode && on -> "发送翻译: 开"
            unicode -> "发送翻译: 关"
            on -> "TRANS: ON"
            else -> "TRANS: OFF"
        }
        val index = module.outgoingLanguage.value.coerceIn(0, LANGUAGE_CODES.size - 1)
        val langText = if (unicode) "→ " + LANGUAGE_NAMES[index] else "→ " + LANGUAGE_CODES[index]

        val pad = 4
        val gap = 3
        val y = screen.height - 28
        val right = screen.width - 4
        val toggleWidth = font.getStringWidth(toggleText) + pad * 2
        val langWidth = font.getStringWidth(langText) + pad * 2

        toggleY = y
        toggleW = toggleWidth
        langW = langWidth
        langX = right - langWidth
        toggleX = langX - gap - toggleWidth
        visible = true

        val hoverToggle = inRect(mouseX, mouseY, toggleX, y, toggleW)
        val hoverLang = inRect(mouseX, mouseY, langX, y, langWidth)

        val depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
        val lighting = GL11.glIsEnabled(GL11.GL_LIGHTING)
        try {
            if (depth) GlStateManager.disableDepth()
            if (lighting) GlStateManager.disableLighting()
            GlStateManager.enableBlend()
            GlStateManager.color(1f, 1f, 1f, 1f)
            drawChip(font, toggleX, y, toggleWidth, hoverToggle, toggleText,
                if (on) 0xFF67E08A.toInt() else 0xFFB8BCC7.toInt())
            drawChip(font, langX, y, langWidth, hoverLang, langText, 0xFFE6E8EE.toInt())
        } finally {
            GlStateManager.color(1f, 1f, 1f, 1f)
            if (lighting) GlStateManager.enableLighting()
            if (depth) GlStateManager.enableDepth()
        }
    }

    /** 在 GuiChat.mouseClicked 头部调用；点击命中胶囊时立即写入配置并持久化。 */
    fun mouseClicked(mouseX: Int, mouseY: Int, button: Int) {
        if (!visible || button != 0) return
        val module = ChatTranslateRuntime.settings() ?: return

        if (inRect(mouseX, mouseY, toggleX, toggleY, toggleW)) {
            if (module.enabled && module.translateOutgoing.get()) {
                module.translateOutgoing.set(false)
            } else {
                module.translateOutgoing.set(true)
                // 默认模块是关闭的：从「关」切到「开」时一起启用，否则用户点了没有任何效果
                module.enabled = true
            }
            ChatTranslateRuntime.sync()
            persist()
        } else if (inRect(mouseX, mouseY, langX, toggleY, langW)) {
            val next = (module.outgoingLanguage.value + 1) % LANGUAGE_CODES.size
            module.outgoingLanguage.setValue(next)
            ChatTranslateRuntime.sync()
            persist()
        }
    }

    private fun inRect(mouseX: Int, mouseY: Int, x: Int, y: Int, width: Int): Boolean =
        mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + BAR_HEIGHT

    private fun drawChip(font: FontRenderer, x: Int, y: Int, width: Int, hover: Boolean, text: String, color: Int) {
        Gui.drawRect(x, y, x + width, y + BAR_HEIGHT, if (hover) 0xC0303030.toInt() else 0xA0101010.toInt())
        Gui.drawRect(x, y, x + width, y + 1, if (hover) 0x60FFFFFF else 0x20FFFFFF)
        font.drawStringWithShadow(text, (x + 4).toFloat(), (y + 2).toFloat(), color)
    }

    private fun persist() {
        try {
            DangoMusic.instance.configManager.saveIfChanged()
        } catch (_: Throwable) {
            // 持久化失败不影响本次操作，ConfigManager 的定时保存会再兜底
        }
    }
}
