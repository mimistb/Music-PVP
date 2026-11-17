package me.sorihiko.dango.legacy

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic

import net.minecraft.client.Minecraft
import net.minecraft.client.network.NetworkPlayerInfo
import net.minecraft.entity.player.EntityPlayer

import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 自动黑名单存储，移植自 Meowtils 的 BlacklistManager。
 *
 * 文件：config/Music PVP/blacklist.json
 * 键：UUID 字符串（解析失败时退回玩家名）
 * 值："<毫秒时间戳> <reason> | <reason> | ..."
 *
 * 原因归类表、追加去重（LinkedHashSet 保序）与多词原因合并逻辑均与原版一致。
 */
internal object BlacklistStore {

    private val nonBlatant = hashSetOf(
        "cheating", "aimassist", "autoclicker", "reach", "velocity", "esp",
        "fakelag", "legit scaffold", "fastplace", "closet", "scaffold"
    )

    private val blatant = hashSetOf(
        "antivoid", "bednuker", "fly", "keepsprint", "killaura", "nofall",
        "noslow", "bhop", "antifireball", "safewalk", "blink", "autoblock",
        "blatant", "strafe", "snipe", "sniper", "sniping"
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val type = object : TypeToken<MutableMap<String, String>>() {}.type

    private var blacklist: MutableMap<String, String> = HashMap()
    private var loaded = false

    private val file: File
        get() = DangoMusic.instance.clientDir.resolve("blacklist.json").toFile()

    @Synchronized
    fun isBlacklisted(uuidOrName: String): Boolean {
        ensureLoaded()
        return blacklist.containsKey(uuidOrName)
    }

    @Synchronized
    fun appendReason(key: String, reason: String) {
        ensureLoaded()
        val existing = blacklist[key]
        if (existing == null) {
            add(key, reason)
            return
        }
        val parts = existing.split(" ", limit = 2)
        val previous = if (parts.size > 1) parts[1] else ""
        val reasons = LinkedHashSet(previous.split(" | "))
        reasons.add(reason)
        blacklist.remove(key)
        blacklist[key] = System.currentTimeMillis().toString() + " " + reasons.joinToString(" | ")
        save()
    }

    /** `/report <name> <多词原因>` → "原因 | 原因"，相邻单词能组成已知原因时合并 */
    fun formatReasons(reasonParts: Array<String>): String {
        val result = ArrayList<String>()
        var i = 0
        while (i < reasonParts.size) {
            val current = reasonParts[i].lowercase()
            if (i < reasonParts.size - 1) {
                val combined = current + " " + reasonParts[i + 1].lowercase()
                if (nonBlatant.contains(combined) || blatant.contains(combined)) {
                    result.add(combined)
                    i += 2
                    continue
                }
            }
            result.add(current)
            i++
        }
        return result.joinToString(" | ")
    }

    private fun add(key: String, reason: String) {
        blacklist[key] = System.currentTimeMillis().toString() + " " + reason
        save()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val target = file
        if (!target.exists()) return
        try {
            FileReader(target).use { reader ->
                val loadedMap: MutableMap<String, String>? = gson.fromJson(reader, type)
                if (loadedMap != null) blacklist = loadedMap
            }
        } catch (t: Throwable) {
            DangoLogger.warn("Blacklist load failed: {}", t.toString())
        }
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            FileWriter(file).use { writer -> gson.toJson(blacklist, writer) }
        } catch (t: Throwable) {
            DangoLogger.warn("Blacklist save failed: {}", t.toString())
        }
    }
}

/**
 * 名字 → UUID，移植自 Meowtils 的 MojangNameToUUID。
 * 先查当前世界/玩家列表里的 GameProfile，查不到再走 Mojang 公开接口；回调切回客户端主线程。
 */
internal object MojangNameLookup {

    private val executor = Executors.newFixedThreadPool(2)

    fun lookup(name: String, callback: (String?) -> Unit) {
        executor.submit {
            val result = lookupLocal(name) ?: lookupFromApi(name)
            mainThread {
                val mc = Minecraft.getMinecraft()
                if (mc.theWorld != null && mc.thePlayer != null) callback(result)
            }
        }
    }

    private fun lookupLocal(name: String): String? {
        val mc = Minecraft.getMinecraft()
        mc.theWorld?.playerEntities?.forEach { player ->
            val profile = (player as EntityPlayer).gameProfile
            if (profile != null && profile.name != null && profile.name.equals(name, ignoreCase = true)) {
                return profile.id.toString()
            }
        }
        mc.netHandler?.playerInfoMap?.forEach { info ->
            val profile = (info as NetworkPlayerInfo).gameProfile
            if (profile != null && profile.name != null && profile.name.equals(name, ignoreCase = true)) {
                return profile.id.toString()
            }
        }
        return null
    }

    private fun lookupFromApi(name: String): String? {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("https://api.mojang.com/users/profiles/minecraft/$name").openConnection() as HttpURLConnection)
            connection.requestMethod = "GET"
            connection.readTimeout = 5000
            connection.connectTimeout = 5000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (connection.responseCode != 200) return null
            connection.inputStream.reader().use { reader ->
                val response = JsonParser().parse(reader).asJsonObject
                val raw = response.get("id").asString
                return raw.replaceFirst(
                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})".toRegex(),
                    "$1-$2-$3-$4-$5"
                )
            }
        } catch (t: Throwable) {
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private fun mainThread(block: () -> Unit) {
        try {
            Minecraft.getMinecraft().addScheduledTask(block)
        } catch (t: Throwable) {
            DangoLogger.warn("Mojang lookup callback dropped: {}", t.toString())
        }
    }
}
