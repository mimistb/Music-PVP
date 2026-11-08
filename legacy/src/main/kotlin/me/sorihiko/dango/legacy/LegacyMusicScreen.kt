package me.sorihiko.dango.legacy

import me.sorihiko.dango.DangoClient

import net.minecraft.client.gui.GuiScreen
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse

/** 内置播放器在 1.8.9 里的外壳：把原版界面的事件转给共用的 [me.sorihiko.dango.music.MusicScreen] */
class LegacyMusicScreen(
    /** 关闭后回到哪个界面（null = 回到游戏） */
    private val parent: GuiScreen? = null,
    /** 打开后直接进入设置页（主菜单右上角的功能入口用） */
    private val openSettings: Boolean = false,
) : GuiScreen() {

    private val screen = DangoClient.newMusicScreen { mc.displayGuiScreen(parent) }
    private var openedOnce = false

    override fun initGui() {
        Keyboard.enableRepeatEvents(true)
        // 窗口 resize 会重新走 initGui；只有第一次打开才做加载与跳转设置页
        if (!openedOnce) {
            openedOnce = true
            screen.opened()
            if (openSettings) screen.openSettings()
        }
    }

    override fun onGuiClosed() {
        Keyboard.enableRepeatEvents(false)
        // 被断线 / 其它界面顶掉时也要提交输入并落盘
        screen.closed()
    }

    override fun doesGuiPauseGame() = false

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        screen.render()
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        screen.mouseClicked(mouseButton)
    }

    override fun mouseClickMove(mouseX: Int, mouseY: Int, clickedMouseButton: Int, timeSinceLastClick: Long) {
        screen.mouseDragged()
    }

    override fun mouseReleased(mouseX: Int, mouseY: Int, state: Int) {
        super.mouseReleased(mouseX, mouseY, state)
        screen.mouseReleased()
    }

    override fun handleMouseInput() {
        super.handleMouseInput()
        val wheel = Mouse.getEventDWheel()
        if (wheel != 0) screen.scroll(wheel, Mouse.getEventX(), mc.displayHeight - Mouse.getEventY() - 1)
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        screen.keyTyped(typedChar, keyCode)
    }
}
