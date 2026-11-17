package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.TickEvent
import me.sorihiko.dango.hud.BedWarsData
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue

/**
 * 名牌上方统计（BedWars / SkyWars 星级）。
 *
 * 这个模块本身不画任何东西：设置值镜像到 [BedWarsData]，由平台层的
 * NametagStatsRenderer 在原版名牌上方补绘一行；数据复用 Hypixel 统计缓存。
 *
 * 显示规则（与 HyInfo 一致，同一时刻只显示一行）：
 * - BedWars 局内：BedWars 星级
 * - SkyWars 局内：SkyWars 星级
 * - 其它场景（Hypixel 大厅等）：网络等级
 */
@ModuleMeta(Category.MUSIC, "NametagStats")
class NametagStatsModule : Module() {

    @ModuleValue("显示自己", "第三人称下也给自己画一行（原版名牌不画自己）")
    private val showSelf = BooleanValue(false)

    @ModuleValue("高度偏移", "0 ~ 100，单位 0.01 格；0 表示紧贴名牌上方")
    private val heightOffset = IntValue(0, 100, 0)

    @ModuleValue("显示 BedWars 星级", "BedWars 局内在名牌上方显示星级")
    private val showBedwars = BooleanValue(true)

    @ModuleValue("显示 SkyWars 星级", "SkyWars 局内在名牌上方显示星级")
    private val showSkywars = BooleanValue(true)

    @ModuleValue("显示网络等级", "其它场景在名牌上方显示网络等级")
    private val showNetLevel = BooleanValue(true)

    override val defaultEnabled: Boolean get() = false

    override val enabledOnFreshConfig: Boolean get() = false

    override fun init() {
        instance = this
    }

    @EventHandler
    fun onTick(e: TickEvent) {
        if (BedWarsData.ntShowSelf != showSelf.get()) BedWarsData.ntShowSelf = showSelf.get()
        if (BedWarsData.ntHeightOffset != heightOffset.value) BedWarsData.ntHeightOffset = heightOffset.value
        if (BedWarsData.ntShowBedwars != showBedwars.get()) BedWarsData.ntShowBedwars = showBedwars.get()
        if (BedWarsData.ntShowSkywars != showSkywars.get()) BedWarsData.ntShowSkywars = showSkywars.get()
        if (BedWarsData.ntShowNetLevel != showNetLevel.get()) BedWarsData.ntShowNetLevel = showNetLevel.get()
    }

    companion object {
        @Volatile
        private var instance: NametagStatsModule? = null

        fun of(): NametagStatsModule? = instance
    }
}
