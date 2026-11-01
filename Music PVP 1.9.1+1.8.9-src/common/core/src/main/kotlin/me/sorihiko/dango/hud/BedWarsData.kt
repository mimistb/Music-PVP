package me.sorihiko.dango.hud

import bedwarsstats.BedWarsStats

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.8.9 平台层 → 共用 UI 的数据桥。
 *
 * 平台层每个客户端 tick 写入比赛态、床状态、威胁快照与 bedLine（拼好的显示文本）；
 * HUD 模块（灵动岛 / 敌人状态卡）只在这里读，不直接碰游戏对象、也不做字符串拼接。
 * 写入与读取都在 Minecraft 主线程（统计回调经 addScheduledTask 切回主线程），因此不需要锁。
 */
object BedWarsData {

    // ---------------------------------------------------------------- 比赛 / 床

    @Volatile
    var matchActive = false

    /** 自己队伍（bedwars.Team.index()，-1 = 未知）。 */
    @Volatile
    var ownTeam = -1

    @Volatile
    var ownBedKnown = false

    /** 比赛还在进行、曾经锁定过自己床但现在找不到了（视为被拆）。 */
    @Volatile
    var ownBedBroken = false

    @Volatile
    var rosterCount = 0

    /**
     * 在场所有人（TAB 玩家列表里的原始账号名，按名单顺序）。
     * 平台层每 tick 刷新；名单没变时复用同一个数组（UI 用引用比较判断要不要重建缓存）。
     */
    @Volatile
    var tabNames: Array<String> = emptyArray()

    // ---------------------------------------------------------------- 威胁快照（每 tick 覆盖）

    @Volatile
    var threatCount = 0

    @Volatile
    var topThreatName = ""

    /** 威胁玩家队伍（Team.index()，-1 = 未知）；UI 用原版 § 色表取色。 */
    @Volatile
    var topThreatTeam = -1

    @Volatile
    var topThreatDistance = 0f

    @Volatile
    var topThreatInvisible = false

    /** 灵动岛要显示的床/威胁行（已拼好，渲染时不再拼接）；空串表示不显示。 */
    @Volatile
    var bedLine = ""

    /** bedLine 里需要上色的队伍（-1 = 用普通字色）。 */
    @Volatile
    var bedLineTeam = -1

    /** bedLine 是否需要警示色（自己床被拆）。 */
    @Volatile
    var bedLineWarn = false

    // ---------------------------------------------------------------- 设置镜像（BedWarsModule 每 tick 写入）

    /** 床提醒模式：0 严格（仅 Hypixel） / 1 通用。 */
    @Volatile
    var bedMode = 0

    @Volatile
    var hypStats = true

    @Volatile
    var hypAutoQuery = true

    @Volatile
    var hypCacheMinutes = 60

    /** 统计样式：0 紧凑 / 1 Lunar / 2 详细。 */
    @Volatile
    var hypStyle = 1

    @Volatile
    var hypDecimals = 1

    @Volatile
    var hypStars = true

    @Volatile
    var hypFkdr = true

    @Volatile
    var hypWlr = true

    @Volatile
    var hypBblr = true

    @Volatile
    var hypWinStreak = true

    @Volatile
    var hypWins = true

    @Volatile
    var hypNetLevel = true

    /** 待执行的手动查询（设置页回车时写入，平台层取走后置空）。 */
    @Volatile
    var queryRequest: String? = null

    // ---------------------------------------------------------------- Hypixel 查询状态

    const val API_IDLE = 0
    const val API_LOADING = 1
    const val API_READY = 2
    const val API_ERROR = 3

    @Volatile
    var apiState = API_IDLE

    @Volatile
    var apiMessage = ""

    // ---------------------------------------------------------------- 统计缓存

    /** key 是玩家名的小写形式。 */
    private val statsByName = ConcurrentHashMap<String, BedWarsStats>()

    /** 每次写入自增，TAB 统计列表据此判断要不要重建显示字符串。 */
    @Volatile
    var statsVersion = 0
        private set

    fun putStats(name: String?, stats: BedWarsStats?) {
        if (name.isNullOrBlank() || stats == null) return
        statsByName[name.trim().lowercase(Locale.ROOT)] = stats
        statsVersion++
    }

    fun statsFor(name: String?): BedWarsStats? {
        if (name.isNullOrBlank()) return null
        return statsByName[name.trim().lowercase(Locale.ROOT)]
    }

    fun clearStats() {
        statsByName.clear()
        statsVersion++
    }

    /** 世界切换：清比赛态、床、威胁与行文本；统计缓存跨局保留（6 小时缓存策略由管线自己负责）。 */
    fun clearMatch() {
        matchActive = false
        ownTeam = -1
        ownBedKnown = false
        ownBedBroken = false
        rosterCount = 0
        threatCount = 0
        topThreatName = ""
        topThreatTeam = -1
        topThreatDistance = 0f
        topThreatInvisible = false
        bedLine = ""
        bedLineTeam = -1
        bedLineWarn = false
    }

    /** 原版 § 队伍色（0xRRGGBB）；未知返回白色。 */
    fun teamRgb(index: Int): Int {
        return when (index) {
            0 -> 0xFF5555  // 红  §c
            1 -> 0x5555FF  // 蓝  §9
            2 -> 0x55FF55  // 绿  §a
            3 -> 0xFFFF55  // 黄  §e
            4 -> 0x55FFFF  // 青  §b
            5 -> 0xFFFFFF  // 白  §f
            6 -> 0xFF55FF  // 粉  §d
            7 -> 0xAAAAAA  // 灰  §7
            else -> 0xFFFFFF
        }
    }
}
