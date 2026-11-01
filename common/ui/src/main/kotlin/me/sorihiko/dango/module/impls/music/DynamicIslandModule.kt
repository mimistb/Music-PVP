package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.WorldRenderEndEvent
import me.sorihiko.dango.font.KaraokeText
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.hud.BedWarsData
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.lyrics.LyricLine
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 灵动岛（iOS 风格的胶囊）：
 *  - 内置播放器在放歌时显示 封面 + 歌名 / 逐字歌词 + 跳动的音浪
 *  - 切歌 / 暂停 / 继续：展开成大卡片（封面、歌名、歌手、进度条）
 *  - 玩家名 / 帧率 / 延迟 / 时间已经拆到单独的"信息胶囊"模块（HudInfo）
 * 尺寸用弹簧动画过渡，内容淡入淡出；背景可选液态玻璃或纯黑。
 */
@ModuleMeta(Category.MUSIC, "DynamicIsland")
class DynamicIslandModule : Module() {

    @ModuleValue("液态玻璃", "液态玻璃背景；关闭后为纯黑胶囊")
    private val glass = BooleanValue(true)

    @ModuleValue("音乐", "显示内置播放器正在播放的内容")
    private val showMusic = BooleanValue(true)

    @ModuleValue("歌曲通知", "切歌或暂停时展开显示歌曲信息")
    private val songNotify = BooleanValue(true)

    @ModuleValue("歌词", "在灵动岛里显示当前歌词（开启后会自动关闭 3D 歌词）")
    private val lyrics = BooleanValue(false)

    @ModuleValue("提前显示", "在灵动岛里提前显示还没唱到的下一句歌词")
    private val lookahead = BooleanValue(true)

    @ModuleValue("未唱颜色", "还没唱到的歌词颜色：金 / 红 / 粉 / 白")
    private val nextColor = IntValue(0, 3, 3)

    @ModuleValue("已唱颜色", "正在唱的歌词颜色：金 / 红 / 粉 / 白")
    private val sungColor = IntValue(0, 3, 0)

    @ModuleValue("歌词字号", "灵动岛歌词文字大小")
    private val lyricSize = FloatValue(9f, 20f, 12f, 0)

    @ModuleValue("缩放", "灵动岛大小")
    private val scale = FloatValue(0.6f, 1.6f, 1f, 2)

    @ModuleValue("通知时长", "歌曲通知停留时间（秒）")
    private val notifyTime = FloatValue(1f, 8f, 3.5f, 1)

    /** 歌词颜色：金 / 红 / 粉 / 白（和设置页的色块顺序一致） */
    private val lyricColors = LyricPalette.all

    private enum class Kind { IDLE, SONG }

    private var context: GuiCanvas? = null

    // 当前显示的内容 / 临时内容
    private var shown = Kind.IDLE
    private var temp: Kind? = null
    private var tempUntil = 0L
    private var contentAlpha = 0f

    // 弹簧动画（单位：逻辑像素）
    private var w = 0f
    private var h = 0f
    private var vw = 0f
    private var vh = 0f
    private var lastNanos = 0L
    private var enabledAt = 0L

    private var lastTrackId = 0L
    private var lastState: MusicPlayer.State? = null

    /** 本次渲染要显示的歌词（灵动岛歌词打开、正在放歌时才有） */
    private var curCurrent: String? = null
    private var curNext: String? = null
    /** 没有歌词可显示时（2D / 3D 歌词接管、或这首没找到歌词）显示歌名，避免胶囊空着 */
    private var curTitle: String? = null

    /** 逐字歌词进度（卡拉 OK） */
    private val kara = KaraokeText()
    private var curLine: LyricLine? = null
    private var curLineEndMs = 0L
    private var curPosMs = 0L

    /** 换句时的淡入进度（0→1），让切到下一句时平滑出现 */
    private var lineAlpha = 1f
    private var lastLineText: String? = null

    /** 打开聊天栏后可以直接拖动灵动岛，位置写进组件位置模块 */
    private var dragging = false
    private var dragDX = 0f
    private var dragDY = 0f

    private val mt = MixedText()

    override fun init() {
        instance = this
    }

    override fun onEnable() {
        enabledAt = System.currentTimeMillis()
        w = 0f; h = 0f; vw = 0f; vh = 0f
        shown = Kind.IDLE
        temp = null
        contentAlpha = 0f
        lastNanos = 0L
        lastTrackId = MusicPlayer.current?.id ?: 0L
        lastState = MusicPlayer.state
    }

    private fun show(kind: Kind, ms: Long) {
        temp = kind
        tempUntil = System.currentTimeMillis() + ms
    }

    private fun watchMusic() {
        val cur = MusicPlayer.current
        val st = MusicPlayer.state
        if (cur == null) {
            lastTrackId = 0L
        } else if (cur.id != lastTrackId) {
            lastTrackId = cur.id
            if (songNotify.get()) show(Kind.SONG, (notifyTime.value * 1000).toLong())
        } else if (lastState != null && st != lastState && songNotify.get() &&
            (st == MusicPlayer.State.PAUSED || (st == MusicPlayer.State.PLAYING && lastState == MusicPlayer.State.PAUSED))
        ) {
            if (temp != Kind.SONG || tempUntil - System.currentTimeMillis() < 1500L) show(Kind.SONG, 1500L)
        }
        lastState = st
    }

    private fun desired(): Kind {
        val t = temp
        if (t != null && System.currentTimeMillis() < tempUntil) {
            val ok = when (t) {
                Kind.SONG -> MusicPlayer.current != null
                Kind.IDLE -> true
            }
            if (ok) return t
        }
        temp = null
        return Kind.IDLE
    }

    private fun musicIdle() = showMusic.get() && MusicPlayer.current != null && MusicPlayer.active

    /** 灵动岛歌词的可用宽度（逻辑像素） */
    private val LYRIC_W = 200f

    /** 歌词开关本身（不管模块开没开），互斥逻辑读它 */
    private val islandLyricsSetting: Boolean get() = lyrics.get()

    /** 与 3D 歌词互斥时由歌词模块调用 */
    fun setIslandLyrics(on: Boolean) = lyrics.set(on)

    /** 正在显示灵动岛歌词 */
    val lyricsEnabled: Boolean get() = enabled && islandLyricsSetting

    /** 歌词（当前句 / 下一句）的排版缓存：同一句、同一字号每帧直接复用，不再反复量宽度 */
    private class LineCache {
        var src: String? = null
        var size = 0f
        var shown = ""
        var width = 0f
    }

    private val line1 = LineCache()
    private val line2 = LineCache()
    private val lineTitle = LineCache()
    private val lineBed = LineCache()

    private fun lineShown(c: LineCache, line: String, size: Float): String {
        if (line === c.src && size == c.size) return c.shown
        val shown = trim(line, LYRIC_W, size, Theme.FONT_MEDIUM)
        c.src = line
        c.size = size
        c.shown = shown
        c.width = width(shown, size, Theme.FONT_MEDIUM).coerceIn(56f, LYRIC_W)
        return shown
    }

    private fun lineWidth(c: LineCache, line: String, size: Float): Float {
        lineShown(c, line, size)
        return c.width
    }

    /** 找歌词模块要当前 / 下一句；歌词模块没注册、没歌词时返回空内容 */
    private fun lyricView(): LyricView = LyricsModule.of()?.lyricView(lookahead.get()) ?: EMPTY_VIEW

    companion object {
        /** 歌词模块不可用时的空内容（复用，避免每帧分配） */
        private val EMPTY_VIEW = LyricView()

        /** 床威胁行最大宽度（逻辑像素） */
        private const val BED_ROW_W = 138f

        /** 床威胁行高度（含间距） */
        private const val BED_ROW_H = 14f

        /** 原版 § 队伍色（从 BedWarsData 的色表派生，不新增自定义配色） */
        private val TEAM_COLORS = Array(8) { i ->
            val rgb = BedWarsData.teamRgb(i)
            Color(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f, 1f)
        }

        /** 歌词模块的反向互斥 / 本模块被其它模块引用时用（避免每帧查 ModuleManager） */
        @Volatile
        private var instance: DynamicIslandModule? = null

        fun of(): DynamicIslandModule? = instance
    }

    /** 床提醒行：比赛已确认且平台层拼好了显示文本 */
    private fun bedRowVisible(): Boolean = BedWarsData.matchActive && BedWarsData.bedLine.isNotEmpty()

    /** 床行按最大宽度裁剪（同一根字符串复用缓存） */
    private fun bedShown(line: String, size: Float): String {
        if (line === lineBed.src && size == lineBed.size) return lineBed.shown
        val shown = trim(line, BED_ROW_W, size, Theme.FONT_MEDIUM)
        lineBed.src = line
        lineBed.size = size
        lineBed.shown = shown
        lineBed.width = width(shown, size, Theme.FONT_MEDIUM).coerceAtLeast(24f)
        return shown
    }

    // ---------------------------------------------------------------- 尺寸（逻辑像素）

    private fun sizeOf(kind: Kind): Pair<Float, Float> = when (kind) {
        Kind.SONG -> {
            val t = MusicPlayer.current
            val tw = if (t == null) 0f else max(
                width(t.name, 14f, Theme.FONT_SEMIBOLD).coerceAtMost(260f),
                width(t.artists.joinToString(" / "), 11f).coerceAtMost(260f),
            )
            (76f + max(tw, 150f) + 44f) to 76f
        }
        Kind.IDLE -> {
            val bed = bedRowVisible()
            // 床行宽度（最多 BED_ROW_W，左右各留 12）
            val bedW = if (bed) width(bedShown(BedWarsData.bedLine, 9f), 9f, Theme.FONT_MEDIUM) + 24f else 0f
            if (musicIdle()) {
                val size = lyricSize.value
                val cur = curCurrent
                val nxt = curNext
                val title = curTitle
                // 歌词按当前这句（以及提前显示的那句）的长度走，没有歌词时按歌名长度走
                val textW = when {
                    cur != null && nxt != null -> max(kara.width, lineWidth(line2, nxt, size))
                    cur != null -> kara.width
                    nxt != null -> lineWidth(line2, nxt, size)
                    title != null -> lineWidth(lineTitle, title, size)
                    else -> 0f
                }.coerceAtLeast(56f)
                // 同时显示"已唱 + 未唱"两行时需要更高一点；有床行时再给一行高度
                val hh = (if (cur != null && nxt != null) 44f else 32f) + if (bed) BED_ROW_H else 0f
                max(9f + 22f + 9f + textW + 12f + 14f + 12f, bedW) to hh
            } else if (bed) {
                // 没在放歌但比赛激活：只显示一条床状态胶囊
                bedW to 30f
            } else {
                // 没在放歌：灵动岛不显示任何信息（玩家名 / 帧率 / 延迟 / 时间已经拆到信息胶囊模块）
                0f to 0f
            }
        }
    }

    // ---------------------------------------------------------------- 渲染

    @EventHandler
    fun render(e: WorldRenderEndEvent) {
        if (!enabled) return
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now

        watchMusic()
        curCurrent = null
        curNext = null
        curTitle = null
        curLine = null
        curLineEndMs = 0L
        curPosMs = 0L
        if (lyricsEnabled && musicIdle()) {
            val v = lyricView()
            curCurrent = v.current
            curNext = v.next
            curLine = v.line
            curLineEndMs = v.lineEndMs
            curPosMs = v.posMs
            // 有歌词就显示歌词；没有（或这首确实没找到歌词）就退回显示歌名
            if (curCurrent == null && curNext == null) curTitle = v.fallback ?: MusicPlayer.current?.name
        } else if (musicIdle()) {
            // 灵动岛歌词关掉了（2D / 3D 歌词接管时也会自动关）：显示歌名，不要空着
            curTitle = MusicPlayer.current?.name
        }
        if (curCurrent != null) {
            val size = lyricSize.value
            kara.prepare(curLine, curLineEndMs, curPosMs, lineShown(line1, curCurrent!!, size), size, Theme.FONT_MEDIUM)
        } else {
            kara.reset()
        }
        // 换句：短淡入 + 轻微上移，切到下一句时是平滑的
        if (curCurrent != lastLineText) {
            lastLineText = curCurrent
            lineAlpha = if (curCurrent == null) 1f else 0f
        }
        lineAlpha = min(1f, lineAlpha + dt / 0.16f)

        val want = desired()
        if (want == Kind.IDLE && !musicIdle() && !bedRowVisible()) {
            // 没在放歌、也没有床提醒：灵动岛不再显示信息（信息已经拆到信息胶囊模块）
            return
        }

        // 内容切换：先淡出旧内容，再换成新内容淡入
        if (shown != want) {
            contentAlpha -= dt / 0.09f
            if (contentAlpha <= 0f) {
                contentAlpha = 0f
                shown = want
            }
        } else {
            contentAlpha = min(1f, contentAlpha + dt / 0.16f)
        }

        // 弹簧：略带回弹，接近 iOS 灵动岛的手感
        val (tw, th) = sizeOf(want)
        val stiff = 230f
        val damp = 24f
        vw += ((tw - w) * stiff - vw * damp) * dt
        vh += ((th - h) * stiff - vh * damp) * dt
        w += vw * dt
        h += vh * dt
        if (w < 1f || h < 1f) return

        val platform = DangoClient.platform
        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), 0, 0)
        try {
            val uiScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
            val k = uiScale * scale.value
            val lw = platform.windowWidth / k
            val sf = platform.guiScale
            val top = 18f * sf / k                       // 和原来一样，留出 Boss 血条的位置
            val baseX = (lw - w) / 2f
            val layout = HudLayoutModule.of()
            var pill = LayoutBounds(
                baseX + (layout?.islandX?.value ?: 0f),
                top + (layout?.islandY?.value ?: 0f),
                w, h,
            )
            // 打开聊天栏后可以直接拖动灵动岛（位置写进组件位置模块）
            if (layout != null) {
                if (!platform.chatOpen) {
                    dragging = false
                } else {
                    val mx = platform.mouseX / k
                    val my = platform.mouseY / k
                    if (platform.isMouseDown(0)) {
                        if (!dragging && pill.contains(mx.toInt(), my.toInt())) {
                            dragging = true
                            dragDX = mx - pill.x
                            dragDY = my - pill.y
                        }
                        if (dragging) {
                            layout.islandX.setValue(mx - dragDX - baseX)
                            layout.islandY.setValue(my - dragDY - top)
                            pill = LayoutBounds(
                                baseX + layout.islandX.value,
                                top + layout.islandY.value,
                                w, h,
                            )
                        }
                    } else {
                        dragging = false
                    }
                }
            }
            val r = min(h / 2f, 22f)

            ctx.pushScale(k, k, 1f)
            if (glass.get()) {
                ctx.drawGlass(pill, Theme.GLASS_WINDOW.copy(radius = r, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.14f), 8f, Vec2(0f, -2f))))
            } else {
                ctx.drawShape(
                    pill,
                    ShapeStyle(
                        Color(0.02f, 0.02f, 0.03f, 0.9f), r, 1f, Color(1f, 1f, 1f, 0.07f),
                        ShadowStyle(Color(0f, 0f, 0f, 0.18f), 8f, Vec2(0f, -2f)),
                    )
                )
            }

            // 内容按目标尺寸排版，在当前胶囊里居中，超出部分裁掉
            val cw: Float
            val ch: Float
            if (shown == want) {
                cw = tw
                ch = th
            } else {
                val s = sizeOf(shown)
                cw = s.first
                ch = s.second
            }
            val cb = LayoutBounds(pill.x + (w - cw) / 2f, pill.y + (h - ch) / 2f, cw, ch)
            val scX = (pill.x * k).toInt()
            val scY = (platform.windowHeight - (pill.y + h) * k).toInt()
            ctx.scissor(scX, scY, (w * k).toInt() + 1, (h * k).toInt() + 1)

            mt.clear()
            mt.clip = pill
            ctx.pushOpacity(contentAlpha)
            when (shown) {
                Kind.IDLE -> {
                    if (cb.width > 1f && cb.height > 1f) {
                        val bed = bedRowVisible()
                        // 有床行时音乐内容只占上半部分，床行贴在最下面
                        if (musicIdle()) {
                            drawMusicIdle(ctx, if (bed) LayoutBounds(cb.x, cb.y, cb.width, cb.height - BED_ROW_H) else cb)
                        }
                        if (bed) drawBedRow(ctx, cb)
                    }
                }
                Kind.SONG -> drawSong(ctx, cb)
            }
            ctx.popOpacity()
            ctx.popTransform()
            mt.flush(k)
            ctx.noScissor()
        } finally {
            ctx.end()
        }
    }

    /** 床提醒行：床威胁 / 自己床状态，队伍色用原版 § 色表，警告用主字色 */
    private fun drawBedRow(ctx: GuiCanvas, b: LayoutBounds) {
        val line = BedWarsData.bedLine
        if (line.isEmpty()) return
        val color = when {
            BedWarsData.bedLineWarn -> Theme.TEXT_PRIMARY
            BedWarsData.bedLineTeam >= 0 -> TEAM_COLORS[BedWarsData.bedLineTeam.coerceIn(0, TEAM_COLORS.size - 1)]
            else -> Theme.TEXT_SECONDARY
        }
        val size = 9f
        // 只有床行（没在放歌）的胶囊很矮，整行垂直居中；有音乐时贴在最下面一行
        val y = if (b.height <= 32f) b.y + (b.height - size * 0.73f) / 2f
        else b.bottom - BED_ROW_H + (BED_ROW_H - size * 0.73f) / 2f
        text(ctx, bedShown(line, size), b.x + 12f, y, size, color, Theme.FONT_MEDIUM)
    }

    private fun drawMusicIdle(ctx: GuiCanvas, b: LayoutBounds) {
        val t = MusicPlayer.current ?: return
        // 胶囊两头是半圆：封面往里挪一点、圆角跟着胶囊的弧度走，看起来才居中
        val cover = LayoutBounds(b.x + 9f, b.y + (b.height - 22f) / 2f, 22f, 22f)
        drawCover(ctx, cover, t.let { CoverCache.trackRounded(it, 0.36f) ?: CoverCache.track(it) })
        val playing = MusicPlayer.state == MusicPlayer.State.PLAYING
        val tx = cover.right + 9f
        val size = lyricSize.value
        val cur = curCurrent
        val nxt = curNext
        if (cur != null) {
            // 逐字染色：唱过的字用"已唱颜色"，还没唱到的字用"未唱颜色"
            ctx.pushOpacity(lineAlpha)
            val slide = (1f - lineAlpha) * 3f
            if (nxt != null) {
                text(ctx, lineShown(line2, nxt, size), tx, b.y + 32f - size * 0.39f + slide, size,
                    lyricColors[nextColor.value], Theme.FONT_MEDIUM)
                drawKaraoke(ctx, tx, b.y + 14f - size * 0.39f + slide, size)
            } else {
                drawKaraoke(ctx, tx, b.y + 16f - size * 0.39f + slide, size)
            }
            ctx.popOpacity()
        } else if (nxt != null) {
            // 前奏：还没唱到第一句时先显示它（整句用未唱颜色）
            val slide = (1f - lineAlpha) * 3f
            text(ctx, lineShown(line2, nxt, size), tx, b.y + 16f - size * 0.39f + slide, size,
                lyricColors[nextColor.value], Theme.FONT_MEDIUM)
        } else {
            // 没有歌词可显示（2D / 3D 歌词接管，或这首没找到歌词）：显示歌名，别让胶囊空着
            val title = curTitle
            if (title != null) {
                text(ctx, lineShown(lineTitle, title, size), tx, b.y + 16f - size * 0.39f, size,
                    Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
            }
        }
        bars(ctx, b.right - 12f - 14f, b.y + b.height - 10f, playing, 11f)
    }

    /** 逐字画当前句：已唱前缀 + 未唱后缀 */
    private fun drawKaraoke(ctx: GuiCanvas, x: Float, y: Float, size: Float) {
        val sung = lyricColors[sungColor.value]
        val unsung = lyricColors[nextColor.value]
        if (kara.prefix.isNotEmpty()) text(ctx, kara.prefix, x, y, size, sung, Theme.FONT_MEDIUM)
        if (kara.suffix.isNotEmpty()) text(ctx, kara.suffix, x + kara.prefixWidth, y, size, unsung, Theme.FONT_MEDIUM)
    }

    private fun drawSong(ctx: GuiCanvas, b: LayoutBounds) {
        val t = MusicPlayer.current ?: return
        val cover = LayoutBounds(b.x + 10f, b.y + 10f, 56f, 56f)
        drawCover(ctx, cover, CoverCache.trackRounded(t) ?: CoverCache.track(t))
        val playing = MusicPlayer.state == MusicPlayer.State.PLAYING
        val tx = cover.right + 10f
        val tw = b.right - 14f - tx

        val label = when (MusicPlayer.state) {
            MusicPlayer.State.PAUSED -> "已暂停"
            MusicPlayer.State.LOADING -> "加载中"
            else -> "正在播放"
        }
        text(ctx, label, tx, b.y + 11f, 9f, if (playing) Theme.ACCENT else Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        bars(ctx, b.right - 14f - 14f, b.y + 21f, playing, 10f)
        text(ctx, trim(t.name, tw - 4f, 14f, Theme.FONT_SEMIBOLD), tx, b.y + 24f, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        text(ctx, trim(t.artists.joinToString(" / "), tw, 11f), tx, b.y + 42f, 11f, Theme.TEXT_SECONDARY)

        val bar = LayoutBounds(tx, b.y + 60f, tw, 3f)
        ctx.drawShape(bar, ShapeStyle(Theme.TRACK, 1.5f))
        val dur = MusicPlayer.durationMs
        if (dur > 0) {
            val f = (MusicPlayer.positionMs.toDouble() / dur).coerceIn(0.0, 1.0).toFloat()
            ctx.drawShape(LayoutBounds(bar.x, bar.y, max(3f, bar.width * f), bar.height), ShapeStyle(Theme.TEXT_PRIMARY, 1.5f))
        }
    }

    private fun drawCover(ctx: GuiCanvas, b: LayoutBounds, tex: Texture?) {
        CoverCache.upload()
        if (tex != null) ctx.drawImage(b, tex)
        else ctx.drawShape(b, ShapeStyle(Theme.TRACK, b.width * 0.2f))
    }

    /** 跳动的音浪：3 根小柱子，底部对齐 yBottom */
    private fun bars(ctx: GuiCanvas, x: Float, yBottom: Float, playing: Boolean, maxH: Float) {
        val time = System.nanoTime() / 1e9
        for (i in 0 until 4) {
            val hh = if (playing)
                (3f + (maxH - 3f) * (0.5f + 0.5f * sin(time * (5.5 + i * 1.9) + i * 1.3).toFloat()))
            else 3f
            ctx.drawShape(
                LayoutBounds(x + i * 3.6f, yBottom - hh, 2.2f, hh),
                ShapeStyle(if (playing) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY, 1.1f),
            )
        }
    }

    // ---------------------------------------------------------------- 文字（拉丁用菜单字体，中文用 MC 字体），y 是文字顶部

    private fun text(ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) =
        mt.text(ctx, s, x, y, size, color, font)

    private fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float = mt.width(s, size, font)

    private fun trim(s: String, maxW: Float, size: Float, font: String = Theme.FONT_REGULAR): String = mt.trim(s, maxW, size, font)
}
