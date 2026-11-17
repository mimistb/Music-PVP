package me.sorihiko.dango.legacy

import me.sorihiko.dango.module.impls.music.MechvibesModule

import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse

import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mechvibes 运行时：把 MechvibesModule 的 5 个配置落到真实按键音效上。
 *
 * 由 ForgeBridge 在 ClientTickEvent(Phase.END) 每 tick 调用 [tick]：
 *   - 启用 / 切换 Type → 后台线程异步加载对应轴体的 wav
 *   - 每 tick 做按键边沿检测（按下才响，长按不连发）
 *   - 音量滑块改动即时写回 Clip 增益
 *   - 关闭模块立即停掉所有正在播放的音效（无残留）
 *
 * 资源固定从 /assets/glidemods/mechvibes 读取，不改动原 jar 的路径与目录名。
 */
internal object MechvibesRuntime {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Dango-Mechvibes-Sound").apply { isDaemon = true }
    }

    private val random = Random()
    private val keyStates = BooleanArray(256)
    private var mouseLeftPressed = false
    private var mouseRightPressed = false

    @Volatile
    private var bank: SoundBank? = null

    private val generation = AtomicInteger()

    private var lastDir: String? = null
    private var lastKeyboardVolume = -1f
    private var lastMouseVolume = -1f
    private var wasEnabled = false

    fun tick() {
        val module = MechvibesModule.of() ?: return

        if (!module.enabled) {
            if (wasEnabled) {
                wasEnabled = false
                // 停播并关闭音频线，避免模块关闭后仍占用 12 条 Clip；重新启用会重新加载
                generation.incrementAndGet() // 作废进行中的异步加载
                val old = bank
                bank = null
                old?.stopAll()
                old?.close()
                lastDir = null
                lastKeyboardVolume = -1f
                lastMouseVolume = -1f
            }
            return
        }

        val dir = module.typeDir()
        if (dir != lastDir) {
            lastDir = dir
            requestLoad(dir)
        }
        wasEnabled = true

        val bank = bank ?: return // 首次加载完成前不做按键检测（与原实现 loaded 门闩一致）

        val keyboardVolume = module.keyboardVolumeValue()
        if (keyboardVolume != lastKeyboardVolume) {
            lastKeyboardVolume = keyboardVolume
            bank.applyKeyboardVolume(keyboardVolume)
        }
        val mouseVolume = module.mouseVolumeValue()
        if (mouseVolume != lastMouseVolume) {
            lastMouseVolume = mouseVolume
            bank.applyMouseVolume(mouseVolume)
        }

        if (module.keyboardEnabled()) {
            try {
                for (code in 0 until 256) {
                    val down = Keyboard.isKeyDown(code)
                    if (down && !keyStates[code]) bank.playKey(code)
                    keyStates[code] = down
                }
            } catch (t: Throwable) {
                // 输入状态不可用时跳过本 tick
            }
        }

        if (module.mouseEnabled()) {
            try {
                val left = Mouse.isButtonDown(0)
                if (left && !mouseLeftPressed) bank.playMouse()
                mouseLeftPressed = left
                val right = Mouse.isButtonDown(1)
                if (right && !mouseRightPressed) bank.playMouse()
                mouseRightPressed = right
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    private fun requestLoad(dir: String) {
        val token = generation.incrementAndGet()
        executor.execute {
            if (token != generation.get()) return@execute
            val loaded = SoundBank.load(dir, random)
            val moduleNow = MechvibesModule.of()
            if (token != generation.get() || moduleNow == null || !moduleNow.enabled) {
                loaded.close()
                return@execute
            }
            val previous = bank
            bank = loaded
            previous?.close()
            val module = MechvibesModule.of()
            loaded.applyKeyboardVolume(module?.keyboardVolumeValue() ?: 0.5f)
            loaded.applyMouseVolume(module?.mouseVolumeValue() ?: 0.5f)
            lastKeyboardVolume = module?.keyboardVolumeValue() ?: 0.5f
            lastMouseVolume = module?.mouseVolumeValue() ?: 0.5f
        }
    }

    /**
     * 一套轴体的音效集合：5 个随机键位样本、6 个特殊键样本、1 个鼠标样本。
     * 256 个键只引用这 12 个 Clip，而不是各自开一条音频线。
     */
    private class SoundBank private constructor(
        private val keys: Array<MechvibesClip?>,
        private val mouse: MechvibesClip?,
        private val clips: List<MechvibesClip>,
    ) {

        fun playKey(code: Int) {
            keys[code]?.play()
        }

        fun playMouse() {
            mouse?.play()
        }

        fun applyKeyboardVolume(volume: Float) {
            clips.forEach { it.setVolume(volume) }
        }

        fun applyMouseVolume(volume: Float) {
            mouse?.setVolume(volume)
        }

        fun stopAll() {
            clips.forEach { it.stop() }
            mouse?.stop()
        }

        fun close() {
            clips.forEach { it.close() }
            mouse?.close()
        }

        companion object {
            fun load(dir: String, random: Random): SoundBank {
                val base = "mechvibes/$dir"
                val regular = arrayOfNulls<MechvibesClip>(5)
                for (i in 0 until 5) {
                    regular[i] = MechvibesClip.load("$base/${i + 1}")
                }
                val special = HashMap<Int, MechvibesClip?>()
                special[15] = MechvibesClip.load("$base/tab")
                special[14] = MechvibesClip.load("$base/backspace")
                special[58] = MechvibesClip.load("$base/capslock")
                special[28] = MechvibesClip.load("$base/enter")
                special[57] = MechvibesClip.load("$base/space")
                val shift = MechvibesClip.load("$base/shift")

                val keys = arrayOfNulls<MechvibesClip>(256)
                for (code in 0 until 256) {
                    keys[code] = if (code == 42 || code == 54) {
                        shift
                    } else {
                        special[code] ?: regular[random.nextInt(5)]
                    }
                }
                val mouse = MechvibesClip.load("mechvibes/mouse")

                val distinct = LinkedHashSet<MechvibesClip>()
                keys.forEach { if (it != null) distinct.add(it) }
                if (mouse != null) distinct.add(mouse)
                return SoundBank(keys, mouse, distinct.toList())
            }
        }
    }
}
