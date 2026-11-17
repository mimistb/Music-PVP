package me.sorihiko.dango.security

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 本机凭据的加解密。
 *
 * - 算法：AES/CBC/PKCS5Padding，随机 IV 前置，Base64 后加 `enc:v1:` 前缀；
 * - 密钥：首次使用时在配置目录生成 16 字节随机密钥（`.cred.key`），之后复用；
 * - 兼容：不以 `enc:v1:` 开头的值按明文处理（老配置文件可直接读，下次保存时自动转成密文）。
 *
 * 这层只防"配置文件被随手拷走 / 上传网盘"，不防能读到密钥文件的本机攻击者；
 * 密钥文件丢失或损坏时，已加密的值视为空（等同退出登录），不影响启动。
 */
object CredCrypto {

    private const val PREFIX = "enc:v1:"
    private const val KEY_FILE_NAME = ".cred.key"
    private const val TRANSFORM = "AES/CBC/PKCS5Padding"

    @Volatile private var keyCache: ByteArray? = null
    @Volatile private var keyBroken = false

    private fun configDir(): File? =
        runCatching { DangoMusic.instance.clientDir.toFile() }.getOrNull()

    private fun key(): ByteArray? {
        keyCache?.let { return it }
        if (keyBroken) return null
        val dir = configDir() ?: return null
        synchronized(this) {
            keyCache?.let { return it }
            if (keyBroken) return null
            return try {
                val f = File(dir, KEY_FILE_NAME)
                val bytes = if (f.isFile && f.length() == 16L) {
                    Files.readAllBytes(f.toPath())
                } else {
                    val fresh = ByteArray(16).also { SecureRandom().nextBytes(it) }
                    dir.mkdirs()
                    Files.write(f.toPath(), fresh)
                    fresh
                }
                keyCache = bytes
                bytes
            } catch (e: Exception) {
                // 拿不到密钥：退化成明文（功能可用优先），只记一次日志
                keyBroken = true
                DangoLogger.warn("Credential key unavailable, values stored as plain text. {}", e.javaClass.simpleName)
                null
            }
        }
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    /** 加密；空值 / 已加密 / 没有密钥时原样返回 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty() || isEncrypted(plain)) return plain
        val k = key() ?: return plain
        return try {
            val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(iv))
            val body = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
            PREFIX + Base64.getEncoder().encodeToString(iv + body)
        } catch (e: Exception) {
            DangoLogger.warn("Credential encrypt failed, stored as plain text. {}", e.javaClass.simpleName)
            plain
        }
    }

    /** 解密；不是密文时原样返回，解不开（换过密钥 / 文件被改）时返回空串 */
    fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        val k = key() ?: return ""
        return try {
            val raw = Base64.getDecoder().decode(stored.substring(PREFIX.length))
            if (raw.size <= 16) return ""
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(raw, 0, 16))
            String(cipher.doFinal(raw, 16, raw.size - 16), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            DangoLogger.warn("Credential decrypt failed, treated as signed out. {}", e.javaClass.simpleName)
            ""
        }
    }
}

/**
 * 日志脱敏：把 URL / 异常信息里的令牌、Cookie、手机号打码后再写日志。
 *
 * 异常信息经常带完整请求 URL（QQ / 酷狗的令牌就在 query 里），直接 `e.toString()`
 * 写进日志等于把令牌落盘，所以所有"可能带请求参数"的日志都走这里。
 */
object Redact {

    private val kv = Regex(
        """(?i)\b(access_token|refresh_token|token|app_key|appkey|unikey|music_u|musk_u|__csrf|sid|key)=([^&\s"',;]+)""",
    )
    private val cookieHeader = Regex("""(?i)(cookie\s*:\s*)([^;\r\n]+(?:;[^;\r\n]+)*)""")
    private val phone = Regex("""(?<!\d)(1[3-9]\d)\d{4}(\d{4})(?!\d)""")

    /** 任意文本（通常是异常信息）脱敏 */
    fun text(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        var out = kv.replace(value) { m -> m.groupValues[1] + "=****" }
        out = cookieHeader.replace(out) { m -> m.groupValues[1] + "****" }
        out = phone.replace(out) { m -> m.groupValues[1] + "****" + m.groupValues[2] }
        return out
    }

    /** 单个令牌的展示值：保留首尾各 4 位，短值全打码 */
    fun token(value: String?): String {
        val v = value ?: return ""
        if (v.isEmpty()) return ""
        if (v.length <= 8) return "****"
        return v.take(4) + "…" + v.takeLast(4)
    }

    /** 账号 ID / 手机号展示：保留前 3 后 4 */
    fun id(value: String?): String {
        val v = value ?: return ""
        if (v.length <= 7) return "****"
        return v.take(3) + "****" + v.takeLast(4)
    }
}
