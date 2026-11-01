package me.sorihiko.dango.font

import me.sorihiko.dango.lyrics.LyricLine

/**
 * 一句歌词的逐字进度（卡拉 OK 式渲染）。
 *
 * 有 yrc 逐字时间时按每个字自己的开始时间；普通 LRC 只有整行时间，
 * 就按非空白字的数量把这一句的演唱区间均分，估算每个字什么时候唱到。
 *
 * 用法：每帧 [prepare] 一次，然后按 [prefix] / [suffix] 分两段画：
 * 已经唱过的部分用"已唱颜色"，还没唱到的部分用"未唱颜色"。
 * 每句、每个字号只量一次宽度；唱到的字有变化时才重新切分字符串。
 */
class KaraokeText {

    private val mt = MixedText()

    private var srcLine: LyricLine? = null
    private var srcShown: String? = null
    private var srcEnd = Long.MIN_VALUE
    private var srcSize = -1f
    private var srcFont = ""
    private var times = LongArray(0)

    /** 这一帧显示的整句（已经截断过的字符串） */
    var text = ""
        private set

    /** 整句宽度（逻辑像素） */
    var width = 0f
        private set

    /** 已唱到第几个字符（0..text.length） */
    var sung = 0
        private set

    /** 已经唱过的前缀 / 还没唱到的后缀 */
    var prefix = ""
        private set
    var suffix = ""
        private set
    var prefixWidth = 0f
        private set
    var suffixWidth = 0f
        private set

    /**
     * 准备这一帧：line 为当前句（可能为 null），end 为下一句开始时间，
     * pos 为播放进度，shown 为要显示的字符串（截断后的），size / font 为字体参数。
     */
    fun prepare(line: LyricLine?, end: Long, pos: Long, shown: String, size: Float, font: String) {
        val changed = line !== srcLine || shown != srcShown || end != srcEnd || size != srcSize || font != srcFont
        if (changed) {
            srcLine = line
            srcShown = shown
            srcEnd = end
            srcSize = size
            srcFont = font
            text = shown
            width = if (shown.isEmpty()) 0f else mt.width(shown, size, font)
            times = buildTimes(line, shown, end)
            sung = -1
        }
        val idx = indexAt(pos, end)
        if (idx != sung) {
            sung = idx
            val cut = idx.coerceIn(0, text.length)
            prefix = if (cut <= 0) "" else text.substring(0, cut)
            suffix = text.substring(cut)
            prefixWidth = if (prefix.isEmpty()) 0f else mt.width(prefix, size, font)
            suffixWidth = if (suffix.isEmpty()) 0f else mt.width(suffix, size, font)
        }
    }

    /** 清掉缓存（停歌 / 模块关掉时用） */
    fun reset() {
        srcLine = null
        srcShown = null
        srcEnd = Long.MIN_VALUE
        srcSize = -1f
        srcFont = ""
        times = LongArray(0)
        text = ""
        width = 0f
        sung = -1
        prefix = ""
        suffix = ""
        prefixWidth = 0f
        suffixWidth = 0f
    }

    /** 每个显示字符的开始时间；显示的字符串比原句长（截断的 "..."）时补成整句结束时间 */
    private fun buildTimes(line: LyricLine?, shown: String, end: Long): LongArray {
        val n = shown.length
        if (n == 0) return LongArray(0)
        val out = LongArray(n)
        val start = line?.timeMs ?: 0L
        val endMs = if (end > 0L && end >= start) end else start + 4000L

        var filled = false
        val words = line?.words
        val src = line?.text
        if (words != null && words.isNotEmpty() && src != null && src.isNotEmpty()) {
            val full = StringBuilder()
            for (w in words) full.append(w.text)
            val off = full.indexOf(src)
            if (off >= 0) {
                val starts = LongArray(full.length)
                var p = 0
                for (w in words) {
                    for (k in w.text.indices) {
                        val i = p + k
                        if (i < starts.size) starts[i] = w.startMs
                    }
                    p += w.text.length
                }
                val srcLen = src.length
                for (i in 0 until n) {
                    val j = off + i
                    out[i] = if (i < srcLen && j < starts.size) starts[j] else endMs
                }
                filled = true
            }
        }

        if (!filled) {
            // 没有逐字时间：把这一句的演唱区间（留 15% 给句尾空白）按字数均分
            val srcText = src ?: shown
            val total = srcText.count { !it.isWhitespace() }.coerceAtLeast(1)
            val dur = ((endMs - start).coerceAtLeast(1L) * 0.85).toLong().coerceAtLeast(1L)
            var count = 0
            for (i in 0 until n) {
                out[i] = if (i < srcText.length) start + dur * count / total else endMs
                if (i < srcText.length && !srcText[i].isWhitespace()) count++
            }
        }

        // 时间必须单调不减，否则二分查找会乱
        for (i in 1 until n) if (out[i] < out[i - 1]) out[i] = out[i - 1]
        return out
    }

    /** 播放进度 pos 时已经唱到几个字符 */
    private fun indexAt(pos: Long, end: Long): Int {
        val n = times.size
        if (n == 0) return 0
        if (end > 0L && pos >= end) return n
        var lo = 0
        var hi = n - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= pos) {
                ans = mid + 1
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
