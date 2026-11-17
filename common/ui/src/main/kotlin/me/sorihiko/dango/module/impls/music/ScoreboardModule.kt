package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.HudRenderEvent
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.ui.HudSnap
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 计分板（移植自 Vibe 的 HudManager#drawScoreboard）。
 *
 * 取数规则与原实现一致：优先读本地玩家队伍颜色对应的 displaySlot（3 + colorIndex），
 * 没有就退回 displaySlot 1；跳过以 `#` 开头的行；最多 15 行；分数升序排列后倒序绘制
 * （最高分在最上面），分数右对齐并固定用原版红色。
 *
 * 标题可以通过「替换服务器标题」写死成 bipas.gay，并按原实现的三种模式逐字上色：
 * 固定 / 渐变（两色正弦插值）/ 彩虹（HSB 循环）。颜色码（§a 这类）按行解析后逐段上色。
 *
 * 原版侧边栏由 ScoreboardTransformer + ScoreboardHooks 在渲染入口取消，
 * 数据由 legacy 的 ScoreboardRuntime 每 tick 读取并 [publish] 进来。
 */
@ModuleMeta(Category.MUSIC, "Scoreboard")
class ScoreboardModule : Module() {

    @ModuleValue("液态玻璃", "液态玻璃背景；关闭后为半透明深色面板")
    private val glass = BooleanValue(false)

    @ModuleValue("背景透明度", "面板背景的不透明度，0% 完全透明、100% 不透明")
    private val alpha = FloatValue(0f, 1f, 0.85f, 2)

    @ModuleValue("替换服务器标题", "把侧边栏标题替换成 bipas.gay，并按下面的域名配色绘制")
    private val replaceServer = BooleanValue(false)

    @ModuleValue("标题颜色模式", "固定 / 渐变 / 彩虹")
    private val domainMode = IntValue(0, 2, 0)

    @ModuleValue("标题颜色", "固定模式的标题颜色；渐变 / 彩虹模式作为起始色")
    private val domainColor = IntValue(0, 0xFFFFFF, 0xFFFFFF)

    @ModuleValue("渐变颜色", "渐变模式的目标颜色")
    private val domainFadeColor = IntValue(0, 0xFFFFFF, 0xA855F7)

    @ModuleValue("色彩速度", "渐变 / 彩虹的循环速度")
    private val domainSpeed = FloatValue(0.1f, 5f, 1f, 1)

    @ModuleValue("彩虹饱和度", "彩虹模式的颜色饱和度")
    private val domainSaturation = FloatValue(0f, 1f, 0.8f, 2)

    @ModuleValue("文字阴影", "所有文字加一层暗色阴影")
    private val textShadow = BooleanValue(true)

    @ModuleValue("缩放", "面板大小")
    private val scale = FloatValue(0.6f, 2.5f, 1f, 2)

    @ModuleValue("X 坐标", "面板位置（打开聊天栏后可拖动）")
    private val posX = FloatValue(0f, 1f, 0.86f, 3)

    @ModuleValue("Y 坐标", "面板位置（打开聊天栏后可拖动）")
    private val posY = FloatValue(0f, 1f, 0.10f, 3)

    private var context: GuiCanvas? = null
    private val mt = MixedText()

    private var dragging = false
    private var dragDX = 0f
    private var dragDY = 0f

    private val panelGlass = Theme.GLASS_WINDOW.copy(
        radius = 6f,
        fallback = Color(0.40f, 0.42f, 0.46f, 0.82f),
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.18f), 6f, Vec2(0f, -2f)),
    )
    private val panelDark = ShapeStyle(
        Color(0f, 0f, 0f, 0.78f), 6f, 1f, Color(1f, 1f, 1f, 0.06f),
        ShadowStyle(Color(0f, 0f, 0f, 0.16f), 6f, Vec2(0f, -2f)),
    )

    // ---------------------------------------------------------------- 文本缓存
    // 服务器每 tick 都会重建行数组，这里按数组 identity + 标题 + 设置指纹缓存解析结果，
    // 避免每帧把 § 颜色码重新切一遍、也不再每帧测量同样的字符串。

    private class Run(val text: String, val rgb: Int)
    private class Line(val runs: Array<Run>, val width: Float)

    private var cachedRows: Array<ScoreboardLine>? = null
    private var cachedTitle: String? = null
    private var cachedFingerprint = Int.MIN_VALUE
    private var cachedLines: Array<Line> = emptyArray()
    private var cachedTitleRuns: Array<Run> = emptyArray()
    private var cachedTitleWidth = 0f
    private var cachedPointsWidth = 0f
    private var cachedContentWidth = 0f

    override fun init() {
        instance = this
    }

    private fun fingerprint(): Int {
        var f = if (textShadow.get()) 1 else 0
        f = f * 3 + replaceServer.get().let { if (it) 1 else 0 }
        f = f * 17 + (scale.value * 100f).toInt()
        return f
    }

    /** 解析 § 颜色码，返回按颜色切好的文字段（§k-o / §r 等只吃掉不上色） */
    private fun parse(s: String, base: Int): Array<Run> {
        if (s.isEmpty()) return emptyArray()
        var out: ArrayList<Run>? = null
        val sb = StringBuilder(s.length)
        var color = base
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '§' && i + 1 < s.length) {
                val code = s[i + 1].lowercaseChar()
                val rgb = CODE_COLORS[code.code] ?: -1
                if (rgb >= 0) {
                    if (sb.isNotEmpty()) {
                        if (out == null) out = ArrayList(3)
                        out.add(Run(sb.toString(), color))
                        sb.setLength(0)
                    }
                    color = rgb
                } else if (code == 'r') {
                    if (sb.isNotEmpty()) {
                        if (out == null) out = ArrayList(3)
                        out.add(Run(sb.toString(), color))
                        sb.setLength(0)
                    }
                    color = base
                }
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        if (sb.isNotEmpty()) {
            if (out == null) return arrayOf(Run(sb.toString(), color))
            out.add(Run(sb.toString(), color))
        }
        return out?.toTypedArray() ?: emptyArray()
    }

    private fun rebuildCache(title: String?, rows: Array<ScoreboardLine>) {
        cachedRows = rows
        cachedTitle = title
        cachedFingerprint = fingerprint()

        cachedTitleRuns = if (title.isNullOrEmpty()) emptyArray() else parse(title, 0xFFFFFF)
        cachedTitleWidth = runWidth(cachedTitleRuns, TITLE_SIZE)

        val lines = arrayOfNulls<Line>(rows.size)
        var maxLineW = 0f
        var pointsW = 0f
        for (i in rows.indices) {
            val runs = parse(rows[i].name, 0xFFFFFF)
            val w = runWidth(runs, ROW_SIZE)
            lines[i] = Line(runs, w)
            if (w > maxLineW) maxLineW = w
            val pw = width(pointsText(rows[i].points), ROW_SIZE)
            if (pw > pointsW) pointsW = pw
        }
        cachedLines = lines.requireNoNulls()
        cachedPointsWidth = pointsW
        cachedContentWidth = max(cachedTitleWidth, maxLineW + 3f + pointsW)
    }

    private fun runWidth(runs: Array<Run>, size: Float): Float {
        var w = 0f
        for (r in runs) w += width(r.text, size)
        return w
    }

    private fun width(s: String, size: Float): Float = mt.width(s, size, Theme.FONT_REGULAR)

    private fun pointsText(points: Int): String = points.toString()

    private fun rgb(rgb: Int, a: Float): Color =
        Color(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f, a)

    private fun drawRuns(
        ctx: GuiCanvas, runs: Array<Run>, x: Float, y: Float, size: Float, a: Float,
    ) {
        var pen = x
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val color = rgb(run.rgb, a)
            if (textShadow.get()) {
                mt.text(ctx, run.text, pen + 0.8f, y + 0.8f, size, Color(0f, 0f, 0f, a * 0.55f))
            }
            mt.text(ctx, run.text, pen, y, size, color)
            pen += width(run.text, size)
        }
    }

    /** 域名逐字配色（搬运 ScoreboardDomainSettings#colorAt） */
    private fun domainColorAt(timeMs: Long, index: Int): Int {
        val phase = (Math.floorMod(timeMs, 600_000L).toDouble() * domainSpeed.value / 6000.0) + index * 0.065
        return when (domainMode.value) {
            0 -> domainColor.value
            2 -> rainbowRgb(phase, domainSaturation.value)
            else -> mixRgb(domainColor.value, domainFadeColor.value, (0.5 + 0.5 * sin(phase * Math.PI * 2)).toFloat())
        }
    }

    /** 逐字渐变标题：每个字符单独算色，宽度按字符推进 */
    private fun drawDomainTitle(ctx: GuiCanvas, text: String, x: Float, y: Float, size: Float, a: Float) {
        val now = System.currentTimeMillis()
        var pen = x
        for (i in text.indices) {
            val ch = text[i].toString()
            val color = rgb(domainColorAt(now, i), a)
            if (textShadow.get()) mt.text(ctx, ch, pen + 0.8f, y + 0.8f, size, Color(0f, 0f, 0f, a * 0.55f))
            mt.text(ctx, ch, pen, y, size, color)
            pen += width(ch, size)
        }
    }

    @EventHandler
    fun render(e: HudRenderEvent) {
        if (!enabled) return
        val rows = rows
        val rawTitle = if (replaceServer.get()) DOMAIN else title
        if ((rawTitle.isNullOrEmpty()) && rows.isEmpty()) return

        if (rows !== cachedRows || rawTitle != cachedTitle || fingerprint() != cachedFingerprint) {
            rebuildCache(rawTitle, rows)
        }

        val platform = DangoClient.platform
        val k = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f) * scale.value
        val lw = platform.windowWidth / k
        val lh = platform.windowHeight / k
        val mx = platform.mouseX / k
        val my = platform.mouseY / k

        val hasTitle = !rawTitle.isNullOrEmpty()
        val padX = 4f
        val w = cachedContentWidth + padX * 2f
        val titleH = if (hasTitle) TITLE_H else 0f
        val h = titleH + rows.size * ROW_H + 3f

        var x = posX.value * (lw - w)
        var y = posY.value * (lh - h)
        if (!platform.chatOpen) {
            dragging = false
        } else if (platform.isMouseDown(0)) {
            if (!dragging && mx in x..(x + w) && my in y..(y + h)) {
                dragging = true
                dragDX = mx - x
                dragDY = my - y
            }
            if (dragging) {
                val snap = HudSnap.snap(
                    "scoreboard",
                    (mx - dragDX).coerceIn(0f, (lw - w).coerceAtLeast(0f)),
                    (my - dragDY).coerceIn(0f, (lh - h).coerceAtLeast(0f)),
                    w, h, lw, lh,
                )
                x = snap.x
                y = snap.y
                posX.setValue(if (lw > w) x / (lw - w) else 0f)
                posY.setValue(if (lh > h) y / (lh - h) else 0f)
            }
        } else {
            dragging = false
        }

        val panel = LayoutBounds(x, y, w, h)
        HudSnap.register("scoreboard", panel)
        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), mx.toInt(), my.toInt())
        try {
            ctx.pushScale(k, k, 1f)
            ctx.pushOpacity(alpha.value)
            if (glass.get()) {
                ctx.drawGlass(panel, panelGlass)
            } else {
                ctx.drawShape(panel, panelDark)
            }
            ctx.popOpacity()

            mt.clear()
            mt.clip = panel
            val textAlpha = 1f
            var ty = panel.y + 2f
            if (hasTitle) {
                val titleText = rawTitle ?: ""
                val titleX = panel.x + (w - cachedTitleWidth) / 2f
                if (replaceServer.get()) {
                    drawDomainTitle(ctx, titleText, titleX, ty, TITLE_SIZE, textAlpha)
                } else {
                    drawRuns(ctx, cachedTitleRuns, titleX, ty, TITLE_SIZE, textAlpha)
                }
                ty += TITLE_H
            }
            // 服务器按分数升序发布；原实现从末尾往前画，最高分在最上面
            for (i in rows.indices.reversed()) {
                val line = cachedLines[i]
                drawRuns(ctx, line.runs, panel.x + padX, ty, ROW_SIZE, textAlpha)
                val pt = pointsText(rows[i].points)
                val pw = width(pt, ROW_SIZE)
                val px = panel.right - padX - pw
                if (textShadow.get()) {
                    mt.text(ctx, pt, px + 0.8f, ty + 0.8f, ROW_SIZE, Color(0f, 0f, 0f, 0.55f))
                }
                mt.text(ctx, pt, px, ty, ROW_SIZE, POINTS_COLOR)
                ty += ROW_H
            }

            HudSnap.drawGuides(ctx)
            ctx.popTransform()
            mt.flush(k)
        } finally {
            ctx.end()
        }
    }

    companion object {
        /** 替换服务器标题时使用的域名（与原实现一致） */
        const val DOMAIN = "bipas.gay"

        val MODE_NAMES = arrayOf("固定", "渐变", "彩虹")

        private const val TITLE_SIZE = 10f
        private const val ROW_SIZE = 9.5f
        private const val TITLE_H = 12.5f
        private const val ROW_H = 11f

        private val POINTS_COLOR = Color(1f, 1f / 3f, 1f / 3f, 1f)

        /** 原版 §0-§f 颜色表（RRGGBB） */
        private val CODE_COLORS: IntArray = run {
            val codes = IntArray(128) { -1 }
            val colors = intArrayOf(
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
            )
            for (i in colors.indices) codes['0'.code + i] = colors[i]
            for (i in colors.indices) codes['a'.code + i] = colors[i]
            codes
        }

        @Volatile
        private var instance: ScoreboardModule? = null

        fun of(): ScoreboardModule? = instance

        /** 侧边栏标题（无计分板时为 null），保留 § 颜色码 */
        @Volatile
        @JvmStatic
        var title: String? = null
            private set

        /** 服务器发布顺序（分数升序），保留 § 颜色码 */
        @Volatile
        @JvmStatic
        var rows: Array<ScoreboardLine> = emptyArray()
            private set

        @JvmStatic
        fun publish(title: String?, lines: Array<ScoreboardLine>) {
            this.title = title
            rows = lines
        }
    }
}

/** 计分板一行：带 § 颜色码的显示名与分数 */
class ScoreboardLine(val name: String, val points: Int) {
    override fun equals(other: Any?): Boolean =
        other is ScoreboardLine && other.name == name && other.points == points

    override fun hashCode(): Int = name.hashCode() * 31 + points

    override fun toString(): String = "ScoreboardLine(${name.replace('§', '&')}, $points)"

    companion object {
        /** 供 runtime 比较内容用：数组元素按值比较 */
        fun same(a: Array<ScoreboardLine>, b: Array<ScoreboardLine>): Boolean {
            if (a.size != b.size) return false
            for (i in a.indices) if (a[i] != b[i]) return false
            return true
        }

        /**
         * 从服务器发布顺序（分数升序）里取出最多 [max] 行用于绘制。
         *
         * 从末尾（最高分）往前收集，跳过 `#` 开头的占位行，凑够 [max] 行就停，
         * 最后翻回升序交给 HUD（HUD 再倒序绘制）。
         *
         * 注意不能先截最后 [max] 行再过滤：插件常在末尾塞 `#` 占位行，
         * 那样会白丢几条真实数据（Vibe 原实现就是先过滤再计数）。
         */
        fun pickVisible(all: List<ScoreboardLine>, max: Int): Array<ScoreboardLine> {
            if (max <= 0 || all.isEmpty()) return emptyArray()
            val picked = ArrayList<ScoreboardLine>(minOf(max, all.size))
            var i = all.size - 1
            while (i >= 0 && picked.size < max) {
                val line = all[i]
                i--
                if (line.name.startsWith("#")) continue
                picked.add(line)
            }
            picked.reverse()
            return picked.toTypedArray()
        }

        fun formatPercent(v: Float): String = String.format(Locale.ROOT, "%.0f%%", v * 100f)
    }
}

/** 供测试与设置页使用：两色线性混合 */
internal fun mixRgb(from: Int, to: Int, t: Float): Int {
    val tt = t.coerceIn(0f, 1f)
    val r = (((from shr 16) and 0xFF) * (1 - tt) + ((to shr 16) and 0xFF) * tt).roundToInt().coerceIn(0, 255)
    val g = (((from shr 8) and 0xFF) * (1 - tt) + ((to shr 8) and 0xFF) * tt).roundToInt().coerceIn(0, 255)
    val b = ((from and 0xFF) * (1 - tt) + (to and 0xFF) * tt).roundToInt().coerceIn(0, 255)
    return (r shl 16) or (g shl 8) or b
}

/** 彩虹相位取色（与 Vibe 的 Color.HSBtoRGB 公式一致），供测试验证 */
internal fun rainbowRgb(phase: Double, saturation: Float): Int {
    val h = ((phase % 1.0) + 1.0) % 1.0
    val s = saturation.coerceIn(0f, 1f)
    val v = 1f
    val sector = (h * 6).toInt() % 6
    val f = (h * 6).toFloat() - (h * 6).toInt()
    val p = v * (1 - s)
    val q = v * (1 - f * s)
    val t = v * (1 - (1 - f) * s)
    val r: Float
    val g: Float
    val b: Float
    when (sector) {
        0 -> { r = v; g = t; b = p }
        1 -> { r = q; g = v; b = p }
        2 -> { r = p; g = v; b = t }
        3 -> { r = p; g = q; b = v }
        4 -> { r = t; g = p; b = v }
        else -> { r = v; g = p; b = q }
    }
    return ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()
}
