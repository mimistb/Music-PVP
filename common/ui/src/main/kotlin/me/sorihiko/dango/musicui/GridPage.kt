package me.sorihiko.dango.musicui

import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.NeteasePlaylist
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme

import kotlin.math.max
import kotlin.math.min

/** 歌单墙（推荐歌单）：正方形封面 + 下面两行名字，平滑滚动 */
internal class GridPage(private val screen: MusicScreen) {
    private val ui = screen.ui

    /** 本帧画了歌单墙的区域（滚轮用），没画就是空的 */
    var area = LayoutBounds()
    // 平滑滚动（逻辑像素），关掉界面再打开从上次的位置继续
    private var target = ScreenState.homeScroll
    private var pos = ScreenState.homeScroll
    private var maxScroll = 0f

    /**
     * 一次布局下的栅格尺寸。封面墙和加载骨架共用同一套算法，
     * 避免两处各写一遍列宽 / 行高（以前骨架和真实内容会因公式不同而错位）。
     */
    private class GridMetrics(val gap: Float, val cols: Int, val cw: Float, val rowH: Float)

    private fun metrics(area: LayoutBounds): GridMetrics {
        val gap = 22f
        val cols = max(2, ((area.width + gap) / (160f + gap)).toInt())
        val cw = (area.width - gap * (cols - 1)) / cols
        return GridMetrics(gap, cols, cw, cw + 46f)
    }

    /** 歌名折行缓存：宽度不变时每帧复用，避免重复测量字体 */
    private var wrapWidth = -1f
    private val wrapCache = HashMap<String, List<String>>()

    private fun nameLines(name: String, maxW: Float): List<String> {
        if (maxW != wrapWidth) {
            wrapWidth = maxW
            wrapCache.clear()
        }
        return wrapCache.getOrPut(name) { ui.wrap2(name, maxW, 12f, Theme.FONT_MEDIUM) }
    }

    fun tick() {
        pos = ui.approach(pos, target, 70f)
    }

    fun resetScroll() {
        target = 0f
        pos = 0f
        ScreenState.homeScroll = 0f
    }

    fun scroll(wheel: Int) {
        target = (target + (if (wheel > 0) -120f else 120f)).coerceIn(0f, maxScroll)
        ScreenState.homeScroll = target
    }

    fun draw(page: LayoutBounds, title: String, sub: String, items: List<NeteasePlaylist>, loading: Boolean) {
        ui.headerAnim {
            ui.text(title, page.x, page.y, Theme.PAGE_TITLE_SIZE, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            val status = when {
                loading && System.currentTimeMillis() - ui.pageAt > 12000L -> "网络较慢，仍在加载…"
                loading -> "正在加载…"
                else -> sub
            }
            ui.text(status, page.x, page.y + Theme.PAGE_SUB_Y, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        }
        drawRefreshButton(page)
        area = LayoutBounds(page.x, page.y + Theme.PAGE_HEADER_H, page.width, page.bottom - (page.y + Theme.PAGE_HEADER_H))
        when {
            // 首次加载：用骨架块给出明确反馈，避免整页空白像“卡死”
            loading && items.isEmpty() -> drawSkeleton(area)
            items.isEmpty() -> drawEmpty(area)
            else -> drawGrid(area, items)
        }
    }

    /**
     * 功能操作区的「刷新歌单」：重新请求当前平台的推荐歌单接口，
     * 数据回来前保留旧内容，回来后整块替换；加载中禁止重复点击。
     */
    private fun drawRefreshButton(page: LayoutBounds) {
        val loading = Library.recommendLoading
        val label = if (loading) "刷新中…" else "刷新歌单"
        val w = ui.width(label, 11f, Theme.FONT_MEDIUM) + 42f
        val b = LayoutBounds(page.right - w, page.y + 6f, w, 26f)
        val hov = !loading && ui.hover(b)
        ui.panel(b, 13f, active = loading || hov)
        ui.icon("↻", b.x + 16f, b.y + 13f, 11f, if (loading) Theme.TEXT_TERTIARY else Theme.TEXT_SECONDARY)
        ui.text(
            label, b.x + 28f, b.y + 8f, 11f,
            if (loading) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM,
        )
        if (!loading) ui.hits += b to { Library.refreshRecommend() }
    }

    /** 加载骨架：两行封面 + 标题占位，带统一流光；不改变任何加载逻辑 */
    private fun drawSkeleton(area: LayoutBounds) {
        val m = metrics(area)
        ui.beginClip(area)
        var r = 0
        while (r < 3) {
            val y = area.y + r * m.rowH
            if (y + m.cw > area.bottom) break
            for (c in 0 until m.cols) {
                val x = area.x + c * (m.cw + m.gap)
                ui.skeleton(LayoutBounds(x, y, m.cw, m.cw), 12f)
                ui.skeleton(LayoutBounds(x + 2f, y + m.cw + 8f, m.cw * 0.72f, 10f), 5f)
                ui.skeleton(LayoutBounds(x + 2f, y + m.cw + 24f, m.cw * 0.46f, 8f), 4f)
            }
            r++
        }
        ui.endClip()
    }

    /** 空状态：加载结束但确实没有内容 */
    private fun drawEmpty(area: LayoutBounds) {
        ui.text("暂时没有推荐内容", area.x, area.y + 4f, 12f, Theme.TEXT_TERTIARY)
        ui.text("检查网络或稍后刷新", area.x, area.y + 24f, 10f, Theme.TEXT_TERTIARY)
    }

    private fun drawGrid(area: LayoutBounds, items: List<NeteasePlaylist>) {
        val m = metrics(area)
        val cols = m.cols
        val cw = m.cw
        val rowH = m.rowH
        val totalRows = (items.size + cols - 1) / cols
        maxScroll = max(0f, totalRows * rowH - area.height)
        target = target.coerceIn(0f, maxScroll)
        pos = pos.coerceIn(0f, maxScroll)
        ScreenState.homeScroll = target

        // 上下左右各多留 6px 给悬停时放大的边框（之前只在上方留了，底部会被裁掉一条）
        val clip = LayoutBounds(area.x - 6f, area.y - 6f, area.width + 12f, area.height + 12f)
        ui.beginClip(clip)
        val firstRow = max(0, (pos / rowH).toInt())
        val lastRow = min(totalRows - 1, ((pos + area.height) / rowH).toInt())
        for (r in firstRow..lastRow) {
            for (c in 0 until cols) {
                val i = r * cols + c
                if (i >= items.size) break
                val p = items[i]
                val x = area.x + c * (cw + m.gap)
                val y = area.y + r * rowH - pos
                ui.itemAnim((r - firstRow) * cols + c) {
                    val cover = LayoutBounds(x, y, cw, cw)
                    val cell = LayoutBounds(x, y, cw, rowH - 6f)
                    val hov = ui.hover(cell) && clip.contains(ui.mx, ui.my)
                    if (hov) ui.panel(LayoutBounds(x - 5f, y - 5f, cw + 10f, rowH + 2f), 14f)
                    ui.drawCover(cover, CoverCache.playlist(p), 12f)
                    if (hov) {
                        // 悬停：右下角一个播放按钮
                        val pb = LayoutBounds(cover.right - 40f, cover.bottom - 40f, 30f, 30f)
                        ui.ctx.drawShape(pb, ShapeStyle(RED, 15f))
                        ui.icon("▶", pb.x + 16f, pb.y + 15f, 12f, Theme.TEXT_PRIMARY)
                    }
                    val lines = nameLines(p.name, cw - 4f)
                    lines.forEachIndexed { li, line ->
                        ui.text(line, x + 2f, cover.bottom + 8f + li * 15f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                    }
                    val from = ScreenState.page
                    ui.hits += cell.intersect(area) to { screen.openPlaylist(p, from) }
                }
            }
        }
        ui.endClip()
    }
}
