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
    KugouLogin.endpointOverride = null
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
            "/v2/qrcode" ->
                200 to """{"status":1,"data":{"qrcode":"kkey-1","qrcode_img":"${pngBase64()}"}}"""
            "/v2/get_userinfo_qrcode" -> {
                polls++
                when (polls) {
                    1 -> 200 to """{"status":1}"""
                    2 -> 200 to """{"status":2}"""
                    else -> 200 to """{"status":4,"token":"kg-token","userid":"99887766"}"""
                }
            }
            else -> 404 to "{}"
        }
    }
    KugouLogin.endpointOverride = server.base
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
            "/v2/qrcode" -> 200 to """{"status":1,"data":{"qrcode":"kkey-2"}}"""
            "/v2/get_userinfo_qrcode" -> 200 to """{"status":0}"""
            else -> 404 to "{}"
        }
    }
    KugouLogin.endpointOverride = server.base
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
    KugouLogin.endpointOverride = server.base
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
    KugouLogin.endpointOverride = server.base
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
    KugouLogin.endpointOverride = base
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
            "/v2/qrcode" -> 200 to """{"status":1,"data":{"qrcode":"kkey-3"}}"""
            else -> 200 to """{"status":1}"""
        }
    }
    KugouLogin.endpointOverride = server.base
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

/**
 * 三平台登录态相互隔离，任意平台已登录时可自由切换：
 * 切换只改 activePlatform，不影响其它平台的令牌；退出一个平台不影响另外两个。
 */
private fun testPlatformSwitching() {
    resetProviders()
    val dir = tempDir("dango-switch")
    writeConfig(dir)

    check("默认平台为网易云", LoginAccounts.activePlatform() == "netease", LoginAccounts.activePlatform())
    LoginAccounts.saveQq("qq-token", "qq-refresh", "qq-open-1", 0L)
    LoginAccounts.saveKugou("kg-token", "kugou-user-1", "dfid-1")
    check("QQ 与酷狗可同时登录", LoginAccounts.qqAccount() != null && LoginAccounts.kugouAccount() != null)

    LoginAccounts.setActivePlatform("qq")
    LoginAccounts.reload()
    check("切换到 QQ 并持久化", LoginAccounts.activePlatform() == "qq", LoginAccounts.activePlatform())
    check("切换 QQ 后酷狗登录态仍在", LoginAccounts.kugouAccount() != null)

    LoginAccounts.setActivePlatform("kugou")
    LoginAccounts.reload()
    check("切换到酷狗并持久化", LoginAccounts.activePlatform() == "kugou", LoginAccounts.activePlatform())
    check("切换酷狗后 QQ 登录态仍在", LoginAccounts.qqAccount() != null)

    LoginAccounts.setActivePlatform("bogus")
    check("非法平台名被忽略", LoginAccounts.activePlatform() == "kugou", LoginAccounts.activePlatform())

    LoginAccounts.logout("qq")
    check("退出 QQ 不影响酷狗", LoginAccounts.qqAccount() == null && LoginAccounts.kugouAccount() != null)
    check("登录态回退到仍在线的平台", LoginAccounts.firstLoggedIn()?.provider == "kugou")
    LoginAccounts.reload()
    check("退出结果重启后仍生效", LoginAccounts.qqAccount() == null && LoginAccounts.kugouAccount() != null)
}

/** 网易云沿用既有扫码逻辑，适配层不应改变其状态与入口 */
private fun testNeteaseAdapter() {
    val netease = LoginProviders.byId("netease")
    check("网易云登录入口存在", netease is NeteaseLoginProvider)
    check("网易云无需本地凭证", netease?.configured == true)
    check("网易云空闲状态映射正确", netease?.state == LoginState.IDLE, netease?.state?.name ?: "")
    check("未知平台 id 返回 null", LoginProviders.byId("unknown") == null)
}

/** 推荐歌单接口返回结构解析：三个平台各自落地成统一的 NeteasePlaylist */
private fun testRecommendParsing() {
    val qq = QqMusicApi.parseRecommend(
        com.google.gson.JsonParser().parse(
            """{"code":0,"data":{"list":[
                {"dissid":"12345","dissname":"QQ热歌","imgurl":"http://p.qpic.cn/a.jpg","song_count":42},
                {"dissid":"","dissname":"坏数据"}
            ]}}""",
        ).asJsonObject,
        30,
    )
    check("QQ 推荐歌单解析数量", qq.size == 1, "size=${qq.size}")
    check("QQ 推荐歌单字段正确",
        qq.firstOrNull()?.let { it.id == 12345L && it.name == "QQ热歌" && it.trackCount == 42 && it.provider == "qq" } == true,
        qq.firstOrNull()?.let { "id=${it.id}, name=${it.name}, count=${it.trackCount}, provider=${it.provider}" } ?: "")
    check("QQ 封面统一为 https", qq.firstOrNull()?.coverUrl?.startsWith("https://") == true)

    val qqTracks = QqMusicApi.parsePlaylistTracks(
        com.google.gson.JsonParser().parse(
            """{"code":0,"cdlist":[{"songlist":[
                {"songmid":"mid1","songname":"歌1","singer":[{"name":"A"},{"name":"B"}],
                 "album":{"mid":"alb","name":"专辑"},"interval":200,"pay":{"pay_play":0}},
                {"songmid":"","songname":"坏数据"}
            ]}]}""",
        ).asJsonObject,
    )
    check("QQ 歌单歌曲解析数量", qqTracks.size == 1, "size=${qqTracks.size}")
    check("QQ 歌单歌曲字段正确",
        qqTracks.firstOrNull()?.let {
            it.provider == "qq" && it.providerId == "mid1" && it.name == "歌1" &&
                it.artists == listOf("A", "B") && it.durationMs == 200_000L && it.fee == 0
        } == true,
        qqTracks.firstOrNull()?.let { "provider=${it.provider}, pid=${it.providerId}, artists=${it.artists}" } ?: "")

    val kugou = KugouApi.parseRecommend(
        com.google.gson.JsonParser().parse(
            """{"plist":{"list":{"info":[
                {"specialid":321,"specialname":"酷狗精选","imgurl":"http://imgs.kugou.com/a.jpg","songcount":30}
            ]}}}""",
        ).asJsonObject,
        30,
    )
    check("酷狗推荐歌单解析",
        kugou.size == 1 && kugou[0].id == 321L && kugou[0].name == "酷狗精选" && kugou[0].provider == "kugou",
        kugou.firstOrNull()?.let { "id=${it.id}, name=${it.name}" } ?: "empty")

    val kugouTracks = KugouApi.parsePlaylistTracks(
        com.google.gson.JsonParser().parse(
            """{"status":1,"data":{"info":[
                {"hash":"h1","songname":"酷狗歌","singername":"X&Y","duration":180,
                 "album_id":"al","album_name":"专辑"}
            ]}}""",
        ).asJsonObject,
    )
    check("酷狗歌单歌曲解析",
        kugouTracks.size == 1 && kugouTracks[0].provider == "kugou" &&
            kugouTracks[0].providerId == "h1|al" && kugouTracks[0].artists == listOf("X", "Y") &&
            kugouTracks[0].durationMs == 180_000L,
        kugouTracks.firstOrNull()?.let { "pid=${it.providerId}, artists=${it.artists}" } ?: "empty")
}

fun main() {
    println("== 三平台扫码登录 / 平台切换 / 推荐歌单解析测试（本地 Mock 服务）==")
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
    testPlatformSwitching()
    testNeteaseAdapter()
    testRecommendParsing()
    println()
    if (failures.isEmpty()) {
        println("全部通过：$checks 项")
    } else {
        println("失败 ${failures.size} / $checks 项：${failures.joinToString()}")
    }
    exitProcess(if (failures.isEmpty()) 0 else 1)
}
