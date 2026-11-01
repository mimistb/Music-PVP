package me.sorihiko.dango.music

import javazoom.jl.decoder.Bitstream
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.sound.sampled.AudioSystem
import kotlin.math.min

/**
 * 本地音乐：扫描 config/dangomusic/music 里的 mp3 / wav。
 *
 * 歌曲信息优先读 ID3 标签（歌名 / 歌手），没有标签就按文件名 "歌手 - 歌名" 拆；
 * ID 用负数，和网易云的歌曲 ID 区分开。同目录下同名的 .lrc 会被当作这首歌的歌词。
 */
object LocalLibrary {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-LocalMusic").apply { isDaemon = true } }

    @Volatile var tracks: List<NeteaseTrack> = emptyList()
        private set
    @Volatile var scanning = false
        private set

    /** 曲目 ID → 文件；扫描结束时整体替换，播放中重新扫描也不会短暂取空 */
    @Volatile private var fileMap: Map<Long, Path> = emptyMap()
    @Volatile private var lrcMap: Map<Long, Path> = emptyMap()

    /** 音乐目录（不存在时也能直接拿到路径） */
    fun dir(): Path = DangoMusic.instance.clientDir.resolve("music")

    /** 本地曲目的 ID 是负数 */
    fun isLocal(id: Long): Boolean = id < 0

    fun fileFor(id: Long): Path? = fileMap[id]

    /** 这首歌的歌词文件（同目录同名 .lrc），没有就是 null */
    fun lrcFor(id: Long): Path? = lrcMap[id]

    /** 重新扫描目录（后台线程；扫描中重复调用会被忽略） */
    fun scan() {
        if (scanning) return
        scanning = true
        worker.execute {
            try {
                val d = dir()
                Files.createDirectories(d)
                val found = ArrayList<Path>()
                Files.newDirectoryStream(d).use { ds ->
                    for (p in ds) {
                        val n = p.fileName.toString().lowercase()
                        if (n.endsWith(".mp3") || n.endsWith(".wav")) found.add(p)
                    }
                }
                found.sortBy { it.fileName.toString().lowercase() }

                val list = ArrayList<NeteaseTrack>(found.size)
                val fmap = HashMap<Long, Path>()
                val lmap = HashMap<Long, Path>()
                for (p in found) {
                    try {
                        val track = readTrack(p) ?: continue
                        if (fmap.containsKey(track.id)) continue   // 文件名哈希撞了就跳过
                        fmap[track.id] = p
                        val lrc = lrcOf(p)
                        if (lrc != null) lmap[track.id] = lrc
                        list += track
                    } catch (e: Exception) {
                        DangoLogger.warn("Local music read failed: {} {}", p.fileName, e.toString())
                    }
                }
                tracks = list
                fileMap = fmap
                lrcMap = lmap
            } catch (e: Exception) {
                DangoLogger.warn("Local music scan failed: {}", e.toString())
            } finally {
                scanning = false
            }
        }
    }

    // ---------------------------------------------------------------- 歌曲信息

    private fun readTrack(file: Path): NeteaseTrack? {
        val base = file.fileName.toString().substringBeforeLast('.')
        val tags = try {
            readId3(file)
        } catch (_: Exception) {
            null
        }
        var title = tags?.first?.takeIf { it.isNotBlank() }
        var artist = tags?.second?.takeIf { it.isNotBlank() }
        if (title.isNullOrBlank()) {
            val cut = base.indexOf(" - ")
            if (cut > 0) {
                artist = artist ?: base.substring(0, cut).trim()
                title = base.substring(cut + 3).trim()
            } else {
                title = base.trim()
            }
        }
        if (title.isNullOrBlank()) return null
        // 用文件名的哈希做负数 ID：同一个文件每次扫描得到同一个 ID（先去掉符号位，避免溢出成整数）
        val key = base.hashCode().toLong() and 0x7FFF_FFFFL
        val id = -(key + 1)
        val artists = if (artist.isNullOrBlank()) listOf("未知歌手") else listOf(artist)
        return NeteaseTrack(id, title, artists, durationOf(file), "")
    }

    /** 同目录同名 .lrc（大小写不敏感） */
    private fun lrcOf(file: Path): Path? {
        val dir = file.parent ?: return null
        val base = file.fileName.toString().substringBeforeLast('.')
        for (ext in listOf(".lrc", ".LRC", ".Lrc")) {
            val p = dir.resolve(base + ext)
            if (Files.isRegularFile(p)) return p
        }
        // 有些下载工具会把歌词命名成 "歌名.lrc" 而音频是 "歌手 - 歌名.mp3"：再按歌名找一次
        val cut = base.indexOf(" - ")
        if (cut > 0) {
            val name = base.substring(cut + 3).trim()
            if (name.isNotEmpty()) {
                val p = dir.resolve(name + ".lrc")
                if (Files.isRegularFile(p)) return p
            }
        }
        return null
    }

    private fun durationOf(file: Path): Long = try {
        val n = file.fileName.toString().lowercase()
        if (n.endsWith(".wav")) {
            val aff = AudioSystem.getAudioFileFormat(file.toFile())
            val len = aff.frameLength
            val rate = aff.format.frameRate
            if (len > 0 && rate > 0) (len * 1000.0 / rate).toLong() else 0L
        } else {
            mp3Duration(file)
        }
    } catch (_: Exception) {
        0L
    }

    /** 只读帧头不解码，几百首也扫得很快 */
    private fun mp3Duration(file: Path): Long {
        val bs = Bitstream(Files.newInputStream(file).buffered())
        try {
            var ms = 0L
            var frames = 0
            while (frames < 400_000) {
                val h = bs.readFrame() ?: break
                ms += h.ms_per_frame().toLong()
                frames++
                bs.closeFrame()
            }
            return ms
        } finally {
            try {
                bs.close()
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- ID3（只认 TIT2 / TPE1）

    private fun readId3(file: Path): Pair<String?, String?>? {
        val buf = ByteArray(256 * 1024)
        val n = Files.newInputStream(file).use { inp ->
            var off = 0
            while (off < buf.size) {
                val r = inp.read(buf, off, buf.size - off)
                if (r <= 0) break
                off += r
            }
            off
        }
        if (n < 10) return null
        if (buf[0] != 'I'.code.toByte() || buf[1] != 'D'.code.toByte() || buf[2] != '3'.code.toByte()) return null
        val ver = buf[3].toInt()
        val flags = buf[5].toInt()
        val tagSize = syncsafe(buf, 6)
        var p = 10
        if (flags and 0x40 != 0) {
            if (n < p + 4) return null
            val ext = if (ver >= 4) syncsafe(buf, p) else beInt(buf, p)
            p += ext
        }
        val end = min(n, 10 + tagSize)
        var title: String? = null
        var artist: String? = null
        while (p + 10 <= end) {
            val id = String(buf, p, 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = if (ver >= 4) syncsafe(buf, p + 4) else beInt(buf, p + 4)
            val dp = p + 10
            if (size <= 0 || dp + size > n) break
            if (id == "TIT2" || id == "TPE1") {
                val text = decodeText(buf, dp, size)
                if (id == "TIT2") title = text else artist = text
            }
            p = dp + size
        }
        return title to artist
    }

    private fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7f) shl 21) or ((b[at + 1].toInt() and 0x7f) shl 14) or
            ((b[at + 2].toInt() and 0x7f) shl 7) or (b[at + 3].toInt() and 0x7f)

    private fun beInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

    /** 帧内容第一个字节是编码：0 = Latin-1，1 = UTF-16（带 BOM），2 = UTF-16BE，3 = UTF-8 */
    private fun decodeText(b: ByteArray, at: Int, size: Int): String {
        if (size < 2) return ""
        val enc = b[at].toInt() and 0xff
        val raw = b.copyOfRange(at + 1, at + size)
        val s = when (enc) {
            1 -> String(raw, Charset.forName("UTF-16"))
            2 -> String(raw, Charset.forName("UTF-16BE"))
            3 -> String(raw, Charsets.UTF_8)
            else -> String(raw, Charsets.ISO_8859_1)
        }
        return s.trimEnd('\u0000').trim()
    }
}
