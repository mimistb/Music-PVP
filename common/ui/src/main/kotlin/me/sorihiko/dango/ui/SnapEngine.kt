package me.sorihiko.dango.ui

import kotlin.math.abs
import kotlin.math.roundToInt

/** 吸附模式：三种模式可以同时开启（[bit] 用于配置里的位掩码存储） */
enum class SnapMode(val bit: Int) {
    /** 与其它 HUD 元素的边缘 / 中线对齐 */
    ALIGN(1),

    /** 对齐到固定网格 */
    GRID(2),

    /** 吸附到屏幕边缘与屏幕中线 */
    EDGE(4),
    ;

    companion object {
        fun fromMask(mask: Int): Set<SnapMode> =
            entries.filter { mask and it.bit != 0 }.toSet()

        fun toMask(modes: Set<SnapMode>): Int = modes.fold(0) { acc, m -> acc or m.bit }
    }
}

/**
 * 一次吸附的结果。[x] / [y] 是需要落到的坐标；[guideX] / [guideY] 是视觉反馈用的参考线
 * （逻辑坐标，null 表示该轴没有触发吸附）。
 */
data class SnapResult(
    val x: Float,
    val y: Float,
    val guideX: Float? = null,
    val guideY: Float? = null,
    val mode: SnapMode? = null,
) {
    val snapped: Boolean get() = mode != null
}

/**
 * HUD 布局吸附引擎：纯计算，不依赖渲染与平台层，方便单测。
 *
 * 三种模式按优先级依次尝试：对齐其它元素 → 屏幕边缘 → 网格。
 * 每个轴只取「距离最近且在容差内」的目标，因此不会出现来回抖动。
 */
class SnapEngine {

    fun snap(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        others: List<LayoutBounds> = emptyList(),
        modes: Set<SnapMode> = setOf(SnapMode.ALIGN, SnapMode.GRID, SnapMode.EDGE),
        tolerance: Float = 6f,
        grid: Float = 8f,
    ): SnapResult {
        if (modes.isEmpty() || tolerance <= 0f) return SnapResult(x, y)
        val tol = tolerance

        var bestX = Float.NaN
        var bestDx = Float.MAX_VALUE
        var modeX: SnapMode? = null
        var guideX = Float.NaN

        var bestY = Float.NaN
        var bestDy = Float.MAX_VALUE
        var modeY: SnapMode? = null
        var guideY = Float.NaN

        fun tryX(target: Float, mode: SnapMode, guide: Float) {
            val d = abs(target - x)
            if (d <= tol && d < bestDx) {
                bestDx = d
                bestX = target
                modeX = mode
                guideX = guide
            }
        }

        fun tryY(target: Float, mode: SnapMode, guide: Float) {
            val d = abs(target - y)
            if (d <= tol && d < bestDy) {
                bestDy = d
                bestY = target
                modeY = mode
                guideY = guide
            }
        }

        if (SnapMode.ALIGN in modes) {
            for (o in others) {
                if (o.width <= 0f || o.height <= 0f) continue
                tryX(o.left, SnapMode.ALIGN, o.left)
                tryX(o.right - width, SnapMode.ALIGN, o.right)
                tryX(o.left + o.width / 2f - width / 2f, SnapMode.ALIGN, o.left + o.width / 2f)
                tryY(o.top, SnapMode.ALIGN, o.top)
                tryY(o.bottom - height, SnapMode.ALIGN, o.bottom)
                tryY(o.top + o.height / 2f - height / 2f, SnapMode.ALIGN, o.top + o.height / 2f)
            }
        }

        if (SnapMode.EDGE in modes) {
            tryX(0f, SnapMode.EDGE, 0f)
            tryX(viewportWidth - width, SnapMode.EDGE, viewportWidth)
            tryX((viewportWidth - width) / 2f, SnapMode.EDGE, viewportWidth / 2f)
            tryY(0f, SnapMode.EDGE, 0f)
            tryY(viewportHeight - height, SnapMode.EDGE, viewportHeight)
            tryY((viewportHeight - height) / 2f, SnapMode.EDGE, viewportHeight / 2f)
        }

        if (SnapMode.GRID in modes && grid > 0f) {
            val gx = (x / grid).roundToInt() * grid
            val gy = (y / grid).roundToInt() * grid
            tryX(gx, SnapMode.GRID, gx)
            tryY(gy, SnapMode.GRID, gy)
        }

        return SnapResult(
            x = if (bestX.isNaN()) x else bestX,
            y = if (bestY.isNaN()) y else bestY,
            guideX = if (guideX.isNaN()) null else guideX,
            guideY = if (guideY.isNaN()) null else guideY,
            mode = modeX ?: modeY,
        )
    }
}
