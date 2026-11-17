package me.sorihiko.dango.legacy

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.platform.Platform
import me.sorihiko.dango.platform.PlayerPose

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiChat
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse

import java.nio.file.Path

/** 1.8.9 的游戏接口。键码本来就是 LWJGL 2 的，不用转换 */
object LegacyPlatform : Platform {

    override val configDir: Path get() = mc.mcDataDir.toPath().resolve("config").resolve(DangoMusic.CONFIG_DIR)

    override val playerName: String get() = mc.session.username

    override val windowWidth: Int get() = mc.displayWidth
    override val windowHeight: Int get() = mc.displayHeight

    override val mouseX: Int get() = Mouse.getX()
    override val mouseY: Int get() = mc.displayHeight - Mouse.getY() - 1

    override fun isMouseDown(button: Int): Boolean = Mouse.isButtonDown(button)

    override fun isKeyDown(key: Int): Boolean = try {
        Keyboard.isKeyDown(key)
    } catch (_: Exception) {
        false
    }

    override val ctrlDown: Boolean get() = GuiScreen.isCtrlKeyDown()

    override var clipboard: String
        get() = GuiScreen.getClipboardString()
        set(value) = GuiScreen.setClipboardString(value)

    override val inGame: Boolean get() = mc.currentScreen == null && mc.theWorld != null

    override val chatOpen: Boolean get() = mc.currentScreen is GuiChat

    override val guiScale: Int get() = ScaledResolution(mc).scaleFactor

    override val fps: Int get() = Minecraft.getDebugFPS()

    override val fov: Float get() = mc.gameSettings.fovSetting

    override val pingMs: Int
        get() {
            val p = mc.thePlayer ?: return 0
            if (mc.isSingleplayer) return 0
            return mc.netHandler?.getPlayerInfo(p.uniqueID)?.responseTime ?: 0
        }

    override val player: PlayerPose?
        get() {
            val p = mc.thePlayer ?: return null
            return PlayerPose(p.posX, p.posY + p.getEyeHeight(), p.posZ, p.rotationYaw, p.rotationPitch)
        }


    override fun openMusicScreen() {
        val floating = try {
            me.sorihiko.dango.module.impls.music.NeteaseMusicModule.of()?.floatingPanel?.get() ?: true
        } catch (_: Throwable) {
            true
        }
        if (floating) {
            FloatingMusicPanel.toggle()
        } else {
            mc.displayGuiScreen(LegacyMusicScreen(floating = false))
        }
    }

    /**
     * 游戏选项背景图：AWT 原生文件选择框。
     * 对话框会阻塞打开它的线程，必须放到独立线程 + AWT 事件线程，否则游戏主循环会停摆；
     * 选完再调度回 Minecraft 主线程回调。
     */
    override fun pickImageFileAsync(onPicked: (String?) -> Unit) {
        Thread({
            val picked = try {
                val result = arrayOfNulls<String>(1)
                java.awt.EventQueue.invokeAndWait { result[0] = showImagePicker() }
                result[0]
            } catch (t: Throwable) {
                me.sorihiko.dango.DangoLogger.warn("Image picker failed: {}", t.toString())
                null
            }
            Minecraft.getMinecraft().addScheduledTask { onPicked(picked) }
        }, "Dango-ImagePicker").apply { isDaemon = true }.start()
    }

    private fun showImagePicker(): String? {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "选择背景图片", java.awt.FileDialog.LOAD)
        dialog.setFilenameFilter { _, name ->
            val lower = name.lowercase()
            lower.endsWith(".png") || lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") || lower.endsWith(".bmp")
        }
        // Windows 原生对话框不认 FilenameFilter，用文件掩码兜底
        dialog.file = "*.png;*.jpg;*.jpeg;*.bmp"
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return java.io.File(dir, file).absolutePath
    }

    override fun renderKillIconPreview(x: Int, y: Int, width: Int, height: Int, preset: Int): Boolean =
        KillIconRuntime.renderPreview(x, y, width, height, preset)
}
