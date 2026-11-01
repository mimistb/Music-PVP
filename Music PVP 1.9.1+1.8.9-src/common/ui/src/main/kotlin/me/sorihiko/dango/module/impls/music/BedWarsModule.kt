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
import me.sorihiko.dango.module.values.StringValue

/**
 * 床提醒 + Hypixel 统计的设置项。
 *
 * 这个模块本身不画任何东西：设置值同步到 [BedWarsData]，由平台层驱动
 * bedwars.BedAlertModule 与 Hypixel 客户端；显示复用灵动岛 / 敌人状态卡。
 *
 * 下面所有参数都由 [onTick] 每 tick 镜像到 [BedWarsData]（只在值变化时写入），
 * 平台层与 HUD 只读 BedWarsData 里的同名字段。
 */
@ModuleMeta(Category.MUSIC, "BedWars")
class BedWarsModule : Module() {

    @ModuleValue("床提醒", "敌人靠近自己床时在灵动岛提示")
    private val bedAlerts = BooleanValue(true)

    @ModuleValue("提醒半径", "3 ~ 32 格")
    private val bedRadius = IntValue(3, 32, 8)

    @ModuleValue("床提醒模式", "0 严格（仅 Hypixel） / 1 通用")
    private val bedMode = IntValue(0, 1, 0)

    @ModuleValue("Hypixel 统计", "总开关；关闭后不再自动请求，手动查询仍可用")
    private val hypStats = BooleanValue(true)

    @ModuleValue("自动查询对手", "自动为 TAB 名单里的玩家请求统计")
    private val hypAutoQuery = BooleanValue(true)

    @ModuleValue("统计缓存分钟", "5 ~ 360")
    private val hypCacheMinutes = IntValue(5, 360, 60)

    @ModuleValue("统计样式", "0 紧凑 / 1 Lunar / 2 详细")
    private val hypStyle = IntValue(0, 2, 1)

    @ModuleValue("统计小数位", "0 ~ 2")
    private val hypDecimals = IntValue(0, 2, 1)

    @ModuleValue("显示等级星", "在统计里显示等级星")
    private val hypStars = BooleanValue(true)

    @ModuleValue("显示 FKDR", "在统计里显示 FKDR")
    private val hypFkdr = BooleanValue(true)

    @ModuleValue("显示 WLR", "在统计里显示 WLR")
    private val hypWlr = BooleanValue(true)

    @ModuleValue("显示 BB", "在统计里显示破坏床数")
    private val hypBblr = BooleanValue(true)

    @ModuleValue("显示连胜", "在统计里显示连胜")
    private val hypWinStreak = BooleanValue(true)

    @ModuleValue("显示胜场", "在统计里显示胜场")
    private val hypWins = BooleanValue(true)

    @ModuleValue("显示网络等级", "在统计里显示网络等级")
    private val hypNetLevel = BooleanValue(true)

    @ModuleValue("官方 API Key", "可选；api.hypixel.net 的 Key，留空则跳过")
    private val hypixelKey = StringValue("")

    @ModuleValue("第三方 API Key（NetherAPI）", "可选；netherapi.com 的 Key，留空则跳过")
    private val netherApiKey = StringValue("")

    @ModuleValue("查询玩家", "输入名字后按回车查询 Bed Wars 统计")
    private val queryName = StringValue("")

    val alertsEnabled: Boolean get() = bedAlerts.get()
    val alertsRadius: Int get() = bedRadius.value
    val lastQuery: String get() = queryName.get()
    val officialKey: String get() = hypixelKey.get()
    val netherKey: String get() = netherApiKey.get()

    override fun init() {
        instance = this
    }

    @EventHandler
    fun onTick(e: TickEvent) {
        if (BedWarsData.bedMode != bedMode.value) BedWarsData.bedMode = bedMode.value
        if (BedWarsData.hypStats != hypStats.get()) BedWarsData.hypStats = hypStats.get()
        if (BedWarsData.hypAutoQuery != hypAutoQuery.get()) BedWarsData.hypAutoQuery = hypAutoQuery.get()
        if (BedWarsData.hypCacheMinutes != hypCacheMinutes.value) BedWarsData.hypCacheMinutes = hypCacheMinutes.value
        if (BedWarsData.hypStyle != hypStyle.value) BedWarsData.hypStyle = hypStyle.value
        if (BedWarsData.hypDecimals != hypDecimals.value) BedWarsData.hypDecimals = hypDecimals.value
        if (BedWarsData.hypStars != hypStars.get()) BedWarsData.hypStars = hypStars.get()
        if (BedWarsData.hypFkdr != hypFkdr.get()) BedWarsData.hypFkdr = hypFkdr.get()
        if (BedWarsData.hypWlr != hypWlr.get()) BedWarsData.hypWlr = hypWlr.get()
        if (BedWarsData.hypBblr != hypBblr.get()) BedWarsData.hypBblr = hypBblr.get()
        if (BedWarsData.hypWinStreak != hypWinStreak.get()) BedWarsData.hypWinStreak = hypWinStreak.get()
        if (BedWarsData.hypWins != hypWins.get()) BedWarsData.hypWins = hypWins.get()
        if (BedWarsData.hypNetLevel != hypNetLevel.get()) BedWarsData.hypNetLevel = hypNetLevel.get()
    }

    companion object {
        @Volatile
        private var instance: BedWarsModule? = null

        fun of(): BedWarsModule? = instance
    }
}
