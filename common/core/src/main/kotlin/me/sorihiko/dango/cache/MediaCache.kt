package me.sorihiko.dango.cache

import me.sorihiko.dango.DangoMusic

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 媒体缓存（封面缩略图 / 歌单曲目 / 歌词）的磁盘层。
 *
 * 目录：config/Music PVP/cache/<category>/<sha1(key)>.bin
 * 用 key 的 SHA-1 当文件名：key 里有 ':'、'@' 这类 Windows 文件名非法字符，直接拼会写盘失败。
 *
 * 全部方法都是"失败就当没缓存"：缓存是加速手段，任何 IO 异常都不能影响主流程。
 */
object MediaCache {

    /** 上次清理时间：每类缓存最多 10 分钟清一次，避免每次写盘都扫目录 */
    private val lastPrune = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val PRUNE_INTERVAL_MS = 10 * 60_000L

    private fun root(): Path? = runCatching { DangoMusic.instance.clientDir }.getOrNull()

    private fun dir(category: String): Path? {
        val r = root() ?: return null
        val d = r.resolve("cache").resolve(category)
        return try {
            if (!Files.isDirectory(d)) Files.createDirectories(d)
            d
        } catch (_: Exception) {
            null
        }
    }

    fun file(category: String, key: String): Path? = dir(category)?.resolve(name(key))

    private fun name(key: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(key.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.append(".bin").toString()
    }

    /** 读字节；文件不存在 / 过期 / 读失败都返回 null */
    fun readBytes(category: String, key: String, maxAgeMs: Long): ByteArray? {
        val f = file(category, key) ?: return null
        return try {
            if (!Files.isRegularFile(f)) return null
            if (maxAgeMs > 0 && System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() > maxAgeMs) {
                runCatching { Files.deleteIfExists(f) }
                return null
            }
            val bytes = Files.readAllBytes(f)
            if (bytes.isEmpty()) null else bytes
        } catch (_: Exception) {
            null
        }
    }

    fun writeBytes(category: String, key: String, bytes: ByteArray) {
        val f = file(category, key) ?: return
        try {
            val tmp = f.resolveSibling(f.fileName.toString() + ".tmp")
            Files.write(tmp, bytes)
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
            // 刷新 mtime：命中 / 覆盖写之后按"最近使用"来淘汰，而不是按"第一次写入"
            runCatching { Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())) }
            pruneIfDue(category)
        } catch (_: Exception) {
            runCatching { Files.deleteIfExists(f.resolveSibling(f.fileName.toString() + ".tmp")) }
        }
    }

    fun readText(category: String, key: String, maxAgeMs: Long): String? =
        readBytes(category, key, maxAgeMs)?.toString(StandardCharsets.UTF_8)

    fun writeText(category: String, key: String, text: String) =
        writeBytes(category, key, text.toByteArray(StandardCharsets.UTF_8))

    fun delete(category: String, key: String) {
        val f = file(category, key) ?: return
        runCatching { Files.deleteIfExists(f) }
    }

    /** 命中一次就把 mtime 往前推，让 LRU 淘汰按"最近用过"算（可选调用） */
    fun touch(category: String, key: String) {
        val f = file(category, key) ?: return
        runCatching {
            if (Files.isRegularFile(f)) {
                Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
            }
        }
    }

    private fun pruneIfDue(category: String) {
        val now = System.currentTimeMillis()
        val last = lastPrune[category] ?: 0L
        if (now - last < PRUNE_INTERVAL_MS) return
        lastPrune[category] = now
        val d = dir(category) ?: return
        runCatching {
            // Java 8：Stream 没有 toList()，手工收集
            val files = ArrayList<Pair<Path, Long>>()
            Files.list(d).use { s ->
                s.filter { Files.isRegularFile(it) }.forEach { p ->
                    files += p to runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrDefault(0L)
                }
            }
            if (files.size <= MAX_FILES) return@runCatching
            // 最久没写过的先删，保留 [MAX_FILES]
            files.sortedBy { it.second }.take(files.size - MAX_FILES).forEach { (p, _) ->
                runCatching { Files.deleteIfExists(p) }
            }
        }
    }

    private const val MAX_FILES = 320
}
