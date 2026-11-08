package me.sorihiko.dango.music

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

import java.awt.image.BufferedImage
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 扫码登录的统一状态。三个平台共用一套，界面只按这套状态画东西。
 *
 * IDLE 还没开始 · LOADING 正在拿二维码 · WAITING 等扫码 · SCANNED 已扫码待确认
 * SUCCESS 成功 · EXPIRED 二维码过期 · CANCELLED 用户在手机上取消
 * ERROR 网络 / 接口异常 · UNCONFIGURED 本地缺少凭证配置
 */
enum class LoginState { IDLE, LOADING, WAITING, SCANNED, SUCCESS, EXPIRED, CANCELLED, ERROR, UNCONFIGURED }

/**
 * 一个可以扫码登录的音乐平台。
 *
 * 登录成功后只把平台下发的令牌保存在本地（config/Music PVP/login_config.json），
 * 界面上任何地方读到的都是同一份状态，所以关掉播放器、重启游戏后登录态依然有效，
 * 登录用户和原生扫码登录用户拥有完全相同的权限（平台内没有按登录渠道分级的逻辑）。
 */
interface QrLoginProvider {
    /** 稳定标识，也用作配置键 / 贴图名 */
    val id: String
    /** 显示名，如"网易云音乐" */
    val title: String
    /** 登录方式的一句话说明 */
    val entryHint: String
    /** 本地凭证是否齐全 */
    val configured: Boolean
    /** 缺少配置时给用户的说明 */
    val configHint: String

    val state: LoginState
    /** 当前状态对应的用户提示（失败时是具体原因） */
    val message: String
    val qrImage: BufferedImage?
    /** 每生成一张新二维码 +1 */
    val qrVersion: Int
    /** 已登录账号的展示文案（已脱敏），未登录时为空 */
    val accountName: String

    /** 开始扫码：申请二维码并在后台轮询 */
    fun start()

    /** 取消当前会话（关闭弹窗、切换平台时调用） */
    fun cancel()

    /** 退出该平台的登录，清空本地令牌 */
    fun logout()
}

/** 三个平台的入口，顺序就是登录页上的顺序 */
object LoginProviders {
    val all: List<QrLoginProvider> = listOf(NeteaseLoginProvider, QqMusicLogin, KugouLogin)

    fun byId(id: String): QrLoginProvider? = all.firstOrNull { it.id == id }
}

/** 只允许 http(s)，避免配置里的地址被误写成本地文件路径 */
internal fun isHttpUrl(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** 从 base64（可带 data:image/png;base64, 前缀）解出图片；失败返回 null */
internal fun decodeBase64Image(text: String): BufferedImage? {
    if (text.isEmpty()) return null
    return try {
        val raw = if (text.contains(",")) text.substringAfter(',') else text
        val bytes = Base64.getDecoder().decode(raw.trim())
        javax.imageio.ImageIO.read(bytes.inputStream())
    } catch (e: Exception) {
        null
    }
}

/** 用 zxing 把一段文本画成二维码 */
internal fun makeQrImage(text: String, size: Int = 256): BufferedImage {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val img = BufferedImage(m.width, m.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until m.height) for (x in 0 until m.width) {
        img.setRGB(x, y, if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }
    return img
}

/** 极简 HTTP 客户端：只用于登录链路，异常交给调用方统一翻译成用户提示 */
internal object LoginHttp {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    class Reply(val code: Int, val body: String)

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 8000): Reply {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs + 4000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { String(it.readBytes(), StandardCharsets.UTF_8) } ?: ""
            return Reply(code, body)
        } finally {
            conn.disconnect()
        }
    }
}

// ---------------------------------------------------------------- JSON 取值（对返回结构做容错）

internal fun JsonObject.strOr(key: String): String =
    get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() } ?: ""

internal fun JsonObject.intOr(key: String, def: Int = 0): Int =
    get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() } ?: def

internal fun JsonObject.longOr(key: String, def: Long = 0L): Long =
    get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asLong }.getOrNull() } ?: def

/**
 * 在整棵 JSON 里找第一个叫 [key] 的字段。
 * 第三方接口有时把数据塞在 data / result / 多层嵌套里，这样就不用为每个平台写一套硬编码路径。
 */
internal fun JsonElement?.findValue(key: String): JsonElement? {
    val e = this ?: return null
    if (e.isJsonObject) {
        val o = e.asJsonObject
        val direct = o.get(key)
        if (direct != null && !direct.isJsonNull) return direct
        for ((_, v) in o.entrySet()) {
            val found = v.findValue(key)
            if (found != null) return found
        }
    } else if (e.isJsonArray) {
        for (v in e.asJsonArray) {
            val found = v.findValue(key)
            if (found != null) return found
        }
    }
    return null
}

internal fun JsonElement?.findString(key: String): String {
    val v = findValue(key) ?: return ""
    return runCatching { v.asString }.getOrNull() ?: ""
}

// ---------------------------------------------------------------- 杂项

internal fun hmacSha256Hex(key: String, data: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    val bytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append(Character.forDigit(v shr 4, 16)).append(Character.forDigit(v and 0x0F, 16))
    }
    return sb.toString()
}

internal fun localIp(): String = try {
    java.net.InetAddress.getLocalHost().hostAddress ?: "127.0.0.1"
} catch (e: Exception) {
    "127.0.0.1"
}

/** 账号展示时不暴露完整 ID */
internal fun maskId(id: String): String = when {
    id.isEmpty() -> ""
    id.length <= 4 -> "****"
    else -> id.take(2) + "****" + id.takeLast(2)
}

/** 网络异常翻译成用户能看懂的提示（不打印地址里的凭证） */
internal fun networkFailure(e: Exception, target: String): String = when (e) {
    is java.net.SocketTimeoutException -> "连接$target 超时，请检查网络后重试"
    is java.net.ConnectException -> "无法连接$target，请检查网络或服务是否已启动"
    is java.net.UnknownHostException -> "无法解析$target 的域名，请检查网络"
    else -> "$target 请求失败：" + (e.javaClass.simpleName)
}

/** 网易云扫码登录的适配层：把既有状态机映射成统一状态 */
object NeteaseLoginProvider : QrLoginProvider {
    override val id = "netease"
    override val title = "网易云音乐"
    override val entryHint = "手机网易云 App 扫码"
    override val configured = true
    override val configHint = ""

    override val state: LoginState
        get() = when (NeteaseLogin.state) {
            NeteaseLogin.State.IDLE -> LoginState.IDLE
            NeteaseLogin.State.LOADING -> LoginState.LOADING
            NeteaseLogin.State.WAITING -> LoginState.WAITING
            NeteaseLogin.State.SCANNED -> LoginState.SCANNED
            NeteaseLogin.State.SUCCESS -> LoginState.SUCCESS
            NeteaseLogin.State.EXPIRED -> LoginState.EXPIRED
            NeteaseLogin.State.ERROR -> LoginState.ERROR
        }

    override val message: String
        get() = when (NeteaseLogin.state) {
            NeteaseLogin.State.LOADING -> "正在获取二维码…"
            NeteaseLogin.State.WAITING -> "打开手机网易云音乐 App 扫码"
            NeteaseLogin.State.SCANNED -> "已扫码，请在手机上确认登录"
            NeteaseLogin.State.EXPIRED -> "二维码已过期，请刷新"
            NeteaseLogin.State.ERROR -> "获取二维码失败，请检查网络后重试"
            NeteaseLogin.State.SUCCESS -> "登录成功"
            NeteaseLogin.State.IDLE -> ""
        }

    override val qrImage: BufferedImage? get() = NeteaseLogin.qrImage
    override val qrVersion: Int get() = NeteaseLogin.qrVersion

    override val accountName: String
        get() = if (NeteaseApi.loggedIn) NeteaseApi.nickname.ifEmpty { "已登录" } else ""

    override fun start() = NeteaseLogin.start()
    override fun cancel() = NeteaseLogin.cancel()
    override fun logout() = NeteaseApi.logout()
}
