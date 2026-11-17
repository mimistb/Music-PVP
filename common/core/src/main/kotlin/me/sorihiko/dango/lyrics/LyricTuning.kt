package me.sorihiko.dango.lyrics

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 每首歌的歌词微调：手动偏移（毫秒）和手动粘贴的 LRC 歌词。
 *
 * 存在 config/Music PVP/lyrics.json，不参与模块配置的 schema 迁移，
 * 换歌时按 track key（provider:id）自动读取。
 */
object LyricTuning {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val offsets = ConcurrentHashMap<String, Int>()
    private val custom = ConcurrentHashMap<String, String>()

    @Volatile
    private var file: Path? = null

    @Synchronized
    fun load(dir: Path) {
        val f = dir.resolve("lyrics.json")
        file = f
        if (!Files.isRegularFile(f)) return
        try {
            val text = String(Files.readAllBytes(f), StandardCharsets.UTF_8)
            val root = JsonParser().parse(text).asJsonObject
            (root.get("offsets") as? JsonObject)?.entrySet()?.forEach { (k, v) ->
                try {
                    offsets[k] = v.asInt
                } catch (_: Exception) {
                }
            }
            (root.get("custom") as? JsonObject)?.entrySet()?.forEach { (k, v) ->
                try {
                    custom[k] = v.asString
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            DangoLogger.warn("Lyric tuning load failed: {}", e.toString())
        }
    }

    fun offsetFor(key: String): Int = offsets[key] ?: 0

    fun setOffset(key: String, ms: Int) {
        if (ms == 0) offsets.remove(key) else offsets[key] = ms
        save()
    }

    fun customFor(key: String): String? = custom[key]

    fun setCustom(key: String, lrc: String?) {
        if (lrc.isNullOrBlank()) custom.remove(key) else custom[key] = lrc
        save()
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        try {
            val root = JsonObject()
            val o = JsonObject()
            for ((k, v) in offsets) o.addProperty(k, v)
            val c = JsonObject()
            for ((k, v) in custom) c.addProperty(k, v)
            root.add("offsets", o)
            root.add("custom", c)
            Files.createDirectories(f.parent)
            Files.write(f, gson.toJson(root).toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            DangoLogger.warn("Lyric tuning save failed: {}", e.toString())
        }
    }
}
