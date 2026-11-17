package me.sorihiko.dango.ui.glass

/**
 * 液态玻璃背景可用性判定（纯逻辑，无头可测）。
 *
 * 背景纹理没生成 / 已被释放 / 抓取失败时，着色器采样的是一张无效纹理，
 * OpenGL 规定这种情况下采样结果是 (0,0,0,1) —— 玻璃面板会直接变成纯黑。
 * 所以绘制前必须先过这一关，不可用时退回 [me.sorihiko.dango.ui.GlassStyle.fallback] 填充。
 */
object GlassBackdropPolicy {

    /**
     * @param ready 是否成功抓取过至少一帧背景
     * @param softTexture 轻度模糊背景纹理 id（未创建时为 -1 / 0）
     * @param frostTexture 重度模糊背景纹理 id（未创建时为 -1 / 0）
     */
    fun canSample(ready: Boolean, softTexture: Int, frostTexture: Int): Boolean =
        ready && softTexture > 0 && frostTexture > 0
}
