package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger

import java.awt.image.BufferedImage

/**
 * QQ 音乐开放平台扫码登录（developer.y.qq.com / OpenAPI）。
 *
 * 流程：
 *   1. fcg_music_custom_sdk_get_qr_code.fcg 取二维码链接与 auth_code
 *   2. fcg_music_custom_qrcode_auth_poll.fcg 轮询扫码状态
 *   3. fcg_music_oauth_get_accesstoken.fcg 用一次性 code 换 access_token / refresh_token / open_id
 *
 * 签名：X-QYOPI-Sign = HMAC-SHA256(appKey, 原样 queryString + "&cookie=" + cookie) 小写十六进制。
 * 官方要求 appKey 只在后台使用；这里按"本地客户端"的方式从用户自己的配置读取，
 * 只会用于向 QQ 音乐服务器发起请求，不会打印到日志、也不会写进界面。
 */
object QqMusicLogin : QrLoginProvider {
    override val id = "qq"
    override val title = "QQ音乐"
    override val entryHint = "QQ 音乐开放平台扫码"
    override val configured: Boolean get() = LoginAccounts.qqCredentials().configured
    override val configHint =
        "尚未配置 QQ 音乐开放平台凭证。请打开 config/Music PVP/login_config.json，" +
            "在 qq.appId / qq.appKey 填入已在 developer.y.qq.com 审核通过的应用信息后重试。"

    @Volatile private var _state = LoginState.IDLE
    override val state: LoginState get() = _state

    @Volatile private var _message = ""
    override val message: String get() = _message

    @Volatile override var qrImage: BufferedImage? = null
        private set

    @Volatile override var qrVersion = 0
        private set

    @Volatile private var session = 0

    /** 轮询间隔 / 整体超时，测试里会调小 */
    @Volatile internal var pollIntervalMs = 3000L
    @Volatile internal var pollTimeoutMs = 180_000L

    override val accountName: String
        get() = LoginAccounts.qqAccount()?.display ?: ""

    /** 回调里 rest 的错误码都翻译成人话 */
    override fun start() {
        val my = ++session
        val cred = LoginAccounts.qqCredentials()
        if (!cred.configured) {
            qrImage = null
            _state = LoginState.UNCONFIGURED
            _message = "尚未配置 QQ 音乐开放平台凭证"
            return
        }
        if (!isHttpUrl(cred.endpoint)) {
            qrImage = null
            _state = LoginState.UNCONFIGURED
            _message = "QQ 音乐接口地址不合法，请检查 login_config.json 里的 qq.endpoint"
            return
        }
        qrImage = null
        _state = LoginState.LOADING
        _message = "正在获取二维码…"
        Thread({ run(my, cred) }, "Dango-QqLogin").apply {
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
        LoginAccounts.logout("qq")
        ++session
        _state = LoginState.IDLE
        _message = ""
        qrImage = null
    }

    private class QrSession(val authCode: String, val qrText: String, val state: String)

    private class Failure(val friendly: String) : Exception(friendly)

    private fun run(my: Int, cred: QqCredentials) {
        try {
            val qr = fetchQr(cred)
            if (my != session) return
            qrImage = makeQrImage(qr.qrText, 256)
            qrVersion++
            _state = LoginState.WAITING
            _message = "打开手机 QQ 音乐扫一扫，扫描二维码"

            val deadline = System.currentTimeMillis() + pollTimeoutMs
            while (my == session) {
                Thread.sleep(pollIntervalMs)
                if (my != session) return
                if (System.currentTimeMillis() > deadline) {
                    _state = LoginState.EXPIRED
                    _message = "二维码已过期，请刷新"
                    return
                }
                val resp = call(
                    cred,
                    "fcg_music_custom_qrcode_auth_poll.fcg",
                    listOf(
                        "qqmusic_openid_appId" to cred.appId,
                        "qqmusic_openid_authCode" to qr.authCode,
                        "state" to qr.state,
                    ),
                )
                val ret = resp.intOr("ret", -999)
                when (ret) {
                    0 -> {
                        val encrypted = resp.strOr("encryptString")
                        if (!encrypted.startsWith("code-")) {
                            throw Failure("QQ 音乐返回了加密登录数据，当前客户端无法解析，请检查开放平台配置")
                        }
                        exchange(cred, encrypted.removePrefix("code-"))
                        return
                    }
                    -11 -> _state = LoginState.WAITING
                    -19 -> {
                        _state = LoginState.SCANNED
                        _message = "已扫码，请在手机上确认登录"
                    }
                    -10 -> {
                        _state = LoginState.EXPIRED
                        _message = "二维码已过期，请刷新"
                        return
                    }
                    -14, -18 -> {
                        _state = LoginState.CANCELLED
                        _message = "你已在手机上取消登录"
                        return
                    }
                    -16 -> {
                        _state = LoginState.WAITING
                        _message = "操作过于频繁，稍后自动重试…"
                        Thread.sleep(2000)
                    }
                    27 -> throw Failure("该 QQ 账号暂时受限，请稍后再试")
                    else -> throw Failure(friendlyOf(resp, ret))
                }
            }
        } catch (e: Failure) {
            if (my == session) {
                _state = LoginState.ERROR
                _message = e.friendly
            }
        } catch (e: Exception) {
            DangoLogger.warn("QQ Music login failed: {}", e.javaClass.simpleName)
            if (my == session) {
                _state = LoginState.ERROR
                _message = networkFailure(e, "QQ 音乐服务器")
            }
        }
    }

    private fun friendlyOf(resp: JsonObject, ret: Int): String {
        val msg = resp.strOr("msg").ifEmpty { resp.strOr("tip_content") }
        return if (msg.isNotEmpty()) "QQ 音乐登录失败：$msg" else "QQ 音乐登录失败（错误码 $ret）"
    }

    private fun fetchQr(cred: QqCredentials): QrSession {
        val state = (System.currentTimeMillis() / 1000).toString()
        val encryptAuth = JsonObject().apply {
            addProperty("response_type", "code")
            addProperty("state", state)
            addProperty("callbackUrl", cred.callbackUrl.ifEmpty { "https://y.qq.com/" })
        }.toString()
        val resp = call(
            cred,
            "fcg_music_custom_sdk_get_qr_code.fcg",
            listOf(
                "qqmusic_open_appid" to cred.appId,
                "qqmusic_package_name" to cred.packageName,
                "qqmusic_dev_name" to "DangoMusic",
                "qqmusic_qrcode_type" to "universal",
                "qqmusic_encrypt_auth" to encryptAuth,
            ),
        )
        val ret = resp.intOr("ret", -999)
        if (ret != 0) throw Failure(friendlyOf(resp, ret))
        val authCode = resp.strOr("auth_code")
        val qrText = resp.strOr("sdk_qr_code")
        if (authCode.isEmpty()) throw Failure("QQ 音乐没有返回 auth_code，请确认应用已通过审核")
        if (qrText.isEmpty()) throw Failure("QQ 音乐没有返回二维码内容，请稍后重试")
        return QrSession(authCode, qrText, state)
    }

    private fun exchange(cred: QqCredentials, code: String) {
        val resp = call(
            cred,
            "fcg_music_oauth_get_accesstoken.fcg",
            listOf("cmd" to "getToken", "code" to code),
        )
        val ret = resp.intOr("ret", -999)
        if (ret != 0) throw Failure(friendlyOf(resp, ret))
        val raw = resp.strOr("encryptString")
        if (!raw.trimStart().startsWith("{")) {
            throw Failure("QQ 音乐返回了加密凭证，当前客户端无法解析，请检查开放平台配置")
        }
        val token = try {
            JsonParser().parse(raw).asJsonObject
        } catch (e: Exception) {
            throw Failure("QQ 音乐登录凭证解析失败，请重试")
        }
        val access = token.strOr("qqmusic_access_token")
        val openId = token.strOr("qqmusic_open_id")
        if (access.isEmpty() || openId.isEmpty()) {
            throw Failure("QQ 音乐登录凭证不完整，请重试")
        }
        val expire = token.longOr("expireTime", 0L)
        LoginAccounts.saveQq(
            accessToken = access,
            refreshToken = token.strOr("qqmusic_refresh_token"),
            openId = openId,
            expiresAtMs = if (expire > 0 && expire < 10_000_000_000L) expire * 1000L else expire,
        )
        _state = LoginState.SUCCESS
        _message = "登录成功"
    }

    /** 一次 OpenAPI 调用：拼接公共参数 → 签名 → GET */
    private fun call(cred: QqCredentials, cmd: String, extra: List<Pair<String, String>>, cookie: String = ""): JsonObject {
        val params = mutableListOf(
            "opi_cmd" to cmd,
            "app_id" to cred.appId,
            "app_key" to cred.appKey,
            "client_ip" to localIp(),
            "timestamp" to (System.currentTimeMillis() / 1000).toString(),
        )
        params += extra
        val query = params.joinToString("&") { enc(it.first) + "=" + enc(it.second) }
        val sign = hmacSha256Hex(cred.appKey, query + "&cookie=" + cookie)
        val url = cred.endpoint + (if (cred.endpoint.contains('?')) "&" else "?") + query
        val reply = try {
            LoginHttp.get(url, mapOf("X-QYOPI-Sign" to sign, "X-QYOPI-Cookie" to cookie))
        } catch (e: Exception) {
            throw Failure(networkFailure(e, "QQ 音乐服务器"))
        }
        if (reply.code !in 200..299) {
            throw Failure("QQ 音乐服务器返回异常（HTTP ${reply.code}），请稍后重试")
        }
        return try {
            JsonParser().parse(reply.body).asJsonObject
        } catch (e: Exception) {
            throw Failure("QQ 音乐返回数据格式异常，请稍后重试")
        }
    }
}
