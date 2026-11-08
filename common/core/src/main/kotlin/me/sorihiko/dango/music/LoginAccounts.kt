package me.sorihiko.dango.music

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** QQ 音乐开放平台的本地凭证（由用户自行填写，不硬编码、不进日志） */
class QqCredentials(
    val appId: String,
    val appKey: String,
    val packageName: String,
    val callbackUrl: String,
    val endpoint: String,
) {
    val configured: Boolean get() = appId.isNotBlank() && appKey.isNotBlank()
}

/** 已登录的平台账号（令牌只在本地使用，界面只显示脱敏后的 ID） */
class LoginAccount(
    val provider: String,
    val id: String,
    val token: String,
    val title: String,
) {
    /** 侧栏 / 登录页展示用，不暴露完整 ID */
    val display: String get() = if (id.isEmpty()) "$title · 已登录" else "$title · ${maskId(id)}"
}

/**
 * 登录配置与登录态的唯一存储点：config/Music PVP/login_config.json。
 *
 * 结构：
 *   qq      : { appId, appKey, packageName, callbackUrl, endpoint }   开放平台凭证（用户填写）
 *   kugou   : { baseUrl }                                             Kungo-API 服务地址
 *   accounts: { qq: {...}, kugou: {...} }                              各平台登录后拿到的令牌
 *
 * 令牌既不会写进日志，也不会出现在界面上；所有平台读写的都是这一份文件，
 * 因此登录态在播放器界面、设置页、重启游戏后都是一致的。
 */
object LoginAccounts {

    /** 测试时可以指到临时目录；正常运行走 .minecraft/config/Music PVP */
    @Volatile
    internal var configDirOverride: Path? = null

    private const val FILE_NAME = "login_config.json"
    private const val DEFAULT_QQ_ENDPOINT =
        "https://openrpc.music.qq.com/rpc_proxy/fcgi-bin/music_open_api.fcg"
    private const val DEFAULT_KUGOU_URL = "http://127.0.0.1:3000"
    private const val DEFAULT_QQ_PACKAGE = "com.tencent.qqmusic"

    private val lock = Any()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var root: JsonObject? = null

    private fun configDir(): Path =
        configDirOverride
            ?: runCatching { DangoMusic.instance.clientDir }.getOrNull()
            ?: Paths.get(System.getProperty("java.io.tmpdir"), "dango-music")

    private fun file(): File = configDir().resolve(FILE_NAME).toFile()

    fun configFilePath(): String = file().absolutePath

    /** 测试用：丢掉内存缓存，重新读文件 */
    internal fun reload() = synchronized(lock) { root = null }

    /** 确保配置文件存在（第一次打开登录页时生成模板，方便用户知道去哪填凭证） */
    fun ensureConfig() {
        loaded()
    }

    private fun loaded(): JsonObject = synchronized(lock) {
        root ?: load().also { root = it }
    }

    private fun load(): JsonObject {
        val f = file()
        if (!f.isFile) {
            val fresh = template()
            persist(fresh)
            return fresh
        }
        return try {
            val text = String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
            JsonParser().parse(text).asJsonObject
        } catch (e: Exception) {
            DangoLogger.warn("Login config parse failed, using defaults. {}", e.javaClass.simpleName)
            JsonObject()
        }
    }

    private fun template(): JsonObject = JsonObject().apply {
        addProperty(
            "_readme",
            "QQ 音乐：在 developer.y.qq.com 申请开放平台应用后填写 appId / appKey（appKey 属于敏感信息，只放在本机）；" +
                "酷狗音乐：kugou.baseUrl 指向 Kungo-API 服务地址。登录成功后令牌会自动写入 accounts。",
        )
        add("qq", JsonObject().apply {
            addProperty("appId", "")
            addProperty("appKey", "")
            addProperty("packageName", DEFAULT_QQ_PACKAGE)
            addProperty("callbackUrl", "")
            addProperty("endpoint", DEFAULT_QQ_ENDPOINT)
        })
        add("kugou", JsonObject().apply {
            addProperty("baseUrl", DEFAULT_KUGOU_URL)
        })
        add("accounts", JsonObject())
    }

    private fun persist(obj: JsonObject) {
        try {
            val f = file()
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(obj), StandardCharsets.UTF_8)
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            DangoLogger.warn("Login config save failed. {}", e.javaClass.simpleName)
        }
    }

    private fun save() = synchronized(lock) {
        root?.let { persist(it) }
    }

    private fun section(name: String): JsonObject = synchronized(lock) {
        val o = loaded()
        (o.get(name) as? JsonObject) ?: JsonObject().also { o.add(name, it) }
    }

    // ---------------------------------------------------------------- 平台配置

    fun qqCredentials(): QqCredentials {
        val q = section("qq")
        return QqCredentials(
            appId = q.strOr("appId").trim(),
            appKey = q.strOr("appKey").trim(),
            packageName = q.strOr("packageName").trim().ifEmpty { DEFAULT_QQ_PACKAGE },
            callbackUrl = q.strOr("callbackUrl").trim(),
            endpoint = q.strOr("endpoint").trim().ifEmpty { DEFAULT_QQ_ENDPOINT },
        )
    }

    fun kugouBaseUrl(): String = section("kugou").strOr("baseUrl").trim().ifEmpty { DEFAULT_KUGOU_URL }

    // ---------------------------------------------------------------- 当前平台

    /**
     * 当前选中的播放平台：netease / qq / kugou。
     * 只表示界面当前聚焦的账号，切换不会触发任何扫码流程，已登录平台直接复用本地令牌。
     */
    fun activePlatform(): String {
        val value = synchronized(lock) { loaded().strOr("activePlatform") }
        return when (value) {
            "netease", "qq", "kugou" -> value
            else -> "netease"
        }
    }

    /** 记住当前平台；未知值直接忽略，避免配置文件被写坏。 */
    fun setActivePlatform(provider: String) {
        if (provider != "netease" && provider != "qq" && provider != "kugou") return
        synchronized(lock) { loaded().addProperty("activePlatform", provider) }
        save()
    }

    // ---------------------------------------------------------------- 登录态

    fun account(provider: String): LoginAccount? {
        val acc = (section("accounts").get(provider) as? JsonObject) ?: return null
        val id = acc.strOr("userId").ifEmpty { acc.strOr("openId") }
        val token = acc.strOr("accessToken").ifEmpty { acc.strOr("token") }
        if (id.isEmpty() || token.isEmpty()) return null
        return LoginAccount(provider, id, token, if (provider == "qq") "QQ音乐" else "酷狗音乐")
    }

    fun qqAccount(): LoginAccount? = account("qq")

    fun kugouAccount(): LoginAccount? = account("kugou")

    /** 除网易云外，当前已登录的平台账号（侧栏显示用） */
    fun firstLoggedIn(): LoginAccount? = qqAccount() ?: kugouAccount()

    fun anyLoggedIn(): Boolean = qqAccount() != null || kugouAccount() != null

    fun saveQq(accessToken: String, refreshToken: String, openId: String, expiresAtMs: Long) {
        val acc = section("accounts")
        acc.add("qq", JsonObject().apply {
            addProperty("accessToken", accessToken)
            addProperty("refreshToken", refreshToken)
            addProperty("openId", openId)
            addProperty("userId", openId)
            addProperty("expiresAt", expiresAtMs)
            addProperty("savedAt", System.currentTimeMillis())
        })
        save()
        DangoLogger.info("QQ Music login saved locally")
    }

    fun saveKugou(token: String, userId: String, dfid: String) {
        val acc = section("accounts")
        acc.add("kugou", JsonObject().apply {
            addProperty("accessToken", token)
            addProperty("userId", userId)
            addProperty("dfid", dfid)
            addProperty("savedAt", System.currentTimeMillis())
        })
        save()
        DangoLogger.info("Kugou login saved locally")
    }

    fun logout(provider: String) {
        synchronized(lock) {
            val acc = (loaded().get("accounts") as? JsonObject) ?: return@synchronized
            acc.remove(provider)
        }
        save()
    }
}
