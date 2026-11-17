package me.sorihiko.dango.utils

/**
 * 永久亮度的 gamma 保护逻辑（纯函数，不依赖 Minecraft，方便无头验证）。
 *
 * 原实现在开启时无条件记下 [net.minecraft.client.Minecraft.gameSettings.gammaSetting]，
 * 关闭时再无条件写回去。一旦"开启前的亮度"本身就已经是 100（上一局没还原、用户自己把
 * 滑条滑到顶、或者别的光影改过），关闭时就会把这个 100 当成用户原值写回去 ——
 * 亮度被永久锁死在上限，表现就是亮度滑条失效 / 关闭永久亮度后依然全亮。
 */
object GammaGuard {

    /** 永久亮度写进 gammaSetting 的值 */
    const val FULL_GAMMA = 100f

    /** 原版亮度滑条的上限：没有记录到有效旧值时退回到这里，避免把 100 留给玩家 */
    const val VANILLA_MAX_GAMMA = 1f

    private const val EPS = 1.0e-3f

    /** 当前亮度是否已经处于上限（是的话就不该被当成"待还原的原亮度"） */
    @JvmStatic
    fun isFull(current: Float): Boolean = kotlin.math.abs(current - FULL_GAMMA) < EPS

    /** 开启时该记住的原亮度：已经是上限时原样返回，等价于"不记录" */
    @JvmStatic
    fun rememberedGamma(current: Float): Float = if (isFull(current)) FULL_GAMMA else current

    /**
     * 关闭永久亮度 / 保存设置前该写回的亮度。
     *
     * - 当前值已经不是我们写的上限：说明用户调过滑条或别的光影改过，保留他们的新值；
     * - 仍是上限且有有效旧值：还原成旧值；
     * - 仍是上限但没有旧值（开启前就已经是 100，历史残留）：退回原版上限，
     *   不能继续留在 100，否则关闭功能后亮度依然锁死。
     */
    @JvmStatic
    fun restoreValue(current: Float, oldGamma: Float, hasOld: Boolean): Float = when {
        !isFull(current) -> current
        hasOld -> oldGamma
        else -> VANILLA_MAX_GAMMA
    }
}
