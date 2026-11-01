package me.sorihiko.dango.module.impls.music

import bedwarsstats.BedWarsStats
import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.hud.BedWarsData
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * TAB 统计列表：按住 TAB 时，在灵动岛里横向排列显示在场所有人的 Hypixel 数据。
 *
 * 名单由平台层每 tick 从 TAB 玩家列表写进 [BedWarsData.tabNames]，
 * 统计来自 [BedWarsData.statsFor] 的缓存（由自动名单管线与手动查询填充）。
 * 列表不再自己画在屏幕中间：尺寸由 [measure] 交给灵动岛，绘制进灵动岛的胶囊内容区，
 * 灵动岛尺寸不够时会换行 / 截断并标注显示人数，保证不超出屏幕。
 *
 * 歌词开关打开时 [isOpen] 为 false：列表临时隐藏，避免和歌词抢同一个胶囊；
 * 此时原版 TAB 列表也不再被拦截，按住 TAB 仍能看到原版名单。
 *
 * 本模块只读 BedWarsData，不直接碰游戏对象。
 */
@ModuleMeta(Category.MUSIC, "TabStats")
class TabStatsHudModule : Module() {

    @ModuleValue("缩放", "灵动岛内 TAB 列表的大小")
    private val scale = FloatValue(0.6f, 1.6f, 1f, 2)

    private val mt = MixedText()

    /**
     * 列表是否正在灵动岛里显示（平台层据此隐藏原版 TAB 列表、统计管线据此补查在场所有人）。
     *
     * 需要同时满足：本模块开启、在游戏内、没开聊天栏、按住 TAB，
     * 且灵动岛能承接列表（灵动岛开启、歌词关闭）。
     */
    val isOpen: Boolean
        get() = enabled && DangoClient.platform.inGame &&
                !DangoClient.platform.chatOpen &&
                DangoClient.platform.isKeyDown(Keys.TAB) &&
                DynamicIslandModule.of()?.isIslandTabHost == true

    /** 当前一帧的横向布局（[measure] 算好，[draw] 直接复用，避免每帧重复排版） */
    private class Grid {
        var scale = 1f
        var cols = 1
        var shown = 0
        var total = 0
        var w = 0f
        var h = 0f
    }

    private val grid = Grid()

    // ---- 每个条目的显示字符串缓存：名单 / 数据版本 / 设置变化时才重建 ----
    private var cachedSource: Array<String>? = null
    private var cachedVersion = Int.MIN_VALUE
    private var cachedFingerprint = Int.MIN_VALUE
    private val cName = ArrayList<String>()
    private val cInitial = ArrayList<String>()
    private val cLine = ArrayList<String>()
    private val cHasData = ArrayList<Boolean>()

    private val avatarFill = Color(1f, 1f, 1f, 0.07f)
    private val avatarRing = Color(1f, 1f, 1f, 0.10f)
    private val entryFill = Color(1f, 1f, 1f, 0.05f)

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

    /** 统计设置指纹：样式 / 小数位 + 7 个显示开关 + 列表缩放 */
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
        // 缩放变了裁剪宽度也变，条目字符串要重建
        f = f * 17 + (scale.value * 100f).toInt().coerceIn(60, 160)
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
        val s = scale.value
        val nameMaxW = (ENTRY_W - AVATAR - 12f) * s
        val lineMaxW = (ENTRY_W - AVATAR - 10f) * s
        for (raw in names) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            val stats = BedWarsData.statsFor(name)
            cName.add(mt.trim(name, nameMaxW, NAME_SIZE * s, Theme.FONT_SEMIBOLD))
            cInitial.add(name.substring(0, 1).uppercase(Locale.ROOT))
            cLine.add(if (stats == null) "无数据" else mt.trim(statsLine(stats), lineMaxW, LINE_SIZE * s, Theme.FONT_MEDIUM))
            cHasData.add(stats != null)
        }
    }

    /**
     * 按灵动岛允许的尺寸计算横向列表大小（单位：灵动岛逻辑像素）。
     *
     * 条目从左往右排、排满一行再往下换行；宽高都收在 [maxW] / [maxH] 里，
     * 高度不够时按行截断并在标题标注"显示 x / y 人"，保证胶囊不超出屏幕。
     */
    fun measure(maxW: Float, maxH: Float): Pair<Float, Float> {
        ensureCache()
        val s = scale.value
        val n = cName.size
        val padX = PAD_X * s
        val padY = PAD_Y * s
        val titleH = TITLE_H * s
        val colW = ENTRY_W * s
        val rowH = ENTRY_H * s
        val gapX = GAP_X * s
        val gapY = GAP_Y * s

        val availW = (maxW - padX * 2f).coerceAtLeast(colW)
        var cols = floor((availW + gapX) / (colW + gapX)).toInt().coerceAtLeast(1)
        if (n > 0) cols = min(cols, n)
        val needRows = if (n == 0) 0 else ceil(n.toFloat() / cols.toFloat()).toInt()
        val availH = (maxH - padY * 2f - titleH).coerceAtLeast(rowH)
        val maxRows = floor((availH + gapY) / (rowH + gapY)).toInt().coerceAtLeast(1)
        val rows = min(needRows, maxRows)
        val shown = min(n, cols * rows)

        val bodyH = if (rows == 0) BODY_MIN_H * s else rows * rowH + (rows - 1) * gapY
        grid.scale = s
        grid.cols = cols
        grid.shown = shown
        grid.total = n
        grid.w = cols * colW + (cols - 1) * gapX + padX * 2f
        grid.h = padY * 2f + titleH + bodyH
        return grid.w to grid.h
    }

    /** 把横向列表画进灵动岛内容区（[bounds] 是灵动岛逻辑坐标里的可用区域） */
    fun draw(ctx: GuiCanvas, bounds: LayoutBounds, k: Float) {
        ensureCache()
        val g = grid
        val s = g.scale
        if (s <= 0f) return
        val padX = PAD_X * s
        val padY = PAD_Y * s

        mt.clear()
        mt.clip = bounds

        val headerY = bounds.y + padY
        text(ctx, "在场玩家", bounds.x + padX, headerY, TITLE_SIZE * s, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val tw = width("在场玩家", TITLE_SIZE * s, Theme.FONT_SEMIBOLD)
        val count = if (g.shown < g.total) "显示 ${g.shown} / ${g.total} 人" else "${g.total} 人"
        text(ctx, count, bounds.x + padX + tw + 8f * s, headerY + s, COUNT_SIZE * s, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)

        if (g.total == 0) {
            text(
                ctx, "无人在线", bounds.x + padX, headerY + TITLE_H * s, BODY_SIZE * s,
                Theme.TEXT_TERTIARY, Theme.FONT_MEDIUM,
            )
        } else {
            val top = bounds.y + padY + TITLE_H * s
            for (i in 0 until g.shown) {
                val c = i % g.cols
                val r = i / g.cols
                drawEntry(
                    ctx, i,
                    bounds.x + padX + c * (ENTRY_W + GAP_X) * s,
                    top + r * (ENTRY_H + GAP_Y) * s, s,
                )
            }
        }
        mt.flush(k)
    }

    private fun drawEntry(ctx: GuiCanvas, i: Int, ex: Float, ey: Float, s: Float) {
        // 条目底色：横向排开时更容易分辨每个玩家
        ctx.drawShape(LayoutBounds(ex, ey, ENTRY_W * s, ENTRY_H * s), ShapeStyle(entryFill, 7f * s))
        val av = AVATAR * s
        val ax = ex + 6f * s
        val ay = ey + (ENTRY_H * s - av) * 0.5f
        ctx.drawShape(LayoutBounds(ax, ay, av, av), ShapeStyle(avatarFill, av * 0.5f, 1f, avatarRing))
        val initial = cInitial[i]
        text(
            ctx, initial, ax + (av - width(initial, 9f * s, Theme.FONT_SEMIBOLD)) * 0.5f,
            ay + (av - 9f * s * 0.73f) * 0.5f, 9f * s, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD,
        )

        val tx = ax + av + 6f * s
        text(ctx, cName[i], tx, ey + 3f * s, NAME_SIZE * s, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val line = cLine[i]
        if (line.isNotEmpty()) {
            text(
                ctx, line, tx, ey + 14.5f * s, LINE_SIZE * s,
                if (cHasData[i]) Theme.TEXT_SECONDARY else Theme.TEXT_TERTIARY, Theme.FONT_MEDIUM,
            )
        }
    }

    companion object {
        // ---- 布局常量（缩放 1 时的逻辑像素） ----
        private const val PAD_X = 10f
        private const val PAD_Y = 8f
        private const val TITLE_H = 15f
        private const val TITLE_SIZE = 10f
        private const val COUNT_SIZE = 8.5f
        private const val BODY_MIN_H = 16f
        private const val BODY_SIZE = 9f
        private const val ENTRY_W = 170f
        private const val ENTRY_H = 26f
        private const val GAP_X = 6f
        private const val GAP_Y = 4f
        private const val AVATAR = 18f
        private const val NAME_SIZE = 10.5f
        private const val LINE_SIZE = 8f

        @Volatile
        private var instance: TabStatsHudModule? = null

        fun of(): TabStatsHudModule? = instance
    }
}
