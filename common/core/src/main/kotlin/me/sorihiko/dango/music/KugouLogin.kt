package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger

import java.awt.image.BufferedImage

/**
 * 酷狗音乐扫码登录（Kungo-API：/login/qr/key → /login/qr/create → /login/qr/check）。
 *
 * 服务地址来自 login_config.json 的 kugou.baseUrl（默认 http://127.0.0.1:3000，
 * 也可以指向自建 / 可信的 Kungo-API 实例）。返回结构可能把数据放在 data 里，
 * 所以取值时对 data 和根节点都做兼容。
 *
 * 状态码：0 过期 / 1 等待扫码 / 2 已扫码待确认 / 3 处理中 / 4 成功（返回 token + userid）。
 */
object KugouLogin : QrLoginProvider {
    override val id = "kugou"
    override val title = "酷狗音乐"
    override val entryHint = "酷狗音乐 App 扫码"
    override val configured: Boolean get() = isHttpUrl(LoginAccounts.kugouBaseUrl())
    override val configHint =
        "尚未配置酷狗登录服务。请在 config/Music PVP/login_config.json 的 kugou.baseUrl 填写 " +
            "Kungo-API 服务地址（默认 http://127.0.0.1:3000），并确认服务已启动。"

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

    override val accountName: String
        get() = LoginAccounts.kugouAccount()?.display ?: ""

    override fun start() {
        val my = ++session
        val base = LoginAccounts.kugouBaseUrl().trimEnd('/')
        if (!isHttpUrl(base)) {
            qrImage = null
            _state = LoginState.UNCONFIGURED
            _message = "酷狗登录服务地址不合法，请检查 login_config.json 里的 kugou.baseUrl"
            return
        }
        qrImage = null
        _state = LoginState.LOADING
        _message = "正在获取二维码…"
        Thread({ run(my, base) }, "Dango-KugouLogin").apply {
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

    private fun run(my: Int, base: String) {
        try {
            val keyResp = getJson("$base/raw-api/login/qr/key?platform=life&timestamp=${System.currentTimeMillis()}")
            val key = keyResp.findString("qrcode").ifEmpty { keyResp.findString("key") }
            if (key.isEmpty()) throw Failure(failureOf(keyResp, "获取二维码 key 失败"))

            val createResp = getJson(
                "$base/raw-api/login/qr/create?key=${enc(key)}&qrimg=true&platform=life&timestamp=${System.currentTimeMillis()}",
            )
            val base64 = createResp.findString("base64").ifEmpty { createResp.findString("qrimg") }
            val url = createResp.findString("url").ifEmpty { createResp.findString("qrurl") }
            val image = decodeBase64Image(base64)
                ?: if (url.isNotEmpty()) makeQrImage(url, 256) else null
            if (image == null) throw Failure(failureOf(createResp, "二维码生成失败，请稍后重试"))

            if (my != session) return
            qrImage = image
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
                    "$base/raw-api/login/qr/check?key=${enc(key)}&platform=life&timestamp=${System.currentTimeMillis()}",
                )
                val data = (root.get("data") as? JsonObject) ?: root
                val status = data.strOr("status").toIntOrNull()
                    ?: root.strOr("status").toIntOrNull()
                    ?: -1
                when (status) {
                    1 -> _state = LoginState.WAITING
                    2 -> {
                        _state = LoginState.SCANNED
                        _message = "已扫码，请在手机上确认登录"
                    }
                    3 -> {
                        _state = LoginState.SCANNED
                        _message = "正在处理登录，请稍候…"
                    }
                    0 -> {
                        _state = LoginState.EXPIRED
                        _message = "二维码已过期，请刷新"
                        return
                    }
                    4 -> {
                        val token = data.strOr("token")
                            .ifEmpty { data.strOr("user_token") }
                            .ifEmpty { data.strOr("login_token") }
                        val userId = data.strOr("userid")
                            .ifEmpty { data.strOr("user_id") }
                            .ifEmpty { root.strOr("userid") }
                        if (token.isEmpty() || userId.isEmpty()) {
                            throw Failure("酷狗登录成功，但没有返回 token / userid，请检查登录服务")
                        }
                        val dfid = data.strOr("dfid").ifEmpty { fetchDfid(base) }
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

    /** 设备标识：登录不一定需要，失败时留空即可 */
    private fun fetchDfid(base: String): String = try {
        val root = getJson("$base/raw-api/register/dev?platform=life&timestamp=${System.currentTimeMillis()}")
        root.findString("dfid")
    } catch (e: Exception) {
        ""
    }

    private fun failureOf(root: JsonObject, fallback: String): String {
        val msg = root.findString("message").ifEmpty { root.findString("msg") }
        val code = root.intOr("code", 0)
        return when {
            msg.isNotEmpty() -> msg
            code != 0 -> "酷狗登录服务返回错误（$code）"
            else -> fallback
        }
    }

    private fun getJson(url: String): JsonObject {
        val reply = try {
            LoginHttp.get(url)
        } catch (e: Exception) {
            throw Failure(networkFailure(e, "酷狗登录服务"))
        }
        if (reply.code !in 200..299) {
            throw Failure("酷狗登录服务返回异常（HTTP ${reply.code}），请确认 Kungo-API 已启动")
        }
        return try {
            JsonParser().parse(reply.body).asJsonObject
        } catch (e: Exception) {
            throw Failure("酷狗登录服务返回数据格式异常，请检查服务地址是否正确")
        }
    }
}
