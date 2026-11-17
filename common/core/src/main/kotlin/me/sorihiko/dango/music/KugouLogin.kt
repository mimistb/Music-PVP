package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

/**
 * 酷狗音乐扫码登录（全内置）：直接对接酷狗官方登录接口，不再需要 Kungo-API 或任何本地服务。
 *
 *   1. GET https://login-user.kugou.com/v2/qrcode             → 二维码 key / 图片（需要 web 签名）
 *   2. 直接用接口返回的 base64 图片（失败时本地用 zxing 生成）
 *   3. GET https://login-user.kugou.com/v2/get_userinfo_qrcode → 轮询状态（同一 mid / dfid）
 *
 * 状态码：0 过期 / 1 等待扫码 / 2 已扫码待确认 / 3 处理中 / 4 成功（返回 token + userid）。
 */
object KugouLogin : QrLoginProvider {
    private const val APPID = "1005"
    private const val SRCAPPID = "2919"
    private const val CLIENTVER = "20489"
    private const val WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    private const val LOGIN_HOST = "https://login-user.kugou.com"
    private const val QR_PAGE = "https://h5.kugou.com/apps/loginQRCode/html/index.html"

    override val id = "kugou"
    override val title = "酷狗音乐"
    override val entryHint = "酷狗音乐 App 扫码"
    override val configured: Boolean get() = true
    override val configHint = ""

    @Volatile private var _state = LoginState.IDLE
    override val state: LoginState get() = _state

    @Volatile private var _message = ""
    override val message: String get() = _message

    @Volatile override var qrImage: BufferedImage? = null
        private set

    @Volatile override var qrVersion = 0
        private set

    @Volatile private var session = 0

    @Volatile internal var pollIntervalMs = 3000L
    @Volatile internal var pollTimeoutMs = 180_000L

    /** 测试时指到本地 Mock 服务；正常运行（null）走酷狗官方登录地址 */
    @Volatile internal var endpointOverride: String? = null

    private val host: String get() = endpointOverride ?: LOGIN_HOST

    override val accountName: String
        get() = LoginAccounts.kugouAccount()?.display ?: ""

    override fun start() {
        val my = ++session
        qrImage = null
        _state = LoginState.LOADING
        _message = "正在获取二维码…"
        Thread({ run(my) }, "Dango-KugouLogin").apply {
            isDaemon = true
            start()
        }
    }

    override fun cancel() {
        ++session
        if (_state != LoginState.SUCCESS) _state = LoginState.IDLE
        qrImage = null
    }

    override fun logout() {
        LoginAccounts.logout("kugou")
        ++session
        _state = LoginState.IDLE
        _message = ""
        qrImage = null
    }

    private class Failure(val friendly: String) : Exception(friendly)

    private fun run(my: Int) {
        try {
            // 同一次登录必须复用同一套设备标识，否则酷狗会认为二维码不属于当前设备
            val mid = md5(UUID.randomUUID().toString())
            val dfid = UUID.randomUUID().toString()

            val keyResp = getJson(
                signedUrl(
                    "$host/v2/qrcode",
                    linkedMapOf(
                        "appid" to APPID,
                        "type" to "1",
                        "plat" to "4",
                        "qrcode_txt" to "$QR_PAGE?appid=$APPID&",
                        "srcappid" to SRCAPPID,
                    ),
                    mid,
                    dfid,
                ),
            )
            val keyData = (keyResp.get("data") as? JsonObject) ?: keyResp
            val key = keyData.strOr("qrcode").ifEmpty { keyData.strOr("key") }
            if (key.isEmpty()) throw Failure(failureOf(keyResp, "酷狗二维码获取失败"))

            if (my != session) return
            qrImage = decodeQrImage(keyData.strOr("qrcode_img"))
                ?: makeQrImage("$QR_PAGE?qrcode=$key", 256)
            qrVersion++
            _state = LoginState.WAITING
            _message = "打开手机酷狗音乐，扫描二维码登录"

            val deadline = System.currentTimeMillis() + pollTimeoutMs
            while (my == session) {
                Thread.sleep(pollIntervalMs)
                if (my != session) return
                if (System.currentTimeMillis() > deadline) {
                    _state = LoginState.EXPIRED
                    _message = "二维码已过期，请刷新"
                    return
                }
                val root = getJson(
                    signedUrl(
                        "$host/v2/get_userinfo_qrcode",
                        linkedMapOf(
                            "plat" to "4",
                            "appid" to APPID,
                            "srcappid" to SRCAPPID,
                            "qrcode" to key,
                        ),
                        mid,
                        dfid,
                    ),
                )
                val data = (root.get("data") as? JsonObject) ?: root
                val status = data.strOr("status").toIntOrNull()
                    ?: root.strOr("status").toIntOrNull()
                    ?: -1
                when (status) {
                    0 -> {
                        _state = LoginState.EXPIRED
                        _message = "二维码已过期，请刷新"
                        return
                    }
                    1 -> _state = LoginState.WAITING
                    2 -> {
                        _state = LoginState.SCANNED
                        _message = "已扫码，请在手机上确认登录"
                    }
                    3 -> {
                        _state = LoginState.SCANNED
                        _message = "正在处理登录，请稍候…"
                    }
                    4 -> {
                        val token = data.strOr("token")
                            .ifEmpty { data.strOr("user_token") }
                            .ifEmpty { data.strOr("login_token") }
                        val userId = data.strOr("userid")
                            .ifEmpty { data.strOr("user_id") }
                            .ifEmpty { root.strOr("userid") }
                        if (token.isEmpty() || userId.isEmpty()) {
                            throw Failure("酷狗登录成功，但没有返回 token / userid，请稍后重试")
                        }
                        LoginAccounts.saveKugou(token, userId, dfid)
                        _state = LoginState.SUCCESS
                        _message = "登录成功"
                        return
                    }
                    else -> throw Failure(failureOf(root, "酷狗登录状态异常（$status）"))
                }
            }
        } catch (e: Failure) {
            if (my == session) {
                _state = LoginState.ERROR
                _message = e.friendly
            }
        } catch (e: Exception) {
            DangoLogger.warn("Kugou login failed: {}", e.javaClass.simpleName)
            if (my == session) {
                _state = LoginState.ERROR
                _message = networkFailure(e, "酷狗登录服务")
            }
        }
    }

    /** 酷狗 web 接口签名：MD5(盐 + 按 key 排序的 key=value 串 + 盐) */
    private fun signedUrl(base: String, params: LinkedHashMap<String, String>, mid: String, dfid: String): String {
        val clientTime = System.currentTimeMillis().toString()
        params["clienttime"] = clientTime
        params["clientver"] = CLIENTVER
        params["uuid"] = "-"
        params["mid"] = mid
        params["dfid"] = dfid
        val joined = params.entries
            .map { "${it.key}=${it.value}" }
            .sorted()
            .joinToString("")
        params["signature"] = md5("$WEB_SALT$joined$WEB_SALT")
        return base + "?" + params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    }

    private fun md5(s: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun decodeQrImage(dataUri: String): BufferedImage? {
        if (dataUri.isEmpty()) return null
        return try {
            val raw = dataUri.substringAfter("base64,", dataUri)
            ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(raw)))
        } catch (_: Exception) {
            null
        }
    }

    private fun failureOf(root: JsonObject, fallback: String): String {
        val msg = root.findValue("message").textOrEmpty().ifEmpty { root.findValue("msg").textOrEmpty() }
        val code = root.intOr("code", 0)
        return when {
            msg.isNotEmpty() -> msg
            code != 0 -> "酷狗登录服务返回错误（$code）"
            else -> fallback
        }
    }

    private fun com.google.gson.JsonElement?.textOrEmpty(): String =
        this?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() } ?: ""

    private fun getJson(url: String): JsonObject {
        val reply = try {
            LoginHttp.get(
                url,
                headers = mapOf(
                    "Referer" to "https://www.kugou.com/",
                    "Accept" to "application/json",
                ),
            )
        } catch (e: Exception) {
            throw Failure(networkFailure(e, "酷狗登录服务"))
        }
        if (reply.code !in 200..299) {
            throw Failure("酷狗登录服务返回异常（HTTP ${reply.code}），请检查网络后重试")
        }
        return try {
            JsonParser().parse(reply.body).asJsonObject
        } catch (e: Exception) {
            throw Failure("酷狗登录服务返回数据格式异常")
        }
    }
}
