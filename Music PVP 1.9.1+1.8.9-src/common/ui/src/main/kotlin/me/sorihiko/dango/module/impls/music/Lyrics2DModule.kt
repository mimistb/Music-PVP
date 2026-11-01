package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.WorldRenderEndEvent
import me.sorihiko.dango.font.KaraokeText
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color

import kotlin.math.max
import kotlin.math.min

/**
 * 2D 歌词：显示在游戏画面里（不是 3D、不是灵动岛）。
 *  - 逐字染色：唱过的字用主色，还没唱到的字用暗色
 *  - 可选液态玻璃背景
 *  - 可选"分割已唱 / 未唱"：唱过的在前一排、没唱的在后一排
 *  - 可选竖直显示：一句歌词一个字一行
 * 打开聊天栏后可以直接用鼠标拖动，位置会保存。
 * 本模块的歌词开关和 3D 歌词、灵动岛歌词三选一（由 Lyrics 模块统一互斥）。
 */
@ModuleMeta(Category.MUSIC, "Lyrics2D")
class Lyrics2DModule : Module() {

    @ModuleValue("字号", "歌词文字大小")
    private val fontSize = FloatValue(10f, 36f, 16f, 0)

    @ModuleValue("字体颜色", "歌词颜色：金 / 红 / 粉 / 白")
    private val lyrics2dColor = IntValue(0, 3, 3)

    @ModuleValue("提前歌词", "在当前这句后面提前显示还没唱到的下一句")
    private val advance = BooleanValue(true)

    @ModuleValue("淡化平滑", "换句时淡出淡入，过渡更柔和")
    private val fade = BooleanValue(true)

    @ModuleValue("X 坐标", "水平位置（打开聊天栏后可拖动）")
    private val posX = FloatValue(0f, 1f, 0.5f, 3)

    @ModuleValue("Y 坐标", "垂直位置（打开聊天栏后可拖动）")
    private val posY = FloatValue(0f, 1f, 0.8f, 3)

    @ModuleValue("显示歌词", "2D 歌词的显示开关；打开时会自动关闭 3D 歌词和灵动岛歌词")
    private val lyrics = BooleanValue(true)

    @ModuleValue("液态玻璃背景", "歌词后面显示液态玻璃底板")
    private val glass = BooleanValue(true)

    @ModuleValue("分割已唱与未唱", "唱过的歌词排前一排，还没唱到的排后一排")
    private val splitRows = BooleanValue(false)

    @ModuleValue("竖直显示", "一句歌词按单个字竖直排列")
    private val vertical = BooleanValue(false)

    @ModuleValue("超长换行", "歌词超过屏幕宽度时自动换成多行（最多两行）")
    private val wrap = BooleanValue(false)

    private var context: GuiCanvas? = null
    private val mt = MixedText()
    private val kara = KaraokeText()

    private var lastNanos = 0L
    private var dragging = false
    private var dragDX = 0f
    private var dragDY = 0f

    /** 换句时的淡入进度 */
    private var alpha = 1f
    private var lastKey: String? = null

    /** 颜色缓存：换句 / 换颜色都不用每帧 copy */
    private var colorIdx = -1
    private var colorMain: Color = LyricPalette.all[3]
    private var colorNext: Color = colorMain

    private fun updateColors() {
        val idx = lyrics2dColor.value
        if (idx == colorIdx) return
        colorIdx = idx
        val c = LyricPalette.of(idx)
        colorMain = c
        colorNext = Color(c.r, c.g, c.b, c.a * 0.55f)
    }

    /** 歌词排版缓存：同一句、同一字号、同一最大宽度每帧直接复用 */
    private class TextCache {
        var src: String? = null
        var size = 0f
        var maxW = 0f
        var shown = ""
        var width = 0f
    }

    private val cCur = TextCache()
    private val cNxt = TextCache()
    private val cTitle = TextCache()
    private val cVert = TextCache()

    /** 超长换行：只在整句 / 字号 / 最大宽度变化时重排；唱到的位置变化时只重切字符串 */
    private class WrapCache {
        var src: String? = null
        var size = 0f
        var maxW = 0f
        var lines: Array<String> = emptyArray()
        var starts: IntArray = IntArray(0)
        var widths: FloatArray = FloatArray(0)
        var width = 0f
        var sungAt = -1
        var pre: Array<String> = emptyArray()
        var suf: Array<String> = emptyArray()
        var preW: FloatArray = FloatArray(0)
    }

    private val cWrap = WrapCache()

    private fun wrapLayout(src: String, size: Float, maxW: Float): WrapCache {
        val c = cWrap
        if (c.src == src && c.size == size && c.maxW == maxW) return c
        c.src = src
        c.size = size
        c.maxW = maxW

        val texts = ArrayList<String>(2)
        val starts = ArrayList<Int>(2)
        val n = src.length
        var i = 0
        while (i < n && texts.size < 2) {
            while (i < n && src[i] == ' ') i++
            if (i >= n) break
            val rest = src.substring(i)
            if (width(rest, size) <= maxW) {
                texts.add(rest)
                starts.add(i)
                break
            }
            if (texts.size == 1) {
                // 第二行也放不下：截断加省略号
                texts.add(trim(rest, maxW, size))
                starts.add(i)
                break
            }
            // 二分找第一行放得下的最长前缀
            var lo = 0
            var hi = rest.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (width(rest.substring(0, mid), size) <= maxW) lo = mid else hi = mid - 1
            }
            var end = lo.coerceAtLeast(1)
            val sp = rest.lastIndexOf(' ', end)
            if (sp > end / 2) end = sp
            texts.add(rest.substring(0, end).trimEnd())
            starts.add(i)
            i += end
        }
        if (texts.isEmpty()) {
            texts.add(src)
            starts.add(0)
        }

        c.lines = texts.toTypedArray()
        c.starts = starts.toIntArray()
        c.widths = FloatArray(c.lines.size) { width(c.lines[it], size) }
        c.width = c.widths.maxOrNull() ?: 0f
        c.sungAt = -1
        c.pre = Array(c.lines.size) { "" }
        c.suf = Array(c.lines.size) { "" }
        c.preW = FloatArray(c.lines.size)
        return c
    }

    /** 按已唱字数把每一行切成「已唱 / 未唱」两段（唱到的字变了才重切） */
    private fun wrapSplits(c: WrapCache, sung: Int) {
        if (c.sungAt == sung) return
        c.sungAt = sung
        for (k in c.lines.indices) {
            val line = c.lines[k]
            val take = (sung - c.starts[k]).coerceIn(0, line.length)
            c.pre[k] = line.substring(0, take)
            c.suf[k] = line.substring(take)
            c.preW[k] = if (take == 0) 0f else width(c.pre[k], c.size)
        }
    }

    private fun trimmed(c: TextCache, s: String, size: Float, maxW: Float): String {
        if (s === c.src && size == c.size && maxW == c.maxW) return c.shown
        val shown = trim(s, maxW, size, Theme.FONT_MEDIUM)
        c.src = s
        c.size = size
        c.maxW = maxW
        c.shown = shown
        c.width = width(shown, size, Theme.FONT_MEDIUM)
        return shown
    }

    private fun lyricView(): LyricView? = LyricsModule.of()?.lyricView(advance.get())

    override fun init() {
        instance = this
    }

    /** 与 3D 歌词 / 灵动岛歌词互斥时由 Lyrics 模块调用 */
    fun setLyrics(on: Boolean) = lyrics.set(on)

    /** 正在显示 2D 歌词 */
    val lyricsEnabled: Boolean get() = enabled && lyrics.get()

    // ---------------------------------------------------------------- 渲染

    @EventHandler
    fun render(e: WorldRenderEndEvent) {
        if (!enabled || !lyrics.get()) {
            alpha = 0f
            lastKey = null
            dragging = false
            return
        }
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now

        if (!MusicPlayer.active || MusicPlayer.current == null) {
            alpha = 0f
            lastKey = null
            dragging = false
            kara.reset()
            return
        }
        val v = lyricView() ?: return
        val cur = v.current
        val nxt = if (advance.get()) v.next else null
        val fallback = v.fallback
        if (cur == null && nxt == null && fallback == null) {
            // 歌词还在取：这一帧什么都不画
            alpha = 0f
            return
        }

        val platform = DangoClient.platform
        val uiScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val lw = platform.windowWidth / uiScale
        val lh = platform.windowHeight / uiScale
        val mx = platform.mouseX / uiScale
        val my = platform.mouseY / uiScale

        updateColors()
        val size = fontSize.value
        val maxW = lw * 0.94f
        val vert = vertical.get()
        val split = splitRows.get() && !vert
        // 超长换行只在横向、不分割时生效（竖直显示本身就是一行一个字）
        val wrapOn = wrap.get() && !vert && !split

        // 这一帧要画的文字（当前句 + 下一句同排时各自限制宽度，避免总宽超出屏幕）
        val nxtShown = if (nxt != null && !vert && !split) {
            trimmed(cNxt, nxt, size, if (wrapOn) maxW else if (cur != null) maxW * 0.34f else maxW)
        } else {
            null
        }
        // 换行时不预先截断整句，按宽度折成多行
        val curShown = if (cur != null && !wrapOn) {
            trimmed(cCur, cur, size, if (nxtShown != null) maxW * 0.6f else maxW)
        } else {
            null
        }
        val wrapBox = if (wrapOn && cur != null) wrapLayout(cur, size, maxW) else null
        val vertNext = if (vert && cur == null && nxt != null) trimmed(cNxt, nxt, size, maxW) else null
        val titleShown = if (cur == null && nxt == null && fallback != null) trimmed(cTitle, fallback, size, maxW) else null

        if (cur != null && (curShown != null || wrapBox != null)) {
            // 换行时用整句（未截断）算逐字进度，这样每一行的染色都能对上
            kara.prepare(v.line, v.lineEndMs, v.posMs, if (wrapBox != null) cur else curShown ?: "", size, Theme.FONT_MEDIUM)
        }

        val rowH = size * 1.4f
        val rowGap = size * 0.3f

        // 竖直显示：一个字一行（太长就截断）
        val vertText = if (vert) {
            val src = curShown ?: vertNext
            if (src != null) {
                val lineH = size * 1.18f
                val maxChars = (((lh - 48f) / lineH).toInt()).coerceAtLeast(1)
                if (src !== cVert.src || size != cVert.size || maxChars.toFloat() != cVert.maxW) {
                    cVert.src = src
                    cVert.size = size
                    cVert.maxW = maxChars.toFloat()
                    cVert.shown = if (src.length <= maxChars) src else src.substring(0, maxChars - 1) + "…"
                    cVert.width = 0f
                }
                cVert.shown
            } else null
        } else null

        // 内容块尺寸
        val contentW: Float
        val contentH: Float
        when {
            vertText != null -> {
                val lineH = size * 1.18f
                var w = size * 0.75f
                for (ch in vertText) w = max(w, width(ch.toString(), size, Theme.FONT_MEDIUM))
                contentW = w
                contentH = vertText.length * lineH
            }
            wrapBox != null -> {
                val rows = wrapBox.lines.size
                contentW = if (nxtShown != null) max(wrapBox.width, cNxt.width) else wrapBox.width
                contentH = rows * rowH + (rows - 1) * rowGap +
                    (if (nxtShown != null) rowGap + rowH else 0f)
            }
            split && curShown != null -> {
                contentW = max(kara.prefixWidth, kara.suffixWidth)
                contentH = rowH * 2f + rowGap
            }
            else -> {
                val gap = if (curShown != null && nxtShown != null) 14f else 0f
                contentW = (if (curShown != null) kara.width else 0f) +
                    (if (nxtShown != null) gap + cNxt.width else 0f) +
                    (if (titleShown != null) cTitle.width else 0f)
                contentH = rowH
            }
        }
        if (contentW <= 0f || contentH <= 0f) return

        var x = posX.value * (lw - contentW)
        var y = posY.value * (lh - contentH)

        // 打开聊天栏后可以拖动（把玻璃底板也算作拖动区域）
        val padX = 12f
        val padY = 7f
        val hit = LayoutBounds(x - padX, y - padY, contentW + padX * 2f, contentH + padY * 2f)
        if (!platform.chatOpen) {
            dragging = false
        } else {
            val down = platform.isMouseDown(0)
            if (down) {
                if (!dragging && hit.contains(mx.toInt(), my.toInt())) {
                    dragging = true
                    dragDX = mx - x
                    dragDY = my - y
                }
                if (dragging) {
                    x = (mx - dragDX).coerceIn(0f, (lw - contentW).coerceAtLeast(0f))
                    y = (my - dragDY).coerceIn(0f, (lh - contentH).coerceAtLeast(0f))
                    posX.setValue(if (lw > contentW) x / (lw - contentW) else 0f)
                    posY.setValue(if (lh > contentH) y / (lh - contentH) else 0f)
                }
            } else {
                dragging = false
            }
        }

        // 换句：开着"淡化平滑"就淡入，关掉就直接出现
        val key = cur ?: nxt ?: fallback
        if (key != lastKey) {
            lastKey = key
            alpha = if (fade.get()) 0f else 1f
        }
        alpha = if (fade.get()) (alpha + dt / 0.16f).coerceAtMost(1f) else 1f
        if (alpha <= 0.01f) return

        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), mx.toInt(), my.toInt())
        try {
            ctx.pushScale(uiScale, uiScale, 1f)
            ctx.pushOpacity(alpha)
            if (glass.get()) {
                val panel = LayoutBounds(x - padX, y - padY, contentW + padX * 2f, contentH + padY * 2f)
                ctx.drawGlass(panel, Theme.GLASS_WINDOW.copy(
                    radius = min(panel.height / 2f, 18f),
                    shadow = ShadowStyle(Color(0f, 0f, 0f, 0.14f), 8f, Vec2(0f, -2f)),
                ))
            }

            mt.clear()
            when {
                vertText != null -> {
                    val lineH = size * 1.18f
                    val chars = vertText
                    val sung = if (curShown != null) kara.sung.coerceAtMost(chars.length) else 0
                    var yy = y
                    for (i in chars.indices) {
                        val ch = chars[i].toString()
                        val w = width(ch, size, Theme.FONT_MEDIUM)
                        text(ctx, ch, x + (contentW - w) / 2f, yy + (lineH - size * 0.73f) / 2f, size,
                            if (i < sung) colorMain else colorNext)
                        yy += lineH
                    }
                }
                wrapBox != null -> {
                    // 超长换行：每一行都按已唱字数分两段染色，下一句放在最下面
                    wrapSplits(wrapBox, kara.prefix.length)
                    var yy = y
                    for (k in wrapBox.lines.indices) {
                        val lx = x + (contentW - wrapBox.widths[k]) / 2f
                        val ty = yy + (rowH - size * 0.73f) / 2f
                        if (wrapBox.pre[k].isNotEmpty()) text(ctx, wrapBox.pre[k], lx, ty, size, colorMain)
                        if (wrapBox.suf[k].isNotEmpty()) text(ctx, wrapBox.suf[k], lx + wrapBox.preW[k], ty, size, colorNext)
                        yy += rowH + rowGap
                    }
                    if (nxtShown != null) {
                        text(ctx, nxtShown, x + (contentW - cNxt.width) / 2f,
                            yy + (rowH - size * 0.73f) / 2f, size, colorNext)
                    }
                }
                split && curShown != null -> {
                    val y1 = y
                    val y2 = y + rowH + rowGap
                    val ty = (rowH - size * 0.73f) / 2f
                    val p = kara.prefix
                    val s = kara.suffix
                    if (p.isNotEmpty()) {
                        text(ctx, p, x + (contentW - kara.prefixWidth) / 2f, y1 + ty, size, colorMain)
                    }
                    if (s.isNotEmpty()) {
                        text(ctx, s, x + (contentW - kara.suffixWidth) / 2f, y2 + ty, size, colorNext)
                    }
                }
                else -> {
                    val ty = y + (rowH - size * 0.73f) / 2f
                    var tx = x
                    if (curShown != null) {
                        // 逐字染色：唱过的前缀 + 还没唱到的后缀
                        if (kara.prefix.isNotEmpty()) text(ctx, kara.prefix, tx, ty, size, colorMain)
                        if (kara.suffix.isNotEmpty()) text(ctx, kara.suffix, tx + kara.prefixWidth, ty, size, colorNext)
                        tx += kara.width
                    }
                    if (nxtShown != null) {
                        tx += 14f
                        text(ctx, nxtShown, tx, ty, size, colorNext)
                    }
                    if (titleShown != null) {
                        // 找不到歌词时直接显示歌名
                        text(ctx, titleShown, x, ty, size, Theme.TEXT_SECONDARY)
                    }
                }
            }
            ctx.popOpacity()
            ctx.popTransform()
            mt.flush(uiScale)
        } finally {
            ctx.end()
        }
    }

    // ---------------------------------------------------------------- 文字

    private fun text(ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float, color: Color) =
        mt.text(ctx, s, x, y, size, color, Theme.FONT_MEDIUM)

    private fun width(s: String, size: Float, font: String = Theme.FONT_MEDIUM): Float = mt.width(s, size, font)

    private fun trim(s: String, maxW: Float, size: Float, font: String = Theme.FONT_MEDIUM): String =
        mt.trim(s, maxW, size, font)

    companion object {
        @Volatile
        private var instance: Lyrics2DModule? = null

        fun of(): Lyrics2DModule? = instance
    }
}
