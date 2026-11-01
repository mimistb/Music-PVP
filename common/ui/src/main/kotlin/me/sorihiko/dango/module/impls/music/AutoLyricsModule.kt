package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricsProvider
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.music.LocalLibrary
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.music.NeteaseTrack

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/**
 * 自动识别歌词：网易云按歌曲 ID 拿不到歌词时，
 * 先找本地歌词目录里的同名 .lrc，再按歌名 + 歌手去网易云搜索最接近的一首并取词。
 */
@ModuleMeta(Category.MUSIC, "AutoLyrics")
class AutoLyricsModule : Module() {

    @ModuleValue("本地歌词", "在 config/dangomusic/lyrics 里找同名 .lrc 文件")
    private val localLrc = BooleanValue(true)

    @ModuleValue("联网匹配", "本地没有时，按歌名和歌手搜索网易云并取最接近的歌词")
    private val onlineMatch = BooleanValue(true)

    @ModuleValue("时长容差", "搜索结果与当前歌曲时长相差超过这个秒数就放弃")
    private val durationToleranceSec = IntValue(1, 60, 8)

    /** 旧配置文件里没有这一项时也默认打开；配置里存过的开关状态会在启动后覆盖这里 */
    override fun init() {
        enabled = true
        try {
            Files.createDirectories(lyricsDir())
        } catch (e: Exception) {
            DangoLogger.warn("Lyrics dir create failed: {}", e.toString())
        }
    }

    override fun onEnable() {
        // 之前已经判定"没歌词"的歌，重新识别一次
        try {
            DangoMusic.instance.moduleManager.get(LyricsModule::class.java).clearMissingLyrics()
        } catch (e: Exception) {
            DangoLogger.warn("Auto lyrics reset failed: {}", e.toString())
        }
    }

    /** 给当前歌曲找歌词；找不到返回 null */
    fun resolve(track: NeteaseTrack): Lyrics? {
        if (!enabled) return null
        if (localLrc.get()) {
            // 本地音乐：优先找音频旁边的同名 .lrc
            fromSiblingLrc(track)?.let { return it }
            fromLocalFile(track)?.let { return it }
        }
        if (onlineMatch.get()) fromSearch(track)?.let { return it }
        return null
    }

    // ---------------------------------------------------------------- 本地 .lrc

    /** 本地曲目旁边同名的 .lrc（扫描本地音乐时已经找好） */
    private fun fromSiblingLrc(track: NeteaseTrack): Lyrics? {
        val file = LocalLibrary.lrcFor(track.id) ?: return null
        return try {
            val lines = LyricsProvider.parseLrc(readText(file)).filter { it.text.isNotBlank() }
            if (lines.isEmpty()) null else Lyrics(lines, "本地 LRC")
        } catch (e: Exception) {
            DangoLogger.warn("Local lyric read failed: {} {}", file.fileName, e.toString())
            null
        }
    }

    private fun lyricsDir(): Path = DangoMusic.instance.clientDir.resolve("lyrics")

    private fun fromLocalFile(track: NeteaseTrack): Lyrics? {
        val dir = lyricsDir()
        if (!Files.isDirectory(dir)) return null
        // 歌手为空时不要拼出 "歌名 - " 这种文件名
        val artists = track.artists.map { it.trim() }.filter { it.isNotEmpty() }
        val names = ArrayList<String>(3)
        if (artists.isNotEmpty()) {
            names += track.name + " - " + artists.first()
            names += track.name + " - " + artists.joinToString("、")
        }
        names += track.name
        for (base in names.distinct()) {
            val file = dir.resolve(safeName(base) + ".lrc")
            if (!Files.isRegularFile(file)) continue
            try {
                // 只有时间标签、没有文字的行不算歌词；全是这类行就继续找下一份
                val lines = LyricsProvider.parseLrc(readText(file)).filter { it.text.isNotBlank() }
                if (lines.isEmpty()) continue
                val lyrics = Lyrics(lines, "本地 LRC")
                if (lyrics.lines.isNotEmpty()) return lyrics
            } catch (e: Exception) {
                DangoLogger.warn("Local lyric read failed: {} {}", file.fileName, e.toString())
            }
        }
        return null
    }

    /** 文件名里不能出现的字符换成下划线 */
    private fun safeName(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(120)

    /** LRC 常见两种编码：UTF-8 和 GBK；先按 UTF-8 严格解，失败再按 GBK */
    private fun readText(file: Path): String {
        val bytes = Files.readAllBytes(file)
        val text = try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, Charset.forName("GB18030"))   // GBK 的超集，生僻字也能解
        }
        return text.removePrefix("\uFEFF")
    }

    // ---------------------------------------------------------------- 联网匹配

    /** 候选的匹配情况：分数、命中的关键词数、当前歌曲一共几个关键词、歌名是否完全一致 */
    private class Match(val score: Int, val matched: Int, val total: Int, val titleExact: Boolean)

    /**
     * 关键词搜索：把歌名 + 歌手拆成关键词（中日韩每个字算一个，连续的英文 / 数字算一个），
     * 在搜索结果里数每个候选命中了几个关键词，命中越多越可能是同一首歌。
     * 命中数太少（不到 [MIN_KEYWORD_HITS] 个）就不算数，交给灵动岛 / 2D 歌词显示歌名。
     */
    private fun fromSearch(track: NeteaseTrack): Lyrics? {
        // 「未知歌手」是本地曲目没读到歌手时占的位，拿去搜索只会缩小匹配范围，要剔掉
        val realArtists = track.artists.filter { it.isNotBlank() && it != "未知歌手" }
        // 先把歌名里的 (Live) / 【伴奏】/ feat. 之类的修饰去掉，用真正的关键词去搜
        val clean = cleanTitle(track.name)
        val keyword = (clean + " " + realArtists.joinToString(" ")).trim()
        if (keyword.isEmpty()) return null
        val results = try {
            NeteaseApi.search(keyword, 20)
        } catch (e: Exception) {
            DangoLogger.warn("Auto lyric search failed: {}", e.toString())
            return null
        }
        val wantTitle = norm(clean)
        val wantArtists = realArtists.map { norm(it) }.filter { it.isNotEmpty() }
        val keywords = keywordsOf(clean, realArtists)
        val toleranceMs = durationToleranceSec.value * 1000L
        var best: NeteaseTrack? = null
        var bestMatch: Match? = null
        for (cand in results) {
            val m = match(track, cand, wantTitle, wantArtists, keywords, toleranceMs) ?: continue
            val cur = bestMatch
            if (cur == null || m.score > cur.score) {
                best = cand
                bestMatch = m
            }
        }
        val pick = best ?: return null
        val m = bestMatch ?: return null
        // 关键词命中太少 = 没有把握是同一首；但整首歌本来就不够 4 个关键词时（如英文歌名 + 歌手），
        // 只要歌名完全一致且全部关键词都命中，也算最有可能的那首
        val enough = m.matched >= MIN_KEYWORD_HITS || (m.titleExact && m.matched >= 2 && m.matched >= m.total)
        if (!enough) return null
        return try {
            LyricsProvider.neteaseLyricById(pick.id)
        } catch (e: Exception) {
            DangoLogger.warn("Auto lyric fetch failed: {}", e.toString())
            null
        }
    }

    /** 打分：关键词命中数为主，歌名 / 歌手 / 时长做加分；太不像的直接排除（返回 null） */
    private fun match(
        track: NeteaseTrack, cand: NeteaseTrack,
        wantTitle: String, wantArtists: List<String>, keywords: List<String>, toleranceMs: Long,
    ): Match? {
        val title = norm(cleanTitle(cand.name))
        if (title.isEmpty()) return null
        val exact = title == wantTitle
        // 只靠"互相包含"匹配时，单字标题太容易误中（比如 "雨" 命中 "雨下一整晚"）
        val contains = title.length >= 2 && wantTitle.isNotEmpty() &&
            (title.contains(wantTitle) || wantTitle.contains(title))
        if (!exact && !contains) return null
        val artists = cand.artists.map { norm(it) }.filter { it.isNotEmpty() }
        val artistHit = wantArtists.any { a -> artists.any { it.contains(a) || a.contains(it) } }

        var s = 0
        if (track.durationMs > 0 && cand.durationMs > 0) {
            val diff = abs(track.durationMs - cand.durationMs)
            if (diff > toleranceMs) return null
            if (diff <= 2000) s += 30
            // 时长对不上时，必须歌名完全一致且歌手也对上，才当作同一首
            else if (!exact || !artistHit) return null
        }

        val text = buildString {
            append(cand.name)
            for (a in cand.artists) {
                append(' ')
                append(a)
            }
        }.lowercase()
        var matched = 0
        for (k in keywords) if (text.contains(k)) matched++

        s += matched * 10
        s += if (exact) 100 else 60
        if (artistHit) s += 40
        return Match(s, matched, keywords.size, exact)
    }

    /**
     * 去掉歌名里的括号注释（Live / 伴奏 / Remix …）和 feat. 后面的部分，
     * 只用真正的歌名当关键词，搜索更容易命中正式版。
     */
    private fun cleanTitle(s: String): String {
        val sb = StringBuilder(s.length)
        var depth = 0
        for (c in s) {
            when {
                c == '(' || c == '（' || c == '[' || c == '【' -> depth++
                c == ')' || c == '）' || c == ']' || c == '】' -> if (depth > 0) depth--
                depth == 0 -> sb.append(c)
            }
        }
        val text = sb.toString()
        val i = text.lowercase().indexOf("feat.")
        return (if (i > 0) text.substring(0, i) else text).trim()
    }

    /**
     * 拆关键词：中日韩每个字算一个（避免整句歌名只有一两个"关键词"），
     * 连续的英文 / 数字算一个（至少两个字符）；重复的去掉，免得同一个字把命中数刷高。
     */
    private fun keywordsOf(title: String, artists: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        fun split(s: String) {
            var i = 0
            val n = s.length
            while (i < n) {
                val c = s[i]
                if (c.code >= 0x2E80) {
                    // 中文 / 日文 / 韩文按单字拆；标点和符号不算关键词
                    if (Character.isLetterOrDigit(c)) out += c.lowercaseChar().toString()
                    i++
                } else if (Character.isLetterOrDigit(c)) {
                    var j = i
                    val sb = StringBuilder()
                    while (j < n && s[j].code < 0x2E80 && Character.isLetterOrDigit(s[j])) {
                        sb.append(s[j])
                        j++
                    }
                    val w = sb.toString().lowercase()
                    if (w.length >= 2) out += w
                    i = j
                } else {
                    i++
                }
            }
        }
        split(title)
        for (a in artists) split(a)
        return out.toList()
    }

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private companion object {
        /** 命中多少个关键词才算"确定是同一首" */
        const val MIN_KEYWORD_HITS = 4
    }
}
