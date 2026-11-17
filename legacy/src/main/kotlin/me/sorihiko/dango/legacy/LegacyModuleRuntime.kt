package me.sorihiko.dango.legacy

/**
 * 8 个移植模块在 1.8.9 端的统一入口：ForgeBridge 只调这里的三个方法，
 * 每个模块自己的逻辑仍然放在独立的 Runtime 文件里。
 */
internal object LegacyModuleRuntime {

    /** 客户端 tick（20 Hz）：同步配置 / 发布 HUD 数据 */
    fun tick() {
        PotionStatusRuntime.tick()
        ScoreboardRuntime.tick()
        SpeedometerRuntime.tick()
        SlowSwingRuntime.tick()
        WeatherRuntime.tick()
        ProjectileTrailRuntime.tick()
    }

    /** RenderWorldLast：3D 轨迹预览 */
    fun onRender3D(partialTicks: Float) {
        ProjectileTrailRuntime.onRender3D(partialTicks)
    }

    /** HUD 之后：药水图标 / 背包物品 */
    fun onRenderOverlay() {
        LegacyHudOverlayRuntime.onRenderOverlay()
    }
}
