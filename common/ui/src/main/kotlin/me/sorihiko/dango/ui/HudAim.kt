package me.sorihiko.dango.ui

/**
 * HUD 出场的统一节奏。
 *
 * 进入世界 / 从菜单返回后，所有 HUD 用同一个进度淡入并上滑，
 * 各自模块只读 [alpha]，不用再各维护一套动画状态。
 */
object HudReveal {
    private const val DURATION_MS = 240f

    private var startNanos = 0L
    private var inWorld = false

    /** 每 tick 调一次即可，内部只在"进世界"那一刻记录起点 */
    fun tick(nowInWorld: Boolean) {
        if (nowInWorld && !inWorld) startNanos = System.nanoTime()
        if (!nowInWorld) startNanos = 0L
        inWorld = nowInWorld
    }

    /** 0 → 1；刚进世界时从 0 开始，240ms 缓出到 1 */
    fun alpha(): Float {
        val start = startNanos
        if (start == 0L) return 1f
        val t = ((System.nanoTime() - start) / 1_000_000f) / DURATION_MS
        if (t >= 1f) return 1f
        val inv = 1f - t
        return 1f - inv * inv * inv
    }

    /** 出场时整体上滑的偏移（逻辑像素） */
    fun offsetY(): Float = (1f - alpha()) * 10f
}
