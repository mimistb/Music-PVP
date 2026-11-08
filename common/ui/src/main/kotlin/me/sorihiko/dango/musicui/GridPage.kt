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
            ui.text(title, page.x, page.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            val status = when {
                loading && System.currentTimeMillis() - ui.pageAt > 12000L -> "网络较慢，仍在加载…"
                loading -> "正在加载…"
                else -> sub
            }
            ui.text(status, page.x, page.y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        }
        area = LayoutBounds(page.x, page.y + 54f, page.width, page.bottom - (page.y + 54f))
        when {
            // 首次加载：用骨架块给出明确反馈，避免整页空白像“卡死”
            loading && items.isEmpty() -> drawSkeleton(area)
            items.isEmpty() -> drawEmpty(area)
            else -> drawGrid(area, items)
        }
    }

    /** 加载骨架：两行封面 + 标题占位，带统一流光；不改变任何加载逻辑 */
    private fun drawSkeleton(area: LayoutBounds) {
        val gap = 22f
        val cols = max(2, ((area.width + gap) / (160f + gap)).toInt())
        val cw = (area.width - gap * (cols - 1)) / cols
        val rowH = cw + 46f
        ui.beginClip(area)
        var r = 0
        while (r < 3) {
            val y = area.y + r * rowH
            if (y + cw > area.bottom) break
            for (c in 0 until cols) {
                val x = area.x + c * (cw + gap)
                ui.skeleton(LayoutBounds(x, y, cw, cw), 12f)
                ui.skeleton(LayoutBounds(x + 2f, y + cw + 8f, cw * 0.72f, 10f), 5f)
                ui.skeleton(LayoutBounds(x + 2f, y + cw + 24f, cw * 0.46f, 8f), 4f)
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
        val gap = 22f
        val cols = max(2, ((area.width + gap) / (160f + gap)).toInt())
        val cw = (area.width - gap * (cols - 1)) / cols
        val rowH = cw + 46f
        val totalRows = (items.size + cols - 1) / cols
        maxScroll = max(0f, totalRows * rowH - area.height)
        target = target.coerceIn(0f, maxScroll)
        pos = pos.coerceIn(0f, maxScroll)
        ScreenState.homeScroll = target

        // 上下多留一点空间给悬停时放大的边框
        val clip = LayoutBounds(area.x - 6f, area.y - 6f, area.width + 12f, area.height + 6f)
        ui.beginClip(clip)
        val firstRow = max(0, (pos / rowH).toInt())
        val lastRow = min(totalRows - 1, ((pos + area.height) / rowH).toInt())
        for (r in firstRow..lastRow) {
            for (c in 0 until cols) {
                val i = r * cols + c
                if (i >= items.size) break
                val p = items[i]
                val x = area.x + c * (cw + gap)
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
                    val lines = ui.wrap2(p.name, cw - 4f, 12f, Theme.FONT_MEDIUM)
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
