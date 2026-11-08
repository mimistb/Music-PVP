package me.sorihiko.dango.music

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * QQ 音乐 / 酷狗音乐扫码登录的单元 + 集成测试。
 *
 * 用本地 HttpServer 模拟两个平台的接口（二维码、轮询状态、换 token），
 * 覆盖：正常登录、用户取消、二维码过期、服务端 5xx、返回格式异常、服务不可达、超时、
 * 凭证未配置、返回加密数据、登录态落盘与退出登录。不会访问任何真实平台。
 *
 * 运行：在 common/ 目录执行 gradlew :core:loginTest
 */

private var checks = 0
private val failures = mutableListOf<String>()

private fun check(name: String, cond: Boolean, detail: String = "") {
    checks++
    if (cond) {
        println("PASS  $name")
    } else {
        println("FAIL  $name" + if (detail.isNotEmpty()) "  -> $detail" else "")
        failures += name
    }
}

private fun await(timeoutMs: Long = 4000, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return true
        Thread.sleep(20)
    }
    return cond()
}

// ---------------------------------------------------------------- Mock 服务

private class MockRequest(
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val rawQuery: String,
)

private class MockServer(private val handler: (MockRequest) -> Pair<Int, String>) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { ex ->
            val req = MockRequest(
                ex.requestURI.path,
                parseQuery(ex.requestURI.rawQuery ?: ""),
                ex.requestHeaders.entries.associate { it.key.lowercase() to (it.value.firstOrNull() ?: "") },
                ex.requestURI.rawQuery ?: "",
            )
            val reply = try {
                handler(req)
            } catch (e: Exception) {
                500 to ""
            }
            val bytes = reply.second.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(reply.first, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun stop() = server.stop(0)
}

private fun parseQuery(raw: String): Map<String, String> {
    if (raw.isEmpty()) return emptyMap()
    return raw.split("&").associate { part ->
        val k = part.substringBefore('=')
        val v = part.substringAfter('=', "")
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }
}

private fun json(vararg pairs: Pair<String, Any>): String {
    val o = JsonObject()
    pairs.forEach { (k, v) ->
        when (v) {
            is Number -> o.addProperty(k, v)
            is Boolean -> o.addProperty(k, v)
            else -> o.addProperty(k, v.toString())
        }
    }
    return o.toString()
}

private fun pngBase64(): String {
    val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
}

// ---------------------------------------------------------------- 环境准备

private const val DEFAULT_QQ_ENDPOINT = "https://openrpc.music.qq.com/rpc_proxy/fcgi-bin/music_open_api.fcg"

private fun writeConfig(
    dir: Path,
    qqAppId: String = "",
    qqAppKey: String = "",
    qqEndpoint: String = DEFAULT_QQ_ENDPOINT,
    kugouBase: String = "http://127.0.0.1:1",
) {
    val qq = JsonObject().apply {
        addProperty("appId", qqAppId)
        addProperty("appKey", qqAppKey)
        addProperty("packageName", "com.tencent.qqmusic")
        addProperty("callbackUrl", "")
        addProperty("endpoint", qqEndpoint)
    }
    val kugou = JsonObject().apply { addProperty("baseUrl", kugouBase) }
    val root = JsonObject().apply {
        add("qq", qq)
        add("kugou", kugou)
        add("accounts", JsonObject())
    }
    Files.createDirectories(dir)
    val text = GsonBuilder().setPrettyPrinting().create().toJson(root)
    Files.write(dir.resolve("login_config.json"), text.toByteArray(StandardCharsets.UTF_8))
    LoginAccounts.configDirOverride = dir
    LoginAccounts.reload()
}

private fun resetProviders() {
    QqMusicLogin.cancel()
    KugouLogin.cancel()
    Thread.sleep(120)
}

private fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix)

// ---------------------------------------------------------------- 用例

private fun testProviderRegistry() {
    check("三个独立登录入口", LoginProviders.all.map { it.id } == listOf("netease", "qq", "kugou"))
}

private fun testQqUnconfigured() {
    resetProviders()
    writeConfig(tempDir("dango-qq-cfg"))
    QqMusicLogin.start()
    check("QQ 未配置凭证时给出提示", QqMusicLogin.state == LoginState.UNCONFIGURED, QqMusicLogin.message)
    check("QQ 未配置时不发请求", QqMusicLogin.qrImage == null)
}

private fun testQqHappyPath() {
    resetProviders()
    val dir = tempDir("dango-qq-ok")
    var polls = 0
    var lastSign = ""
    var lastQuery = ""
    val server = MockServer { req ->
        when (req.query["opi_cmd"]) {
            "fcg_music_custom_sdk_get_qr_code.fcg" -> 200 to json(
                "ret" to 0,
                "sdk_qr_code" to "https://y.qq.com/qr/test",
                "auth_code" to "auth-1",
            )
            "fcg_music_custom_qrcode_auth_poll.fcg" -> {
                lastSign = req.headers["x-qyopi-sign"] ?: ""
                lastQuery = req.rawQuery
                polls++
                if (polls == 1) 200 to json("ret" to -19) else 200 to json("ret" to 0, "encryptString" to "code-xyz")
            }
            "fcg_music_oauth_get_accesstoken.fcg" -> 200 to json(
                "ret" to 0,
                "encryptString" to json(
                    "expireTime" to 3600,
                    "qqmusic_access_token" to "access-token",
                    "qqmusic_open_id" to "open-123456",
                    "qqmusic_refresh_token" to "refresh-token",
                ),
            )
            else -> 404 to "{}"
        }
    }
    writeConfig(dir, qqAppId = "appid", qqAppKey = "secret", qqEndpoint = server.base + "/rpc")
    QqMusicLogin.pollIntervalMs = 80
    QqMusicLogin.pollTimeoutMs = 5000
    QqMusicLogin.start()

    val ok = await { QqMusicLogin.state == LoginState.SUCCESS }
    check("QQ 正常扫码登录成功", ok, QqMusicLogin.message)
    check("QQ 二维码已生成", QqMusicLogin.qrImage != null)
    check(
        "QQ 请求签名正确",
        lastSign == hmacSha256Hex("secret", lastQuery + "&cookie="),
        "sign=$lastSign",
    )
    check("QQ 登录态写入本地", LoginAccounts.qqAccount()?.id == "open-123456")
    LoginAccounts.reload()
    check("QQ 登录态重启后仍在", LoginAccounts.qqAccount() != null)
    server.stop()
    resetProviders()
}

private fun testQqCancel() {
    resetProviders()
    val dir = tempDir("dango-qq-cancel")
    val server = MockServer { req ->
        when (req.query["opi_cmd"]) {
            "fcg_music_custom_sdk_get_qr_code.fcg" ->
                200 to json("ret" to 0, "sdk_qr_code" to "https://y.qq.com/qr/c", "auth_code" to "auth-c")
            "fcg_music_custom_qrcode_auth_poll.fcg" -> 200 to json("ret" to -14)
            else -> 404 to "{}"
        }
    }
    writeConfig(dir, qqAppId = "appid", qqAppKey = "secret", qqEndpoint = server.base + "/rpc")
    QqMusicLogin.pollIntervalMs = 80
    QqMusicLogin.pollTimeoutMs = 3000
    QqMusicLogin.start()

    check("QQ 用户取消登录", await { QqMusicLogin.state == LoginState.CANCELLED }, QqMusicLogin.message)
    check("QQ 取消文案友好", QqMusicLogin.message.contains("取消"), QqMusicLogin.message)
    server.stop()
    resetProviders()
}

private fun testQqExpired() {
    resetProviders()
    val dir = tempDir("dango-qq-expired")
    val server = MockServer { req ->
        when (req.query["opi_cmd"]) {
            "fcg_music_custom_sdk_get_qr_code.fcg" ->
                200 to json("ret" to 0, "sdk_qr_code" to "https://y.qq.com/qr/e", "auth_code" to "auth-e")
            "fcg_music_custom_qrcode_auth_poll.fcg" -> 200 to json("ret" to -10)
            else -> 404 to "{}"
        }
    }
    writeConfig(dir, qqAppId = "appid", qqAppKey = "secret", qqEndpoint = server.base + "/rpc")
    QqMusicLogin.pollIntervalMs = 80
    QqMusicLogin.pollTimeoutMs = 3000
    QqMusicLogin.start()

    check("QQ 二维码过期", await { QqMusicLogin.state == LoginState.EXPIRED }, QqMusicLogin.message)
    server.stop()
    resetProviders()
}

private fun testQqServerError() {
    resetProviders()
    val dir = tempDir("dango-qq-500")
    val server = MockServer { _ -> 500 to "{}" }
    writeConfig(dir, qqAppId = "appid", qqAppKey = "secret", qqEndpoint = server.base + "/rpc")
    QqMusicLogin.pollIntervalMs = 80
    QqMusicLogin.pollTimeoutMs = 3000
    QqMusicLogin.start()

    check("QQ 服务端 5xx 有明确提示", await { QqMusicLogin.state == LoginState.ERROR }, QqMusicLogin.message)
    check("QQ 5xx 文案包含状态码", QqMusicLogin.message.contains("HTTP 500"), QqMusicLogin.message)
    server.stop()
    resetProviders()
}

private fun testQqEncryptedPayload() {
    resetProviders()
    val dir = tempDir("dango-qq-enc")
    val server = MockServer { req ->
        when (req.query["opi_cmd"]) {
            "fcg_music_custom_sdk_get_qr_code.fcg" ->
                200 to json("ret" to 0, "sdk_qr_code" to "https://y.qq.com/qr/x", "auth_code" to "auth-x")
            "fcg_music_custom_qrcode_auth_poll.fcg" ->
                200 to json("ret" to 0, "encryptString" to "Q0lQSEVSVEVYVA==")
            else -> 404 to "{}"
        }
    }
    writeConfig(dir, qqAppId = "appid", qqAppKey = "secret", qqEndpoint = server.base + "/rpc")
    QqMusicLogin.pollIntervalMs = 80
    QqMusicLogin.pollTimeoutMs = 3000
    QqMusicLogin.start()

    check("QQ 加密凭证不会误判成功", await { QqMusicLogin.state == LoginState.ERROR }, QqMusicLogin.message)
    check("QQ 加密凭证提示可读", QqMusicLogin.message.contains("加密"), QqMusicLogin.message)
    server.stop()
    resetProviders()
}

private fun testKugouHappyPath() {
    resetProviders()
    val dir = tempDir("dango-kugou-ok")
    var polls = 0
    val server = MockServer { req ->
        when (req.path) {
            "/raw-api/login/qr/key" -> 200 to """{"code":0,"data":{"qrcode":"kkey-1"}}"""
            "/raw-api/login/qr/create" ->
                200 to """{"code":0,"data":{"base64":"${pngBase64()}","url":"https://kugou.example/qr/1"}}"""
            "/raw-api/login/qr/check" -> {
                polls++
                when (polls) {
                    1 -> 200 to """{"status":1}"""
                    2 -> 200 to """{"status":2}"""
                    else -> 200 to """{"status":4,"token":"kg-token","userid":"99887766","dfid":"dfid-1"}"""
                }
            }
            else -> 404 to "{}"
        }
    }
    writeConfig(dir, kugouBase = server.base)
    KugouLogin.pollIntervalMs = 80
    KugouLogin.pollTimeoutMs = 5000
    KugouLogin.start()

    check("酷狗正常扫码登录成功", await { KugouLogin.state == LoginState.SUCCESS }, KugouLogin.message)
    check("酷狗二维码已生成", KugouLogin.qrImage != null)
    check("酷狗登录态写入本地", LoginAccounts.kugouAccount()?.id == "99887766")
    check("酷狗账号展示已脱敏", LoginAccounts.kugouAccount()?.display?.contains("99887766") == false,
        LoginAccounts.kugouAccount()?.display ?: "")
    LoginAccounts.reload()
    check("酷狗登录态重启后仍在", LoginAccounts.kugouAccount() != null)
    server.stop()
    resetProviders()
}

private fun testKugouExpired() {
    resetProviders()
    val dir = tempDir("dango-kugou-exp")
    val server = MockServer { req ->
        when (req.path) {
            "/raw-api/login/qr/key" -> 200 to """{"code":0,"data":{"qrcode":"kkey-2"}}"""
            "/raw-api/login/qr/create" -> 200 to """{"data":{"url":"https://kugou.example/qr/2"}}"""
            "/raw-api/login/qr/check" -> 200 to """{"status":0}"""
            else -> 404 to "{}"
        }
    }
    writeConfig(dir, kugouBase = server.base)
    KugouLogin.pollIntervalMs = 80
    KugouLogin.pollTimeoutMs = 3000
    KugouLogin.start()

    check("酷狗二维码过期", await { KugouLogin.state == LoginState.EXPIRED }, KugouLogin.message)
    server.stop()
    resetProviders()
}

private fun testKugouServerError() {
    resetProviders()
    val dir = tempDir("dango-kugou-500")
    val server = MockServer { _ -> 500 to "{}" }
    writeConfig(dir, kugouBase = server.base)
    KugouLogin.pollIntervalMs = 80
    KugouLogin.pollTimeoutMs = 3000
    KugouLogin.start()

    check("酷狗服务 5xx 有明确提示", await { KugouLogin.state == LoginState.ERROR }, KugouLogin.message)
    check("酷狗 5xx 文案包含状态码", KugouLogin.message.contains("HTTP 500"), KugouLogin.message)
    server.stop()
    resetProviders()
}

private fun testKugouBadPayload() {
    resetProviders()
    val dir = tempDir("dango-kugou-bad")
    val server = MockServer { _ -> 200 to "not-json" }
    writeConfig(dir, kugouBase = server.base)
    KugouLogin.pollIntervalMs = 80
    KugouLogin.pollTimeoutMs = 3000
    KugouLogin.start()

    check("酷狗返回格式异常有提示", await { KugouLogin.state == LoginState.ERROR }, KugouLogin.message)
    check("酷狗格式异常文案可读", KugouLogin.message.contains("格式异常"), KugouLogin.message)
    server.stop()
    resetProviders()
}

private fun testKugouUnreachable() {
    resetProviders()
    val dir = tempDir("dango-kugou-down")
    val server = MockServer { _ -> 200 to "{}" }
    val base = server.base
    server.stop()
    writeConfig(dir, kugouBase = base)
    KugouLogin.pollIntervalMs = 80
    KugouLogin.pollTimeoutMs = 3000
    KugouLogin.start()

    check("酷狗服务不可达有提示", await { KugouLogin.state == LoginState.ERROR }, KugouLogin.message)
    check("酷狗不可达文案指向服务地址", KugouLogin.message.contains("连接"), KugouLogin.message)
    resetProviders()
}

private fun testKugouTimeout() {
    resetProviders()
    val dir = tempDir("dango-kugou-timeout")
    val server = MockServer { req ->
        when (req.path) {
            "/raw-api/login/qr/key" -> 200 to """{"code":0,"data":{"qrcode":"kkey-3"}}"""
            "/raw-api/login/qr/create" -> 200 to """{"data":{"url":"https://kugou.example/qr/3"}}"""
            else -> 200 to """{"status":1}"""
        }
    }
    writeConfig(dir, kugouBase = server.base)
    KugouLogin.pollIntervalMs = 60
    KugouLogin.pollTimeoutMs = 400
    KugouLogin.start()

    check("酷狗长时间未扫码按过期处理", await { KugouLogin.state == LoginState.EXPIRED }, KugouLogin.message)
    server.stop()
    resetProviders()
}

private fun testLogout() {
    resetProviders()
    val dir = tempDir("dango-logout")
    writeConfig(dir)
    LoginAccounts.saveQq("token", "refresh", "open-1234", 1000L)
    check("退出前为已登录", LoginAccounts.anyLoggedIn())
    LoginAccounts.logout("qq")
    check("退出登录后清空令牌", !LoginAccounts.anyLoggedIn())
}

fun main() {
    println("== QQ 音乐 / 酷狗音乐扫码登录测试（本地 Mock 服务）==")
    testProviderRegistry()
    testQqUnconfigured()
    testQqHappyPath()
    testQqCancel()
    testQqExpired()
    testQqServerError()
    testQqEncryptedPayload()
    testKugouHappyPath()
    testKugouExpired()
    testKugouServerError()
    testKugouBadPayload()
    testKugouUnreachable()
    testKugouTimeout()
    testLogout()
    println()
    if (failures.isEmpty()) {
        println("全部通过：$checks 项")
    } else {
        println("失败 ${failures.size} / $checks 项：${failures.joinToString()}")
    }
    exitProcess(if (failures.isEmpty()) 0 else 1)
}
