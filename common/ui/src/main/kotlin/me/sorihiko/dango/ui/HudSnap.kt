package me.sorihiko.dango.ui

import me.sorihiko.dango.module.impls.music.HudLayoutModule
import me.sorihiko.dango.utils.Color

/**
 * HUD 拖动吸附的统一入口。
 *
 * 各 HUD 模块每帧把自身的布局框 [register] 进来（带时间戳，超过 [TTL_NS] 自动淘汰），
 * 拖动时调用 [snap] 得到吸附后的坐标；命中的参考线由 [drawGuides] 在模块自己的
 * 画布坐标里补画，保证「吸附触发时提供视觉反馈」且不影响其它元素。
 */
object HudSnap {

    /** 布局框有效期：HUD 每帧都会重新登记，超过 80ms 视为该元素已不存在 */
    private const val TTL_NS = 80_000_000L

    /** 参考线颜色（和设置页的强调色一致） */
    private val GUIDE_COLOR = Color(0.32f, 0.66f, 1f, 0.9f)

    /** 拖动时与其它 HUD 重叠的描边颜色 */
    private val CONFLICT_COLOR = Color(1f, 0.32f, 0.28f, 0.95f)

    /** 透明的填充，只用来画描边 */
    private val NO_FILL = Color(0f, 0f, 0f, 0f)

    private class Entry(var bounds: LayoutBounds, var at: Long)

    private val entries = HashMap<String, Entry>()
    private val engine = SnapEngine()

    /** 复用的"其它元素"列表：拖动时每帧都要用，不能每次重建 Sequence / ArrayList */
    private val others = ArrayList<LayoutBounds>(16)

    private var guideX: Float? = null
    private var guideY: Float? = null
    private var guideW = 0f
    private var guideH = 0f
    private var guideAt = 0L

    /** 拖动中的元素与其它元素重叠时记录的双方矩形 */
    private var conflictSelf: LayoutBounds? = null
    private var conflictOther: LayoutBounds? = null
    private var conflictAt = 0L

    /** 两个 HUD 元素的重叠信息（[overlapReport] 使用） */
    class Overlap(val a: String, val b: String, val area: Float)

    /** 每帧登记当前元素的位置；同一 id 覆盖上一帧 */
    fun register(id: String, bounds: LayoutBounds) {
        if (bounds.width <= 0f || bounds.height <= 0f) return
        val now = System.nanoTime()
        // 每帧都会重新登记：命中已有条目时就地更新，避免每模块每帧 new 一个 Entry
        val e = entries[id]
        if (e != null) {
            e.bounds = bounds
            e.at = now
        } else {
            entries[id] = Entry(bounds, now)
        }
    }

    /** 对拖动中的坐标做吸附；[viewportWidth] / [viewportHeight] 是当前逻辑坐标系尺寸 */
    fun snap(
        id: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): SnapResult {
        val now = System.nanoTime()
        // 手动迭代淘汰过期条目：removeIf + 捕获 lambda 在拖动帧里每次都要分配
        val it = entries.values.iterator()
        while (it.hasNext()) {
            if (now - it.next().at > TTL_NS) it.remove()
        }
        // 安全区：拖动时别把 HUD 贴到屏幕边缘（视口比元素还小时退化为 SAFE）
        val sx = x.coerceIn(Theme.HUD_SAFE, (viewportWidth - width - Theme.HUD_SAFE).coerceAtLeast(Theme.HUD_SAFE))
        val sy = y.coerceIn(Theme.HUD_SAFE, (viewportHeight - height - Theme.HUD_SAFE).coerceAtLeast(Theme.HUD_SAFE))
        val cfg = HudLayoutModule.of() ?: return SnapResult(sx, sy)
        conflictSelf = null
        conflictOther = null
        if (!cfg.snapEnabled.get()) {
            guideX = null
            guideY = null
            return SnapResult(sx, sy)
        }
        others.clear()
        for ((key, value) in entries) {
            if (key != id) others.add(value.bounds)
        }
        val result = engine.snap(
            x = sx,
            y = sy,
            width = width,
            height = height,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            others = others,
            modes = SnapMode.fromMask(cfg.snapMask.value),
            tolerance = cfg.snapTolerance.value,
            grid = cfg.gridSize.value,
        )
        // 重叠冲突：吸附只能避免贴边，不能避免两个元素真正叠在一起
        for (other in others) {
            if (overlapArea(result.x, result.y, width, height, other) > 1f) {
                conflictSelf = LayoutBounds(result.x, result.y, width, height)
                conflictOther = other
                conflictAt = now
                break
            }
        }
        if (result.snapped && cfg.snapGuides.get()) {
            guideX = result.guideX
            guideY = result.guideY
            guideW = viewportWidth
            guideH = viewportHeight
            guideAt = now
        } else if (!result.snapped) {
            guideX = null
            guideY = null
        }
        return result
    }

    /** 在模块自己的画布坐标系里画参考线（1 逻辑像素宽） */
    fun drawGuides(ctx: RenderContext) {
        if (conflictSelf != null || conflictOther != null) {
            if (System.nanoTime() - conflictAt > TTL_NS) {
                conflictSelf = null
                conflictOther = null
            } else {
                // 只画描边，不填色：避免把面板内容盖住
                conflictSelf?.let { outline(ctx, it) }
                conflictOther?.let { outline(ctx, it) }
            }
        }
        if (guideX == null && guideY == null) return
        if (System.nanoTime() - guideAt > TTL_NS) {
            guideX = null
            guideY = null
            return
        }
        guideX?.let {
            ctx.drawShape(
                LayoutBounds(it, 0f, 1f, guideH),
                ShapeStyle(fillColor = GUIDE_COLOR),
            )
        }
        guideY?.let {
            ctx.drawShape(
                LayoutBounds(0f, it, guideW, 1f),
                ShapeStyle(fillColor = GUIDE_COLOR),
            )
        }
    }

    /**
     * 扫描当前所有 HUD 元素，把两两重叠的项追加到 [out]（重叠面积 > 1 逻辑像素）。
     * 供设置页 / 调试命令检查布局冲突，返回冲突对数。
     */
    fun overlapReport(out: MutableList<Overlap>): Int {
        prune(System.nanoTime())
        var count = 0
        val list = entries.entries.toList()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val area = overlapAreaOf(list[i].value.bounds, list[j].value.bounds)
                if (area > 1f) {
                    out.add(Overlap(list[i].key, list[j].key, area))
                    count++
                }
            }
        }
        return count
    }

    /** 当前重叠的 HUD 对数 */
    fun overlapCount(): Int {
        prune(System.nanoTime())
        var count = 0
        val list = entries.values.toList()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                if (overlapAreaOf(list[i].bounds, list[j].bounds) > 1f) count++
            }
        }
        return count
    }

    private fun outline(ctx: RenderContext, bounds: LayoutBounds) {
        ctx.drawShape(
            bounds,
            ShapeStyle(fillColor = NO_FILL, radius = 4f, borderWidth = 1.5f, borderColor = CONFLICT_COLOR),
        )
    }

    private fun prune(now: Long) {
        val it = entries.values.iterator()
        while (it.hasNext()) {
            if (now - it.next().at > TTL_NS) it.remove()
        }
    }

    private fun overlapArea(x: Float, y: Float, w: Float, h: Float, other: LayoutBounds): Float {
        val left = maxOf(x, other.x)
        val top = maxOf(y, other.y)
        val right = minOf(x + w, other.right)
        val bottom = minOf(y + h, other.bottom)
        if (right <= left || bottom <= top) return 0f
        return (right - left) * (bottom - top)
    }

    private fun overlapAreaOf(a: LayoutBounds, b: LayoutBounds): Float =
        overlapArea(a.x, a.y, a.width, a.height, b)
}
