package me.sorihiko.dango.module.impls.music

import bedwarsstats.BedWarsStats
import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.WorldRenderEndEvent
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.hud.BedWarsData
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * TAB 统计列表：按住 TAB 时在屏幕中间显示在场所有人的 Hypixel 数据。
 *
 * 名单由平台层每 tick 从 TAB 玩家列表写进 [BedWarsData.tabNames]，
 * 统计来自 [BedWarsData.statsFor] 的缓存（由自动名单管线与手动查询填充）；
 * 原版 TAB 列表在同一个界面帧里更晚绘制，会盖住这块面板，所以平台层在
 * 面板打开时取消 RenderGameOverlayEvent 的 PLAYER_LIST，跳过原版列表。
 *
 * 本模块只读 BedWarsData，不直接碰游戏对象。
 */
@ModuleMeta(Category.MUSIC, "TabStats")
class TabStatsHudModule : Module() {

    @ModuleValue("液态玻璃", "液态玻璃背景；关闭后为纯黑面板")
    private val glass = BooleanValue(true)

    @ModuleValue("缩放", "列表面板大小")
    private val scale = FloatValue(0.6f, 1.6f, 1f, 2)

    private var context: GuiCanvas? = null
    private val mt = MixedText()

    /** 面板是否正在显示（平台层据此隐藏原版 TAB 列表） */
    val isOpen: Boolean
        get() = enabled && DangoClient.platform.inGame &&
                !DangoClient.platform.chatOpen &&
                DangoClient.platform.isKeyDown(Keys.TAB)

    // ---- 布局常量（逻辑像素） ----
    private val pad = 12f
    private val titleH = 24f
    private val entryW = 196f
    private val entryH = 30f
    private val colGap = 8f
    private val rowGap = 4f
    private val avatar = 20f
    private val nameSize = 11f
    private val lineSize = 8.5f

    /** 名字 / 统计行可用宽度（固定列宽，缓存裁剪结果就够了） */
    private val nameMaxW = entryW - avatar - 14f
    private val lineMaxW = entryW - avatar - 10f

    /** 面板玻璃：半径 / 折射按小面板缩放，避免大斜面糊住文字 */
    private val panelGlass = Theme.GLASS_WINDOW.copy(
        radius = 14f,
        refraction = 12f,
        bevel = 14f,
        dispersion = 0.22f,
        shadow = ShadowStyle(Color(0f, 0f, 0f, 0.28f), 24f, Vec2(0f, -8f)),
    )

    private val darkPanel = Color(0.02f, 0.02f, 0.03f, 0.86f)
    private val panelBorder = Color(1f, 1f, 1f, 0.08f)
    private val avatarFill = Color(1f, 1f, 1f, 0.07f)
    private val avatarRing = Color(1f, 1f, 1f, 0.10f)

    // ---- 每个条目的显示字符串缓存：名单 / 数据版本 / 设置变化时才重建 ----
    private var cachedSource: Array<String>? = null
    private var cachedVersion = Int.MIN_VALUE
    private var cachedFingerprint = Int.MIN_VALUE
    private val cName = ArrayList<String>()
    private val cInitial = ArrayList<String>()
    private val cLine = ArrayList<String>()
    private val cHasData = ArrayList<Boolean>()

    override fun init() {
        instance = this
        // 新模块默认打开（没有存档记录的用户按住 TAB 就能看到）；存档里有值时会被覆盖
        enabled = true
    }

    private fun text(
        ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float, color: Color,
        font: String = Theme.FONT_REGULAR,
    ) = mt.text(ctx, s, x, y, size, color, font)

    private fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float = mt.width(s, size, font)

    /** 统计设置指纹：样式 / 小数位 + 7 个显示开关 */
    private fun fingerprint(): Int {
        var f = BedWarsData.hypStyle.coerceIn(0, 2)
        f = f * 3 + BedWarsData.hypDecimals.coerceIn(0, 2)
        f = f * 2 + (if (BedWarsData.hypStars) 1 else 0)
        f = f * 2 + (if (BedWarsData.hypFkdr) 1 else 0)
        f = f * 2 + (if (BedWarsData.hypWlr) 1 else 0)
        f = f * 2 + (if (BedWarsData.hypBblr) 1 else 0)
        f = f * 2 + (if (BedWarsData.hypWinStreak) 1 else 0)
        f = f * 2 + (if (BedWarsData.hypWins) 1 else 0)
        f = f * 2 + (if (BedWarsData.hypNetLevel) 1 else 0)
        return f
    }

    /**
     * 单个条目的统计行（单行）：
     *   0 紧凑 = 两个空格连接，星 + 比率 + 连胜
     *   1 Lunar = " · " 连接，星 / 网络等级 + 比率 + 连胜
     *   2 详细 = 在 Lunar 之后追加胜场
     * 全部项被关掉时返回空串（条目只显示名字）。
     */
    private fun statsLine(s: BedWarsStats): String {
        val dec = BedWarsData.hypDecimals.coerceIn(0, 2)
        val star = if (BedWarsData.hypStars) "★ ${s.stars}" else ""
        val netLv = if (BedWarsData.hypNetLevel) "Lv ${s.networkLevel}" else ""
        val fkdr = if (BedWarsData.hypFkdr) "FKDR " + ratio(s.fkdr(), dec) else ""
        val wlr = if (BedWarsData.hypWlr) "WLR " + ratio(s.wlr(), dec) else ""
        val bb = if (BedWarsData.hypBblr) "BB " + ratio(s.bblr(), dec) else ""
        val ws = if (BedWarsData.hypWinStreak) "WS ${s.winStreak}" else ""
        val wins = if (BedWarsData.hypWins) "W ${s.wins}" else ""
        return when (BedWarsData.hypStyle.coerceIn(0, 2)) {
            0 -> join("  ", star, fkdr, wlr, bb, ws)
            1 -> join(" · ", star, netLv, fkdr, wlr, bb, ws)
            else -> join(" · ", star, netLv, fkdr, wlr, bb, ws, wins)
        }
    }

    private fun join(sep: String, vararg items: String): String {
        val sb = StringBuilder(64)
        for (item in items) {
            if (item.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(sep)
            sb.append(item)
        }
        return sb.toString()
    }

    private fun ratio(v: Double, dec: Int): String = String.format(Locale.ROOT, "%.${dec}f", v)

    /** 名单 / 数据 / 设置变化时重建条目缓存（每帧只做三次比较） */
    private fun ensureCache() {
        val names = BedWarsData.tabNames
        val version = BedWarsData.statsVersion
        val fp = fingerprint()
        if (names === cachedSource && version == cachedVersion && fp == cachedFingerprint) return
        cachedSource = names
        cachedVersion = version
        cachedFingerprint = fp
        cName.clear()
        cInitial.clear()
        cLine.clear()
        cHasData.clear()
        for (raw in names) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            val stats = BedWarsData.statsFor(name)
            cName.add(mt.trim(name, nameMaxW, nameSize, Theme.FONT_SEMIBOLD))
            cInitial.add(name.substring(0, 1).uppercase(Locale.ROOT))
            cLine.add(if (stats == null) "无数据" else mt.trim(statsLine(stats), lineMaxW, lineSize, Theme.FONT_MEDIUM))
            cHasData.add(stats != null)
        }
    }

    @EventHandler
    fun render(e: WorldRenderEndEvent) {
        if (!isOpen) return
        ensureCache()
        val platform = DangoClient.platform

        val uiScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
        val k = uiScale * scale.value
        val lw = platform.windowWidth / k
        val lh = platform.windowHeight / k
        val mx = platform.mouseX / k
        val my = platform.mouseY / k

        val n = cName.size
        // 先按可用高度算每列最多几行，再按人数决定列数；宽度不够时截断并标注显示人数
        val maxH = (lh - 16f).coerceAtLeast(pad * 2f + titleH + entryH)
        val maxW = (lw - 16f).coerceAtLeast(pad * 2f + entryW)
        val maxRows = (((maxH - pad * 2f - titleH + rowGap) / (entryH + rowGap)).toInt()).coerceAtLeast(1)
        var cols = if (n == 0) 1 else ceil(n.toFloat() / maxRows.toFloat()).toInt().coerceAtLeast(1)
        val maxCols = (((maxW - pad * 2f + colGap) / (entryW + colGap)).toInt()).coerceAtLeast(1)
        if (cols > maxCols) cols = maxCols
        val shown = min(n, cols * maxRows)
        val rows = if (shown == 0) 0 else ceil(shown.toFloat() / cols.toFloat()).toInt().coerceAtLeast(1)

        val bodyH = if (rows == 0) 18f else rows * entryH + (rows - 1) * rowGap
        val panelW = cols * entryW + (cols - 1) * colGap + pad * 2f
        val panelH = pad * 2f + titleH + bodyH
        val x = (lw - panelW) * 0.5f
        val y = (lh - panelH) * 0.5f

        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), mx.toInt(), my.toInt())
        try {
            ctx.pushScale(k, k, 1f)
            val panel = LayoutBounds(x, y, panelW, panelH)
            if (glass.get()) {
                ctx.drawGlass(panel, panelGlass)
            } else {
                ctx.drawShape(panel, ShapeStyle(darkPanel, 14f, 1f, panelBorder))
            }

            mt.clear()
            mt.clip = panel

            text(ctx, "在场玩家", panel.x + pad, panel.y + pad, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            val tw = width("在场玩家", 12f, Theme.FONT_SEMIBOLD)
            val count = if (shown < n) "显示 $shown / $n 人" else "$n 人"
            text(ctx, count, panel.x + pad + tw + 8f, panel.y + pad + 2f, 9.5f, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)

            if (n == 0) {
                text(ctx, "无人在线", panel.x + pad, panel.y + pad + titleH, 10f, Theme.TEXT_TERTIARY, Theme.FONT_MEDIUM)
            } else {
                var idx = 0
                for (c in 0 until cols) {
                    for (r in 0 until rows) {
                        if (idx >= shown) break
                        val ex = panel.x + pad + c * (entryW + colGap)
                        val ey = panel.y + pad + titleH + r * (entryH + rowGap)
                        drawEntry(ctx, idx, ex, ey)
                        idx++
                    }
                }
            }

            ctx.popTransform()
            mt.flush(k)
        } finally {
            ctx.end()
        }
    }

    private fun drawEntry(ctx: GuiCanvas, i: Int, ex: Float, ey: Float) {
        val headY = ey + (entryH - avatar) * 0.5f
        ctx.drawShape(LayoutBounds(ex, headY, avatar, avatar), ShapeStyle(avatarFill, avatar * 0.5f, 1f, avatarRing))
        val initial = cInitial[i]
        text(ctx, initial, ex + (avatar - width(initial, 9f, Theme.FONT_SEMIBOLD)) * 0.5f,
            headY + (avatar - 9f * 0.73f) * 0.5f, 9f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)

        val tx = ex + avatar + 8f
        text(ctx, cName[i], tx, ey + 3f, nameSize, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val line = cLine[i]
        if (line.isNotEmpty()) {
            text(ctx, line, tx, ey + 16.5f, lineSize,
                if (cHasData[i]) Theme.TEXT_SECONDARY else Theme.TEXT_TERTIARY, Theme.FONT_MEDIUM)
        }
    }

    companion object {
        @Volatile
        private var instance: TabStatsHudModule? = null

        fun of(): TabStatsHudModule? = instance
    }
}
