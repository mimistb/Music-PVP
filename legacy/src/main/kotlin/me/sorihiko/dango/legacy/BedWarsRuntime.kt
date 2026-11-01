package me.sorihiko.dango.legacy

import bedwars.BedAlertModule
import bedwars.BedMarker
import bedwars.MatchInput
import bedwars.MatchTracker
import bedwars.PlayerIdentity
import bedwars.Team
import bedwars.ThreatEntity
import bedwarsstats.BedWarsStats
import bedwarsstats.HypixelStatsClient
import bedwarsstats.RosterStatsService
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.hud.BedWarsData
import me.sorihiko.dango.module.impls.music.BedWarsModule
import me.sorihiko.dango.module.impls.music.TabStatsHudModule

import net.minecraft.block.Block
import net.minecraft.client.Minecraft
import net.minecraft.client.network.NetHandlerPlayClient
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemArmor
import net.minecraft.scoreboard.Scoreboard
import net.minecraft.scoreboard.ScorePlayerTeam
import net.minecraft.util.BlockPos
import net.minecraft.world.World

import java.util.Locale

/**
 * 1.8.9 的床提醒 / Hypixel 统计取数层。
 *
 * 每个客户端 tick 组装 bedwars.MatchInput 交给 BedAlertModule，再把自己床 / 威胁
 * 以纯数据写进 BedWarsData；显示全部由灵动岛与敌人状态卡完成。
 * 这里不画任何东西，也不改动 bedwars / bedwarsstats 的算法。
 */
object BedWarsRuntime {

    private val mc: Minecraft get() = Minecraft.getMinecraft()

    private val bedAlerts = BedAlertModule()
    private val input = MatchInput()

    private val sidebar = ArrayList<String>()
    private val roster = ArrayList<PlayerIdentity>()
    private val armorTeams = ArrayList<Team>()
    private val entityList = ArrayList<ThreatEntity>()
    private val bedList = ArrayList<BedMarker>()
    private val bedCache = LinkedHashMap<Long, BedMarker>()

    /** uuid -> 解析好的名单身份；TAB 格式化名没变就直接复用，避免每 tick 重新解析。 */
    private val identities = HashMap<String, PlayerIdentity>()
    private val identityFormatted = HashMap<String, String>()

    private var lastWorld: World? = null
    private var lastDimension = Int.MIN_VALUE
    private var hadOwnBed = false

    private var nextScanAt = 0L
    /** 上一次同步给 BedAlertModule 的通用模式，避免每 tick 重复写。 */
    private var lastRelaxed = false
    /** 上一次写入 RosterStatsService 的缓存分钟数（-1 = 还没写过）。 */
    private var lastCacheMinutes = -1
    /** 上一次同步到统计服务的可选 Key。 */
    private var lastOfficialKey = ""
    private var lastNetherKey = ""
    private var statsMatchActive = false
    private val enqueued = HashSet<String>()
    /** 上一次发布的 TAB 名单；只有内容变化时才替换 BedWarsData.tabNames。 */
    private var publishedTabNames: List<String> = emptyList()

    @Volatile
    private var manualName = ""

    // ---------------------------------------------------------------- 统计管线

    private val statsClient: HypixelStatsClient by lazy {
        HypixelStatsClient(object : HypixelStatsClient.Listener {
            override fun onHypixelStatsChanged(client: HypixelStatsClient) {
                val state = when (client.getState()) {
                    HypixelStatsClient.State.LOADING -> BedWarsData.API_LOADING
                    HypixelStatsClient.State.READY -> BedWarsData.API_READY
                    HypixelStatsClient.State.ERROR -> BedWarsData.API_ERROR
                    else -> BedWarsData.API_IDLE
                }
                val message = if (client.getErrorMessage().isNotEmpty()) client.getErrorMessage()
                else client.getStatusMessage()
                val stats = client.getStats()
                val name = manualName
                onMainThread {
                    BedWarsData.apiState = state
                    BedWarsData.apiMessage = message
                    if (state == BedWarsData.API_READY) BedWarsData.putStats(name, stats)
                }
            }
        })
    }

    private val rosterService: RosterStatsService by lazy {
        RosterStatsService(object : RosterStatsService.Listener {
            override fun onStatsReady(playerName: String, teamPrefix: String, stats: BedWarsStats) {
                onMainThread { BedWarsData.putStats(playerName, stats) }
            }

            override fun onStatsFailed(playerName: String, reason: String) {
                // 自动名单失败不刷聊天栏、不覆盖手动查询状态
            }
        })
    }

    private fun onMainThread(block: () -> Unit) {
        try {
            Minecraft.getMinecraft().addScheduledTask(block)
        } catch (t: Throwable) {
            DangoLogger.warn("BedWars stats callback dropped: {}", t.toString())
        }
    }

    // ---------------------------------------------------------------- 每 tick

    fun tick() {
        val world = mc.theWorld
        val player = mc.thePlayer
        if (world == null || player == null) {
            if (lastWorld != null) {
                resetWorld()
                lastWorld = null
                lastDimension = Int.MIN_VALUE
            }
            return
        }

        val dimension = world.provider.dimensionId
        if (world !== lastWorld || dimension != lastDimension) {
            bedAlerts.onWorldChange()
            BedWarsData.clearMatch()
            clearSession()
            lastWorld = world
            lastDimension = dimension
        }

        syncSettings()
        handleQuery()

        // 打开任意 GUI 时停止 update（只保留查询请求的处理）
        if (mc.currentScreen != null) return

        val now = System.currentTimeMillis()
        scanBeds(world, player.posX, player.posY, player.posZ, now)
        fillInput(world, player, now)
        publishTabNames()

        try {
            bedAlerts.update(input)
        } catch (t: Throwable) {
            DangoLogger.error("BedWars update failed: {}", t)
            return
        }
        publish(now)
    }

    private fun resetWorld() {
        bedAlerts.onWorldChange()
        BedWarsData.clearMatch()
        clearSession()
    }

    private fun clearSession() {
        identities.clear()
        identityFormatted.clear()
        enqueued.clear()
        bedCache.clear()
        bedList.clear()
        nextScanAt = 0L
        hadOwnBed = false
        statsMatchActive = false
        publishedTabNames = emptyList()
        BedWarsData.tabNames = emptyArray()
        try {
            rosterService.setMatchActive(false)
        } catch (t: Throwable) {
            DangoLogger.warn("BedWars roster reset failed: {}", t.toString())
        }
    }

    private fun syncSettings() {
        val module = BedWarsModule.of()
        val enabled = module?.alertsEnabled ?: true
        val radius = module?.alertsRadius ?: 8
        bedAlerts.setEnabled(enabled)
        bedAlerts.setRadius(radius)

        // 床提醒模式：1 = 通用（任何服务器），0 = 严格（仅 Hypixel）
        val relaxed = BedWarsData.bedMode == 1
        if (relaxed != lastRelaxed) {
            lastRelaxed = relaxed
            bedAlerts.setRelaxed(relaxed)
        }

        // 统计缓存分钟数 -> RosterStatsService 的缓存有效期（只在变化时写）
        val cacheMinutes = BedWarsData.hypCacheMinutes
        if (cacheMinutes != lastCacheMinutes) {
            lastCacheMinutes = cacheMinutes
            try {
                rosterService.setCacheTtlMillis(cacheMinutes * 60_000L)
            } catch (t: Throwable) {
                DangoLogger.warn("BedWars cache ttl sync failed: {}", t.toString())
            }
        }

        // 可选 API Key：只在变化时写入，避免每 tick 重复赋值
        val officialKey = module?.officialKey ?: ""
        val netherKey = module?.netherKey ?: ""
        if (officialKey != lastOfficialKey || netherKey != lastNetherKey) {
            lastOfficialKey = officialKey
            lastNetherKey = netherKey
            try {
                statsClient.setApiKeys(officialKey, netherKey)
                rosterService.setApiKeys(officialKey, netherKey)
                rosterService.reloadConfiguration(System.currentTimeMillis())
            } catch (t: Throwable) {
                DangoLogger.warn("BedWars api key sync failed: {}", t.toString())
            }
        }
    }

    private fun handleQuery() {
        val request = BedWarsData.queryRequest ?: return
        BedWarsData.queryRequest = null
        val name = request.trim()
        if (name.isEmpty()) return
        manualName = name
        BedWarsData.apiState = BedWarsData.API_LOADING
        BedWarsData.apiMessage = "正在查询 $name…"
        statsClient.query(name, System.currentTimeMillis())
    }

    // ---------------------------------------------------------------- 组装输入

    private fun fillInput(world: World, player: EntityPlayer, now: Long) {
        readSidebar(world.scoreboard, sidebar)
        readRoster(player, roster)
        buildEntities(world)
        val localArmor = armorTeamOf(player)

        input.nowMillis = now
        input.sidebarLines = sidebar
        input.roster = roster
        input.localPlayerName = player.name
        input.localArmorTeam = localArmor
        input.armorTeamMask = MatchTracker.armorMask(localArmor, armorTeams)
        input.localEntityId = player.entityId
        input.playerX = player.posX
        input.playerY = player.posY
        input.playerZ = player.posZ
        input.beds = bedList
        input.entities = entityList
    }

    private fun readSidebar(scoreboard: Scoreboard, out: MutableList<String>) {
        out.clear()
        val objective = scoreboard.getObjectiveInDisplaySlot(1) ?: return
        for (score in scoreboard.getSortedScores(objective)) {
            val name = score.playerName
            val team = scoreboard.getPlayersTeam(name)
            out.add(if (team != null) ScorePlayerTeam.formatPlayerName(team, name) else name)
        }
    }

    private fun readRoster(player: EntityPlayer, out: MutableList<PlayerIdentity>) {
        out.clear()
        val netHandler: NetHandlerPlayClient? = mc.netHandler
        if (netHandler == null) return
        for (info in netHandler.getPlayerInfoMap()) {
            val profile = info.gameProfile ?: continue
            val raw = profile.name ?: continue
            val uuid = profile.id?.toString()?.replace("-", "") ?: ""
            val formatted = info.displayName?.formattedText ?: raw
            val cached = identities[uuid]
            if (cached != null && identityFormatted[uuid] == formatted) {
                out.add(cached)
            } else {
                val identity = PlayerIdentity.fromFormattedName(uuid, raw, formatted)
                identities[uuid] = identity
                identityFormatted[uuid] = formatted
                out.add(identity)
            }
        }
        if (identities.size > 256) {
            identities.clear()
            identityFormatted.clear()
        }
    }

    private fun buildEntities(world: World) {
        entityList.clear()
        armorTeams.clear()
        for (obj in world.loadedEntityList) {
            val entity = obj as? EntityPlayer ?: continue
            val uuid = try {
                entity.gameProfile.id?.toString()?.replace("-", "") ?: ""
            } catch (t: Throwable) {
                ""
            }
            entityList.add(
                ThreatEntity(
                    entity.entityId,
                    entity.posX, entity.posY, entity.posZ,
                    uuid, entity.name, true, entity.isInvisible,
                )
            )
            armorTeams.add(armorTeamOf(entity))
        }
    }

    private fun armorTeamOf(player: EntityPlayer): Team {
        val stack = player.getEquipmentInSlot(3) ?: return Team.UNKNOWN
        val armor = stack.item as? ItemArmor ?: return Team.UNKNOWN
        if (!armor.hasColor(stack)) return Team.UNKNOWN
        return Team.fromLeatherRgb(armor.getColor(stack))
    }

    // ---------------------------------------------------------------- 床扫描

    private fun scanBeds(world: World, px: Double, py: Double, pz: Double, now: Long) {
        if (now < nextScanAt) return
        nextScanAt = now + SCAN_INTERVAL_MILLIS

        val x0 = Math.floor(px).toInt() - SCAN_RADIUS
        val x1 = Math.floor(px).toInt() + SCAN_RADIUS
        val z0 = Math.floor(pz).toInt() - SCAN_RADIUS
        val z1 = Math.floor(pz).toInt() + SCAN_RADIUS
        val y0 = (Math.floor(py).toInt() - SCAN_HEIGHT).coerceAtLeast(0)
        val y1 = (Math.floor(py).toInt() + SCAN_HEIGHT).coerceAtMost(255)

        val seen = HashSet<Long>()
        val pos = BlockPos.MutableBlockPos()
        for (x in x0..x1) {
            for (z in z0..z1) {
                for (y in y0..y1) {
                    pos.set(x, y, z)
                    if (!world.isBlockLoaded(pos)) continue
                    val state = world.getBlockState(pos)
                    val block = state.block
                    if (Block.getIdFromBlock(block) != 26) continue
                    val meta = block.getMetaFromState(state)
                    val marker = markerAt(x, y, z, meta) ?: continue
                    val key = keyOf(marker.x, marker.y, marker.z)
                    seen.add(key)
                    bedCache[key] = marker
                }
            }
        }

        // 扫描盒内本轮没再见到的床已消失；盒外的保留（区块卸载 / 距离太远，不能当成被拆）
        val iterator = bedCache.entries.iterator()
        while (iterator.hasNext()) {
            val bed = iterator.next().value
            val inside = bed.x in x0..x1 && bed.z in z0..z1 && bed.y in y0..y1
            if (inside && !seen.contains(keyOf(bed.x, bed.y, bed.z))) iterator.remove()
        }

        bedList.clear()
        bedList.addAll(bedCache.values)
    }

    /** meta bit 0x8 = HEAD；朝向低 2 位：0→footZ-1、1→footX+1、2→footZ+1、3→footX-1。 */
    private fun markerAt(x: Int, y: Int, z: Int, meta: Int): BedMarker? {
        val head = (meta and 0x8) != 0
        val dx = when (meta and 0x3) {
            1 -> 1
            3 -> -1
            else -> 0
        }
        val dz = when (meta and 0x3) {
            0 -> -1
            2 -> 1
            else -> 0
        }
        return if (head) {
            BedMarker(x, y, z, x + dx, z + dz, Team.UNKNOWN)
        } else {
            // 扫到的是脚部：头部在反方向，脚部就是当前方块
            BedMarker(x - dx, y, z - dz, x, z, Team.UNKNOWN)
        }
    }

    private fun keyOf(x: Int, y: Int, z: Int): Long {
        return (x.toLong() and 0x3FFFFFFL) shl 38 or
                ((z.toLong() and 0x3FFFFFFL) shl 12) or
                (y.toLong() and 0xFFFL)
    }

    // ---------------------------------------------------------------- 发布给 UI

    /**
     * 把 TAB 玩家列表（在场所有人的原始账号名）交给 UI。
     * 名单内容没变就不替换数组，UI 侧用引用比较避免每 tick 重建显示缓存。
     */
    private fun publishTabNames() {
        val out = ArrayList<String>(roster.size)
        for (identity in roster) {
            if (identity.name.isNotEmpty()) out.add(identity.name)
        }
        if (out == publishedTabNames) return
        publishedTabNames = out
        BedWarsData.tabNames = out.toTypedArray()
    }

    private fun publish(now: Long) {
        val active = bedAlerts.isMatchActive()
        val ownTeam = bedAlerts.getOwnTeam()
        val ownBed = bedAlerts.getOwnBed()
        val rosterSize = bedAlerts.roster().size

        if (!active) {
            hadOwnBed = false
            BedWarsData.clearMatch()
        } else {
            if (ownBed.known) {
                hadOwnBed = true
            }
            val broken = hadOwnBed && !ownBed.known

            BedWarsData.matchActive = true
            BedWarsData.ownTeam = ownTeam.index()
            BedWarsData.ownBedKnown = ownBed.known
            BedWarsData.ownBedBroken = broken
            BedWarsData.rosterCount = rosterSize

            val threats = bedAlerts.activeThreats()
            val count = threats.size
            BedWarsData.threatCount = count
            if (count > 0) {
                val top = threats[0]
                BedWarsData.topThreatName = top.playerName.ifEmpty { "?" }
                BedWarsData.topThreatTeam = top.team.index()
                BedWarsData.topThreatDistance = top.distance.toFloat()
                BedWarsData.topThreatInvisible = top.invisible
                BedWarsData.bedLine = formatThreatLine(top.playerName, top.distance)
                BedWarsData.bedLineTeam = top.team.index()
                BedWarsData.bedLineWarn = false
            } else {
                BedWarsData.topThreatName = ""
                BedWarsData.topThreatTeam = -1
                BedWarsData.topThreatDistance = 0f
                BedWarsData.topThreatInvisible = false
                when {
                    broken -> {
                        BedWarsData.bedLine = "自己床 已失守"
                        BedWarsData.bedLineTeam = -1
                        BedWarsData.bedLineWarn = true
                    }
                    ownBed.known -> {
                        BedWarsData.bedLine = "自己床 安全"
                        BedWarsData.bedLineTeam = -1
                        BedWarsData.bedLineWarn = false
                    }
                    else -> {
                        BedWarsData.bedLine = ""
                        BedWarsData.bedLineTeam = -1
                        BedWarsData.bedLineWarn = false
                    }
                }
            }
        }

        syncStats(active, ownTeam, now)
    }

    private fun formatThreatLine(name: String, distance: Double): String {
        val who = if (name.isEmpty()) "?" else name
        return "床威胁 $who " + String.format(Locale.ROOT, "%.1f", distance) + " 格"
    }

    /**
     * 统计查询入队。
     *
     * - 比赛确认后：自动为名单里的对手取数（原有行为，不含自己队）。
     * - 按住 TAB 时：为在场所有人取数（含自己队与大厅玩家）；
     *   队伍标签未知时按白色入队（管线只用队伍色做前缀，不影响查询）。
     *
     * 面板关闭且不在比赛时让管线停下（清队列），避免继续后台请求。
     */
    private fun syncStats(active: Boolean, ownTeam: Team, now: Long) {
        val tabActive = TabStatsHudModule.of()?.isOpen == true
        val working = active || tabActive
        if (working != statsMatchActive) {
            statsMatchActive = working
            rosterService.setMatchActive(working)
            if (!working) enqueued.clear()
        }
        if (!working) return
        // 统计总开关关闭 / 自动查询关闭时不自动入队（设置页的手动查询走 handleQuery，不受影响）
        if (!BedWarsData.hypStats || !BedWarsData.hypAutoQuery) return

        if (active) {
            for (identity in bedAlerts.roster()) {
                val team = identity.team()
                if (!team.known() || team == ownTeam) continue
                if (!enqueued.add(identity.name)) continue
                rosterService.enqueuePlayer(identity.name, team, now)
            }
        }
        if (tabActive) {
            for (identity in roster) {
                val name = identity.name
                if (name.isEmpty()) continue
                if (!enqueued.add(name)) continue
                val team = identity.team().let { if (it.known()) it else Team.WHITE }
                rosterService.enqueuePlayer(name, team, now)
            }
        }
    }

    private const val SCAN_INTERVAL_MILLIS = 2000L
    private const val SCAN_RADIUS = 40
    private const val SCAN_HEIGHT = 16
}
