package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.WorldRenderEndEvent
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 歌曲卡片 HUD：内置播放器放歌时，在游戏画面里显示一张液态玻璃卡片
 * （封面 / 歌名 / 歌手 / 进度条）。打开聊天栏时可以用鼠标拖动，位置会保存。
 */
@ModuleMeta(Category.MUSIC, "SongCard")
class SongCardModule : Module() {

    @ModuleValue("缩放", "卡片大小")
    private val scale = FloatValue(0.6f, 2f, 1f, 1)

    @ModuleValue("暂停时显示", "暂停时保持卡片可见")
    private val showPaused = BooleanValue(false)

    @ModuleValue("X 坐标", "水平位置（打开聊天栏后可拖动）")
    private val posX = FloatValue(0f, 1f, 0.01f, 3)

    @ModuleValue("Y 坐标", "垂直位置（打开聊天栏后可拖动）")
    private val posY = FloatValue(0f, 1f, 0.05f, 3)

    @ModuleValue("进度条宽度", "进度条占可用宽度的比例")
    private val barWidth = FloatValue(0.3f, 1f, 1f, 2)

    @ModuleValue("进度条高度", "进度条粗细")
    private val barHeight = FloatValue(2f, 10f, 5f, 1)

    @ModuleValue("进度条偏移", "整条进度行上下移动")
    private val barOffset = FloatValue(-14f, 14f, 0f, 1)

    private var context: GuiCanvas? = null

    private var dragging = false
    private var dragDX = 0f
    private var dragDY = 0f
    private var appear = 0f
    private var lastNanos = 0L

    /** 上一帧画出来的进度条（逻辑坐标）：点击 / 拖动它就能调播放进度 */
    private var barRect: LayoutBounds? = null
    private var seeking = false
    private var lastSeekAtMs = 0L
    // 初值取一个很靠前的值：第一次点到进度条最左边（0）也要能 seek
    private var lastSeekMs = -1_000_000L

    /** 卡片正在显示（Lyrics 模块据此隐藏它自己的左上角信息） */
    val showing: Boolean get() = enabled && appear > 0.01f

    private val mt = MixedText()

    override fun init() {
        instance = this
    }

    @EventHandler
    fun render(e: WorldRenderEndEvent) {
        if (!enabled) return
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 16f else ((now - lastNanos) / 1e6f).coerceAtMost(50f)
        lastNanos = now

        val t = MusicPlayer.current
        val visible = t != null && MusicPlayer.active &&
            (MusicPlayer.state != MusicPlayer.State.PAUSED || showPaused.get())
        appear += ((if (visible) 1f else 0f) - appear) * (1f - kotlin.math.exp(-dt / 120f))
        if (appear < 0.01f || t == null) {
            barRect = null
            seeking = false
            dragging = false
            return
        }

        val platform = DangoClient.platform
        val uiScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val lw = platform.windowWidth / uiScale
        val lh = platform.windowHeight / uiScale
        val mx = platform.mouseX / uiScale
        val my = platform.mouseY / uiScale

        val w = cardW * scale.value
        val h = cardH * scale.value
        // 组件位置模块的微调叠加在卡片自己的坐标上（拖动只改卡片坐标，不会互相干扰）
        val layout = HudLayoutModule.of()
        val ox = layout?.cardX?.value ?: 0f
        val oy = layout?.cardY?.value ?: 0f
        var x = posX.value * (lw - w) + ox
        var y = posY.value * (lh - h) + oy

        // 打开聊天栏时可以拖动；底部进度行单独用来调播放进度（不拖动卡片）
        val chatOpen = platform.chatOpen
        val down = chatOpen && platform.isMouseDown(0)
        if (!down) {
            dragging = false
            seeking = false
        } else {
            val bar = barRect
            val inBar = bar != null && MusicPlayer.durationMs > 0 &&
                mx >= bar.x - 4f && mx <= bar.right + 4f &&
                my >= bar.y - 6f && my <= bar.bottom + 6f
            if (!seeking && inBar) seeking = true
            if (seeking && bar != null) {
                // 拖动进度条：按鼠标在条上的比例 seek（节流，避免每帧重建解码线程）
                dragging = false
                val f = ((mx - bar.x) / bar.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
                val wantMs = (MusicPlayer.durationMs * f).toLong()
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastSeekAtMs >= 180 && abs(wantMs - lastSeekMs) >= 250) {
                    MusicPlayer.seek(wantMs)
                    lastSeekAtMs = nowMs
                    lastSeekMs = wantMs
                }
            } else {
                if (!dragging && mx in x..(x + w) && my in y..(y + h) && !progressHit(x, y, w, h).contains(mx.toInt(), my.toInt())) {
                    dragging = true
                    dragDX = mx - x
                    dragDY = my - y
                }
                if (dragging) {
                    x = (mx - dragDX).coerceIn(0f, lw - w)
                    y = (my - dragDY).coerceIn(0f, lh - h)
                    posX.setValue(if (lw > w) (x - ox) / (lw - w) else 0f)
                    posY.setValue(if (lh > h) (y - oy) / (lh - h) else 0f)
                }
            }
        }

        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(true, mx.toInt(), my.toInt())
        try {
            ctx.pushScale(uiScale, uiScale, 1f)
            ctx.pushOpacity(appear)
            mt.clear()
            drawCard(ctx, LayoutBounds(x, y + (1f - appear) * 10f, w, h), chatOpen)
            ctx.popOpacity()
            ctx.popTransform()
            mt.flush(uiScale)
        } finally {
            ctx.end()
        }
    }

    /** 卡片尺寸（逻辑像素，未乘 scale） */
    private val cardW = 320f
    private val cardH = 104f

    /** 底部进度行区域（逻辑坐标）：这一块用来调进度条，不参与拖动 */
    private fun progressHit(x: Float, y: Float, w: Float, h: Float): LayoutBounds {
        val k = scale.value
        val pad = 14f * k
        val extra = abs(barOffset.value) * k
        return LayoutBounds(x + pad * 0.5f, y + h - pad - 13f * k - extra, w - pad, 13f * k + pad + extra)
    }

    /** 卡片用的玻璃：和窗口同材质，但阴影小很多（HUD 上大阴影会显得脏） */
    private fun cardGlass(k: Float) = Theme.GLASS_WINDOW.copy(
        radius = 24f * k,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.14f), 10f * k, Vec2(0f, -3f * k)),
    )

    /**
     * 排版参考 iOS 锁屏的播放卡片（不要下面那排按钮）：
     *   封面 | 歌名 / 歌手            ▂▅▇▅▂
     *   0:14 ━━━━━━━━━━━━━━━━━━ -6:18
     */
    private fun drawCard(ctx: GuiCanvas, b: LayoutBounds, chatOpen: Boolean) {
        val t = MusicPlayer.current ?: return
        val k = scale.value
        ctx.drawGlass(b, cardGlass(k))
        if (chatOpen) ctx.drawShape(b, ShapeStyle(Color(0f, 0f, 0f, 0f), 24f * k, 1f, Theme.ACCENT))

        val pad = 14f * k
        val cs = 50f * k
        val cover = LayoutBounds(b.x + pad, b.y + pad, cs, cs)
        val loc = CoverCache.trackRounded(t) ?: CoverCache.track(t)
        CoverCache.upload()
        if (loc != null) ctx.drawImage(cover, loc) else ctx.drawShape(cover, ShapeStyle(Theme.TRACK, 9f * k))

        // 右上角的声波（播放中跳动，暂停时变平）
        val playing = MusicPlayer.state == MusicPlayer.State.PLAYING
        val now = System.nanoTime() / 1e9
        val bars = 5
        val bw = 2.6f * k
        val gap = 2.4f * k
        val waveRight = b.right - pad
        val waveCy = cover.y + 12f * k
        for (i in 0 until bars) {
            val base = floatArrayOf(0.45f, 0.75f, 1f, 0.7f, 0.5f)[i]
            val hh = if (playing)
                (4f + 11f * base * (0.45f + 0.55f * (0.5f + 0.5f * sin(now * (5.0 + i * 1.3) + i * 1.9).toFloat()))) * k
            else 3f * k
            ctx.drawShape(
                LayoutBounds(waveRight - (bars - i) * (bw + gap) + gap, waveCy - hh / 2f, bw, hh),
                ShapeStyle(Theme.TEXT_SECONDARY, bw / 2f),
            )
        }

        // 歌名 / 歌手
        val tx = cover.right + 12f * k
        val tw = waveRight - bars * (bw + gap) - 10f * k - tx
        text(ctx, trim(t.name, tw, 15f * k, Theme.FONT_SEMIBOLD), tx, cover.y + 8f * k, 15f * k, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        text(ctx, trim(t.artists.joinToString(" / "), waveRight - tx, 13f * k), tx, cover.y + 28f * k, 13f * k, Theme.TEXT_SECONDARY)

        // 进度：左边已播放，右边剩余（负号），中间进度条（粗细 / 长度 / 上下偏移都可以调）
        val dur = MusicPlayer.durationMs
        val pos = MusicPlayer.positionMs.coerceIn(0L, max(0L, dur))
        val ts = 10.5f * k
        val rowCy = b.bottom - pad - 5f * k + barOffset.value * k
        val left = fmt(pos)
        val right = "-" + fmt(max(0L, dur - pos))
        val lw = width(left, ts, Theme.FONT_MEDIUM)
        val rw = width(right, ts, Theme.FONT_MEDIUM)
        val labelW = max(max(lw, rw), width("0:00", ts, Theme.FONT_MEDIUM))
        val textY = rowCy - ts * 0.39f - 0.5f * k
        text(ctx, left, b.x + pad, textY, ts, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
        text(ctx, right, b.right - pad - rw, textY, ts, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
        val barH = barHeight.value * k
        val barX = b.x + pad + labelW + 9f * k
        val availW = (b.right - pad - labelW - 9f * k - barX).coerceAtLeast(barH)
        val barW = (availW * barWidth.value).coerceIn(barH, availW)
        val bar = LayoutBounds(barX, rowCy - barH / 2f, barW, barH)
        // 记住进度条的实际位置：下一帧用它做点击 / 拖动判定
        barRect = bar
        ctx.drawShape(bar, ShapeStyle(Color(1f, 1f, 1f, 0.18f), barH / 2f))
        if (dur > 0) {
            val f = (pos.toDouble() / dur).coerceIn(0.0, 1.0).toFloat()
            ctx.drawShape(LayoutBounds(bar.x, bar.y, max(barH, bar.width * f), bar.height), ShapeStyle(Color(1f, 1f, 1f, 0.92f), barH / 2f))
        }
    }

    // ---------------------------------------------------------------- 文字（拉丁用菜单字体，中文用 MC 字体）

    private fun fmt(ms: Long): String {
        val s = max(0L, ms / 1000)
        return "${s / 60}:${"%02d".format(s % 60)}"
    }

    private fun text(ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) =
        mt.text(ctx, s, x, y, size, color, font)

    private fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float = mt.width(s, size, font)

    private fun trim(s: String, maxW: Float, size: Float, font: String = Theme.FONT_REGULAR): String = mt.trim(s, maxW, size, font)

    companion object {
        /** 其它模块每帧读 [showing] 用，避免反复从 ModuleManager 里查（会新建列表） */
        @Volatile
        private var instance: SongCardModule? = null

        fun of(): SongCardModule? = instance
    }
}
