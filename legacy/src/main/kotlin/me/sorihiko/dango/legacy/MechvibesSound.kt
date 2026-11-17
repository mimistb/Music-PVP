package me.sorihiko.dango.legacy

import me.sorihiko.dango.DangoLogger

import java.io.BufferedInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import kotlin.math.log10

/**
 * Mechvibes 单个 wav 的播放封装：javax.sound.sampled.Clip。
 *
 * 同一个 wav 只开一个 Clip 复用，避免 256 个键各开一条音频线；
 * 短促点击重复播放时把播放头拉回 0 再 start。
 */
internal class MechvibesClip private constructor(private val clip: Clip) {

    @Volatile
    private var volume = 0.5f

    fun play() {
        try {
            if (clip.isRunning) clip.stop()
            clip.framePosition = 0
            clip.start()
        } catch (t: Throwable) {
            report(t)
        }
    }

    fun setVolume(value: Float) {
        volume = value
        try {
            val control = clip.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl ?: return
            val db = if (volume <= 0.0001f) -80.0 else (20.0 * log10(volume.toDouble())).coerceIn(-80.0, 0.0)
            control.value = db.toFloat()
        } catch (t: Throwable) {
            // 设备不支持 MASTER_GAIN：保持默认增益
        }
    }

    fun stop() {
        try {
            if (clip.isRunning) clip.stop()
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun close() {
        stop()
        try {
            clip.close()
        } catch (t: Throwable) {
            // ignore
        }
    }

    companion object {

        @Volatile
        private var failureLogged = false

        /** path 形如 "mechvibes/nk_cream/1"，最终读 /assets/glidemods/<path>.wav */
        fun load(path: String): MechvibesClip? {
            val stream = MechvibesClip::class.java.getResourceAsStream("/assets/glidemods/$path.wav") ?: return null
            try {
                BufferedInputStream(stream).use { buffered ->
                    AudioSystem.getAudioInputStream(buffered).use { audio ->
                        val clip = AudioSystem.getClip()
                        clip.open(audio)
                        return MechvibesClip(clip)
                    }
                }
            } catch (t: Throwable) {
                if (!failureLogged) {
                    failureLogged = true
                    DangoLogger.error("Dango Music mechvibes sound unavailable: {}", t)
                }
                return null
            }
        }

        private fun report(t: Throwable) {
            if (!failureLogged) {
                failureLogged = true
                DangoLogger.error("Dango Music mechvibes playback failed: {}", t)
            }
        }
    }
}
