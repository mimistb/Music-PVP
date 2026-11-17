package me.sorihiko.dango.music

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.security.CredCrypto

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
            val obj = JsonParser().parse(text).asJsonObject
            // 老版本写的是明文：读到就解密（明文原样返回），下次保存自动转密文
            val migrated = transformSensitive(obj, encrypting = false)
            if (migrated) persist(obj)
            obj
        } catch (e: Exception) {
            DangoLogger.warn("Login config parse failed, using defaults. {}", e.javaClass.simpleName)
            JsonObject()
        }
    }

    private fun template(): JsonObject = JsonObject().apply {
        addProperty(
            "_readme",
            "QQ 音乐：可直接搜索 / 播放免费歌曲，无需登录；如需会员音质，可在 developer.y.qq.com 申请开放平台应用后填写 appId / appKey（appKey 属于敏感信息，只放在本机）；" +
                "酷狗音乐：扫码登录与播放均已内置，kugou.baseUrl 仅为兼容旧配置保留，可留空。",
        )
        add("qq", JsonObject().apply {
            addProperty("appId", "")
            addProperty("appKey", "")
            addProperty("packageName", DEFAULT_QQ_PACKAGE)
            addProperty("callbackUrl", "")
            addProperty("endpoint", DEFAULT_QQ_ENDPOINT)
        })
        add("kugou", JsonObject().apply {
            addProperty("baseUrl", "")
        })
        add("accounts", JsonObject())
    }

    private fun persist(obj: JsonObject) {
        try {
            val f = file()
            f.parentFile?.mkdirs()
            // 落盘前把所有凭据字段换成密文；内存里始终是明文，读写逻辑不用关心加解密
            val onDisk = JsonParser().parse(obj.toString()).asJsonObject
            transformSensitive(onDisk, encrypting = true)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(onDisk), StandardCharsets.UTF_8)
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            DangoLogger.warn("Login config save failed. {}", e.javaClass.simpleName)
        }
    }

    /** 需要加密的字段：QQ 开放平台 appKey + 各平台令牌 / 设备标识 / 账号 ID */
    private val sensitiveFields = arrayOf("accessToken", "refreshToken", "token", "dfid", "userId", "openId")

    /**
     * 就地加 / 解密敏感字段。
     * @return 是否发生了明文 → 密文的转换（用于把老配置文件一次性迁移）
     */
    private fun transformSensitive(obj: JsonObject, encrypting: Boolean): Boolean {
        var migrated = false
        fun apply(holder: JsonObject, name: String) {
            val el = holder.get(name) ?: return
            if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) return
            val value = el.asString
            if (value.isEmpty()) return
            if (encrypting) {
                if (CredCrypto.isEncrypted(value)) return
                holder.addProperty(name, CredCrypto.encrypt(value))
            } else {
                if (!CredCrypto.isEncrypted(value)) {
                    migrated = true
                    return
                }
                holder.addProperty(name, CredCrypto.decrypt(value))
            }
        }
        (obj.get("qq") as? JsonObject)?.let { apply(it, "appKey") }
        (obj.get("accounts") as? JsonObject)?.let { accounts ->
            for ((_, value) in accounts.entrySet()) {
                val acc = value as? JsonObject ?: continue
                for (name in sensitiveFields) apply(acc, name)
            }
        }
        return migrated
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

    /** 酷狗播放接口需要的设备标识；未登录 / 未保存时为空 */
    fun kugouDfid(): String =
        (section("accounts").get("kugou") as? JsonObject)?.strOr("dfid") ?: ""

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
