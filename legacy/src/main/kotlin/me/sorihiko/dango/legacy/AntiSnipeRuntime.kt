package me.sorihiko.dango.legacy

import bedwarsstats.BedWarsStats
import bedwarsstats.HypixelStatsClient
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.hud.AntiCheatFlags
import me.sorihiko.dango.hud.BedWarsData
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.impls.music.AntiCheatModule
import me.sorihiko.dango.module.impls.music.AutoBlacklistModule
import me.sorihiko.dango.module.impls.music.BedWarsModule
import me.sorihiko.dango.module.impls.music.SniperWarningModule
import me.sorihiko.dango.module.values.KeyBindValue

import net.minecraft.client.Minecraft
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.event.ClickEvent
import net.minecraft.event.HoverEvent
import net.minecraft.item.Item
import net.minecraft.item.ItemArmor
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemBucketMilk
import net.minecraft.item.ItemFood
import net.minecraft.item.ItemPotion
import net.minecraft.item.ItemStack
import net.minecraft.item.ItemSword
import net.minecraft.network.play.client.C01PacketChatMessage
import net.minecraft.potion.Potion
import net.minecraft.util.ChatComponentText
import net.minecraft.util.ChatStyle
import net.minecraft.util.EnumChatFormatting
import net.minecraft.world.World

import java.util.Locale
import java.util.UUID

/**
 * 反狙击三件套的平台层实现，移植自 Meowtils 的 antisnipe 包。
 *
 * 检测阈值、检查顺序、状态机与提示文案均与原模组一致，只有数据来源做了适配：
 * 队友判断用计分板队伍 / 皮革盔甲同色 / 名字同色；统计用项目自己的 HypixelStatsClient；
 * 配置项在 common/ui 的 AntiCheatModule / AutoBlacklistModule / SniperWarningModule。
 */
internal object AntiSnipeRuntime {

    /** 项目没有可配置聊天前缀，这里固定用客户端名 */
    private const val PREFIX = "§7[§bMusic§f PVP§7] §f"
    private const val PING_SOUND = "random.orb"

    private val mc: Minecraft get() = Minecraft.getMinecraft()

    private val anticheatData = HashMap<UUID, AntiCheatData>()
    private val violationLevels = HashMap<String, HashMap<String, Int>>()

    private val sniperAlerted = HashMap<String, Boolean>()
    private val sniperNames = arrayOf(
        "mcalt_", "mcalts_", "hassalt_", "dogalt_", "mal_", "bym_", "jy6_", "lf_", "wg_",
        "ggnekito", "dahai_", "tzi", "nicegen", "opalalts", "msmc", "myau", "vape", "snipe",
        "nicealts", "rave", "alt", "client", "hack", "hax", "fernan", "watchdog", "anticheat"
    )
    private var tickCounter = 0

    private val statsQueryQueue = ArrayDeque<String>()
    private val statsPrintPending = HashSet<String>()
    private var statsQueryName: String? = null
    private var statsClient: HypixelStatsClient? = null

    private var lastWorld: World? = null

    private var antiCheatKeyHeld = false
    private var autoBlacklistKeyHeld = false
    private var sniperKeyHeld = false
    /** 已提示过「找不到模块实例」的模块名，同一个模块只记一次日志 */
    private val moduleMissingWarned = HashSet<String>()

    /** 每客户端 tick 调用（ForgeBridge 里紧跟在 LegacyModuleRuntime.tick 之后） */
    fun tick() {
        try {
            val world = mc.theWorld
            val self = mc.thePlayer
            if (world == null || self == null) {
                lastWorld = null
                return
            }
            if (world !== lastWorld) {
                lastWorld = world
                resetAll()
            }
            pollToggleKeys()
            tickAntiCheat(world, self)
            tickSniper(world, self)
            drainStatsQueue()
        } catch (t: Throwable) {
            DangoLogger.error("AntiSnipe tick failed", t)
        }
    }

    /** 模块开关按键（原模组的 key 配置项）：边沿触发，界面打开时不响应 */
    private fun pollToggleKeys() {
        val antiCheat = antiCheatModule()
        antiCheatKeyHeld = pollToggleKey(antiCheat, antiCheat?.toggleKey, antiCheatKeyHeld)
        val autoBlacklist = autoBlacklistModule()
        autoBlacklistKeyHeld = pollToggleKey(autoBlacklist, autoBlacklist?.toggleKey, autoBlacklistKeyHeld)
        val sniper = sniperModule()
        sniperKeyHeld = pollToggleKey(sniper, sniper?.toggleKey, sniperKeyHeld)
    }

    /**
     * 模块实例解析：优先用 init() 登记的静态单例，缺失时回退到模块注册表。
     *
     * 只认静态单例时，只要模块加载顺序出现异常（某个更早的模块 init 抛错中断了
     * ModuleManager.load 的循环），反作弊三件套就永远拿不到实例、检测流程静默空转。
     * 回退查询能保证「模块对象存在就一定能跑检测」。
     */
    private fun <T : Module> resolveModule(primary: T?, type: Class<T>, label: String): T? {
        if (primary != null) return primary
        val found = try {
            DangoMusic.instance.moduleManager.get(type)
        } catch (t: Throwable) {
            null
        }
        if (found == null && moduleMissingWarned.add(label)) {
            DangoLogger.warn("{} module instance not found, feature disabled", label)
        }
        return found
    }

    private fun antiCheatModule(): AntiCheatModule? =
        resolveModule(AntiCheatModule.of(), AntiCheatModule::class.java, "AntiCheat")

    private fun autoBlacklistModule(): AutoBlacklistModule? =
        resolveModule(AutoBlacklistModule.of(), AutoBlacklistModule::class.java, "AutoBlacklist")

    private fun sniperModule(): SniperWarningModule? =
        resolveModule(SniperWarningModule.of(), SniperWarningModule::class.java, "SniperWarning")

    private fun pollToggleKey(module: Module?, key: KeyBindValue?, held: Boolean): Boolean {
        if (module == null || key == null) return false
        val down = try {
            key.isDown(DangoMusic.instance.platform)
        } catch (t: Throwable) {
            false
        }
        if (down && !held && mc.currentScreen == null) {
            module.enabled = !module.enabled
        }
        return down
    }

    /**
     * 在 GuiScreen#sendChatMessage 的 ASM 钩子里调用。返回 true 表示已接管（原命令不再发送）。
     * /report 与 /wdr 会被转发给服务器，同时按 AutoBlacklist 的开关拉黑被举报玩家。
     */
    fun onChatSend(message: String): Boolean {
        return try {
            handleReportCommand(message)
        } catch (t: Throwable) {
            DangoLogger.error("AntiSnipe command hook failed", t)
            false
        }
    }

    private fun resetAll() {
        anticheatData.clear()
        violationLevels.clear()
        sniperAlerted.clear()
        // 换局后上一局的作弊标记不应继续把别人标红
        AntiCheatFlags.clear()
        tickCounter = 0
        statsQueryQueue.clear()
        statsPrintPending.clear()
        statsQueryName = null
    }

    // ---------------------------------------------------------------- 反作弊

    private fun tickAntiCheat(world: World, self: EntityPlayer) {
        val module = antiCheatModule() ?: return
        if (!module.enabled) return
        for (player in world.playerEntities) {
            if (player === self) continue
            val name = player.name ?: continue
            if (isTeammate(player)) continue
            val data = anticheatData.getOrPut(player.uniqueID) { AntiCheatData() }
            data.check(player, module)

            if (data.failedAutoBlock() && incrementViolation(name, "AutoBlock", module.violationLevel.value)) {
                sendFlagMessage(name, "AutoBlock", module)
                data.autoBlock.reset()
                blacklist(name, "autoblock", module.autoBlock.get())
            }
            if (data.failedNoSlow() && incrementViolation(name, "NoSlow", module.violationLevel.value)) {
                sendFlagMessage(name, "NoSlow", module)
                data.noSlow.reset()
                blacklist(name, "noslow", module.noSlow.get())
            }
            if (data.failedLegitScaffold() && incrementViolation(name, "Legit Scaffold", module.violationLevel.value)) {
                sendFlagMessage(name, "Legit Scaffold", module)
                data.legitScaffold.reset()
                blacklist(name, "legit scaffold", module.legitScaffold.get())
            }
            if (data.failedKillaura() && incrementViolation(name, "Killaura", module.violationLevel.value)) {
                sendFlagMessage(name, "Killaura", module)
                data.killaura.reset()
                blacklist(name, "killaura", module.killaura.get())
            }
        }
    }

    private fun incrementViolation(playerName: String, checkType: String, threshold: Int): Boolean {
        val levels = violationLevels.getOrPut(playerName) { HashMap() }
        val next = (levels[checkType] ?: 0) + 1
        levels[checkType] = next
        if (next >= threshold) {
            levels[checkType] = 0
            return true
        }
        return false
    }

    private fun blacklist(playerName: String, reason: String, flagEnabled: Boolean) {
        val module = autoBlacklistModule() ?: return
        if (!module.enabled || !module.forFlags.get() || !flagEnabled) return
        blacklistPlayer(playerName, reason)
    }

    /**
     * 作弊提示：只写本地聊天框。
     *
     * 全部走 {@link EntityPlayer#addChatMessage}（纯客户端），不会经过
     * sendChatMessage / 网络层，因此不会向服务器发任何请求，
     * 也不会触发服务器端的自动封禁逻辑。WDR 按钮同样只是本地 ClickEvent，
     * 必须由玩家自己点击才会执行命令。
     */
    private fun sendFlagMessage(playerName: String, checkType: String, module: AntiCheatModule) {
        // 登记到共享标记表：TabStats 数据区据此把该玩家整行标红
        AntiCheatFlags.mark(playerName)
        val text = displayName(playerName) + EnumChatFormatting.GRAY + " failed " +
            colorOf(module.componentColorName.get()) + checkType
        if (module.wdrButton.get()) {
            val message = ChatComponentText(PREFIX + text + " ")
            val bracket = colorOf(module.bracketColorName.get())
            val button = ChatComponentText(
                bracket.toString() + "[" + colorOf(module.buttonColorName.get()) + "WDR" + bracket + "]"
            )
            button.chatStyle = ChatStyle()
                .setChatClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/wdr $playerName"))
                .setChatHoverEvent(
                    HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        ChatComponentText(EnumChatFormatting.DARK_AQUA.toString() + "Click to report this player.")
                    )
                )
            message.appendSibling(button)
            mc.thePlayer?.addChatMessage(message)
        } else {
            chat(PREFIX + text)
        }
        if (module.flagSound.get()) playSound(PING_SOUND, 1.0f, 1.0f)
    }

    private fun colorOf(name: String): EnumChatFormatting {
        return when (name.lowercase(Locale.ROOT)) {
            "black" -> EnumChatFormatting.BLACK
            "dark_blue" -> EnumChatFormatting.DARK_BLUE
            "dark_green" -> EnumChatFormatting.DARK_GREEN
            "dark_aqua" -> EnumChatFormatting.DARK_AQUA
            "dark_red" -> EnumChatFormatting.DARK_RED
            "dark_purple" -> EnumChatFormatting.DARK_PURPLE
            "gold" -> EnumChatFormatting.GOLD
            "gray" -> EnumChatFormatting.GRAY
            "dark_gray" -> EnumChatFormatting.DARK_GRAY
            "blue" -> EnumChatFormatting.BLUE
            "green" -> EnumChatFormatting.GREEN
            "aqua" -> EnumChatFormatting.AQUA
            "red" -> EnumChatFormatting.RED
            "light_purple" -> EnumChatFormatting.LIGHT_PURPLE
            "yellow" -> EnumChatFormatting.YELLOW
            "white" -> EnumChatFormatting.WHITE
            else -> EnumChatFormatting.WHITE
        }
    }

    private class AntiCheatData {
        val autoBlock = AutoBlockCheck()
        val noSlow = NoSlowCheck()
        val legitScaffold = LegitScaffoldCheck()
        val killaura = KillauraCheck()
        private var lastUuid: UUID? = null

        fun check(player: EntityPlayer, module: AntiCheatModule) {
            lastUuid = player.uniqueID
            autoBlock.check(player, module.autoBlock.get())
            noSlow.check(player, module.noSlow.get())
            legitScaffold.check(player, module.legitScaffold.get())
            killaura.check(player, module.killaura.get())
        }

        fun failedAutoBlock(): Boolean = autoBlock.failed()
        fun failedNoSlow(): Boolean = noSlow.failed()
        fun failedLegitScaffold(): Boolean = lastUuid != null && legitScaffold.failed()
        fun failedKillaura(): Boolean = killaura.failed()
    }

    /**
     * 远端玩家「正在右键使用物品」的等价判据。
     *
     * 1.8.9 客户端没有专门同步 itemInUse，EntityOtherPlayerMP 是靠同步过来的
     * isEating() 标志（服务端在任意物品开始使用时置位）在下一 tick 才补出 itemInUse；
     * 直接依赖 isUsingItem() 会漏掉刚开始使用的这一 tick，部分场景下 itemInUse
     * 甚至不会被补上。两者取或可以覆盖这个空窗，保证检测能真正跑到。
     */
    private fun isUsingItemEntity(player: EntityPlayer): Boolean =
        player.isUsingItem || player.isEating

    /** 边挥砍边格挡（AutoBlock / Blink 类自动格挡），连续 10 tick 以上 */
    private class AutoBlockCheck {
        private var autoBlockTicks = 0

        fun check(player: EntityPlayer, enabled: Boolean) {
            if (!enabled) {
                autoBlockTicks = 0
                return
            }
            // 1.8.9 对远端玩家的 isBlocking() 取决于 itemInUse，可能整段为 null；
            // 用「同步的食用标志 + 手持剑」等价判断正在格挡，判定口径与原模组一致。
            val blocking = player.heldItem?.item is ItemSword && isUsingItemEntity(player)
            autoBlockTicks = if (player.isSwingInProgress && blocking) autoBlockTicks + 1 else 0
        }

        fun failed(): Boolean = autoBlockTicks > 10

        fun reset() {
            autoBlockTicks = 0
        }
    }

    /**
     * 进食/喝药时仍在冲刺移动。
     *
     * 原实现 lastPosX / lastPosZ 从不更新，位移实际是「世界原点到玩家」，
     * 只要边吃边冲刺就必然超过阈值，判定与真实移动速度无关（纯误报源）。
     * 这里改为逐 tick 记录基准后算水平位移，并在换世界/首次采样时重建基准。
     */
    private class NoSlowCheck {
        private var noSlowTicks = 0
        private var lastPosX = 0.0
        private var lastPosZ = 0.0
        private var hasLastPos = false

        fun check(player: EntityPlayer, enabled: Boolean) {
            if (!enabled) {
                noSlowTicks = 0
                hasLastPos = false
                return
            }
            val deltaX = player.posX - lastPosX
            val deltaZ = player.posZ - lastPosZ
            lastPosX = player.posX
            lastPosZ = player.posZ
            // 首次采样 / 传送 / 换维度：没有有效位移，只更新基准
            if (!hasLastPos) {
                hasLastPos = true
                noSlowTicks = 0
                return
            }
            val speed = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ)
            if (player.isSprinting && isUsingItemEntity(player) && !player.isRiding) {
                var threshold = 0.05
                val speedEffect = player.getActivePotionEffect(Potion.moveSpeed)
                if (speedEffect != null) {
                    threshold *= 1.0 + 0.2 * (speedEffect.amplifier + 1)
                }
                noSlowTicks = if (speed > threshold) noSlowTicks + 1 else 0
            } else {
                noSlowTicks = 0
            }
        }

        fun failed(): Boolean = noSlowTicks > 20

        fun reset() {
            noSlowTicks = 0
        }
    }

    /** 快速蹲起 + 贴地放方块 + 挥击时机，最近三次蹲起都 ≤3 tick 才判定 */
    private class LegitScaffoldCheck {
        private var lastCrouchStart = -1L
        private var lastCrouchEnd = -1L
        private var wasSneaking = false
        private var wasSwinging = false
        private var lastSwingTick = Long.MIN_VALUE
        private val crouchDurations = ArrayList<Int>()
        private var lastFlagTick = 0L
        private var flagged = false

        fun check(player: EntityPlayer, enabled: Boolean) {
            if (!enabled) {
                // 关掉这条检查时清空状态，避免重新打开后继承上一次的跪/挥砍判定
                reset()
                return
            }
            val client = Minecraft.getMinecraft()
            if (player === client.thePlayer) return
            val tick = client.theWorld?.totalWorldTime ?: return
            trackCrouch(tick, player.isSneaking)
            // 原版读的是私有的 swingProgressInt == 1；这里用 isSwingInProgress 的上升沿 +1 tick 近似
            val swinging = player.isSwingInProgress
            if (swinging && !wasSwinging) lastSwingTick = tick + 1
            wasSwinging = swinging
            // 离开脚手架姿态后要把旧判定清掉，否则 flagged 会一直粘着上一次的结果
            if (isScaffold(player)) evaluate(tick) else flagged = false
        }

        private fun trackCrouch(tick: Long, currSneak: Boolean) {
            if (currSneak && !wasSneaking) {
                lastCrouchStart = tick
            } else if (!currSneak && wasSneaking) {
                val start = if (lastCrouchStart >= 0) lastCrouchStart else tick - 1
                lastCrouchEnd = tick
                crouchDurations.add(0, (tick - start).toInt())
                if (crouchDurations.size > 5) crouchDurations.removeAt(5)
            }
            wasSneaking = currSneak
        }

        private fun isScaffold(player: EntityPlayer): Boolean {
            // 1.8.9 的远端实体 onGround 不会随位置包更新（恒为 false），
            // 带上它会让整条检查永远不执行；这里只保留两个可靠信号：
            // 俯视 + 手持方块，落地与否交给后面的蹲下/挥砍时序约束。
            return player.rotationPitch >= 60.0f && player.heldItem?.item is ItemBlock
        }

        private fun evaluate(tick: Long) {
            if (lastCrouchStart < 0 || lastCrouchEnd < 0) {
                flagged = false
                return
            }
            val crouchDuration = (lastCrouchEnd - lastCrouchStart).toInt()
            val quickCrouch = crouchDuration in 1..2
            val swingTiming = lastSwingTick >= lastCrouchEnd &&
                lastSwingTick <= lastCrouchEnd + 3 && tick - lastSwingTick <= 10
            val consistent = crouchDurations.size >= 3 &&
                crouchDurations[0] <= 3 && crouchDurations[1] <= 3 && crouchDurations[2] <= 3
            if (quickCrouch && swingTiming && consistent && tick - lastFlagTick >= 60) {
                flagged = true
                lastFlagTick = tick
                return
            }
            flagged = false
        }

        fun failed(): Boolean = flagged

        fun reset() {
            flagged = false
            lastCrouchStart = -1L
            lastCrouchEnd = -1L
            wasSneaking = false
            wasSwinging = false
            lastSwingTick = Long.MIN_VALUE
            crouchDurations.clear()
            lastFlagTick = 0L
        }
    }

    /** 进食后立刻挥砍：使用 6 tick 以上、距上次进食 33 tick 内，累计 8 次 */
    private class KillauraCheck {
        private val useItemTicks = HashMap<UUID, Int>()
        /** 世界时间是 Long，这里必须用 Long 保存：原实现 toInt() 溢出后会算出负的间隔，全员误判 */
        private val lastEatTicks = HashMap<UUID, Long>()
        private val violationLevels = HashMap<UUID, Int>()
        private var failed = false

        fun check(player: EntityPlayer, enabled: Boolean) {
            if (!enabled) {
                failed = false
                useItemTicks.clear()
                lastEatTicks.clear()
                violationLevels.clear()
                return
            }
            val client = Minecraft.getMinecraft()
            if (player === client.thePlayer || player.ridingEntity != null) return
            val uuid = player.uniqueID
            val tick = client.theWorld?.totalWorldTime ?: return
            val heldItem = player.heldItem
            val using = isUsingItemEntity(player)
            val isConsumable = heldItem != null && isConsumable(heldItem.item)
            val isAttacking = player.isSwingInProgress

            var useTime = useItemTicks.getOrDefault(uuid, 0)
            if (using && isConsumable) {
                useTime += 1
                useItemTicks[uuid] = useTime
            } else {
                if (useTime > 0) lastEatTicks[uuid] = tick
                useItemTicks[uuid] = 0
            }

            // 没有进食记录时不能当成「刚吃完」：原实现用 0 兜底，世界时间一大就溢出成负数
            val lastEatTick = lastEatTicks[uuid]
            val sinceLastEat = if (lastEatTick == null) Long.MAX_VALUE else tick - lastEatTick
            if (isAttacking && useTime > 6 && sinceLastEat in 0L..32L && isConsumable) {
                val level = violationLevels.getOrDefault(uuid, 0) + 1
                violationLevels[uuid] = level
                if (level >= 8) failed = true
            } else {
                val level = violationLevels.getOrDefault(uuid, 0)
                if (level > 0) violationLevels[uuid] = level - 1
            }
        }

        private fun isConsumable(item: Item): Boolean {
            return item is ItemFood || item is ItemPotion || item is ItemBucketMilk
        }

        fun failed(): Boolean = failed

        fun reset() {
            failed = false
            useItemTicks.clear()
            lastEatTicks.clear()
            violationLevels.clear()
        }
    }

    // ---------------------------------------------------------------- 狙击预警

    private fun tickSniper(world: World, self: EntityPlayer) {
        val module = sniperModule() ?: return
        if (!module.enabled) return
        if (++tickCounter < 20) return
        tickCounter = 0
        if (module.checkGear.get() && BedWarsData.matchActive) {
            for (player in world.playerEntities) checkGear(player, self, module)
        }
        if (module.checkName.get()) {
            for (player in world.playerEntities) checkName(player, self, module)
        }
        if (module.checkStats.get() && BedWarsData.matchActive && BedWarsData.hypStats) {
            for (player in world.playerEntities) checkStats(player, self, module)
        }
    }

    private fun checkGear(player: EntityPlayer, self: EntityPlayer, module: SniperWarningModule) {
        if (player === self) return
        val name = player.name ?: return
        if (isTeammate(player)) return
        if (sniperAlerted.containsKey(name)) return

        var hasChain = false
        for (armorStack in player.inventory.armorInventory) {
            val armor = armorStack?.item as? ItemArmor ?: continue
            if (armor.armorMaterial == ItemArmor.ArmorMaterial.CHAIN) {
                hasChain = true
                break
            }
        }
        var hasIronSword = false
        val held = player.heldItem
        if (held?.item is ItemSword && (held.item as ItemSword).getToolMaterialName() == "IRON") {
            hasIronSword = true
        }
        if (hasChain && hasIronSword) {
            alert(name, "Iron Sword + Chainmail Armor", module)
            sniperAlerted[name] = true
        }
    }

    private fun checkName(player: EntityPlayer, self: EntityPlayer, module: SniperWarningModule) {
        if (player === self) return
        val name = player.name ?: return
        if (isTeammate(player)) return
        if (sniperAlerted.containsKey(name)) return
        val lower = name.lowercase(Locale.ROOT)
        for (sniper in sniperNames) {
            if (!lower.contains(sniper)) continue
            alert(name, "Name", module)
            sniperAlerted[name] = true
            return
        }
    }

    private fun checkStats(player: EntityPlayer, self: EntityPlayer, module: SniperWarningModule) {
        if (!BedWarsData.hypStats) return
        if (player === self) return
        val name = player.name ?: return
        if (isTeammate(player)) return
        if (isNicked(player)) return
        if (sniperAlerted.containsKey(name)) return

        val cached = BedWarsData.statsFor(name)
        if (cached != null) {
            evaluateStats(name, cached, module)
            return
        }
        if (statsQueryName == null && !statsQueryQueue.contains(name)) statsQueryQueue.add(name)
    }

    /** 原版第一条是 clutchRatio ≥ 0.5，本项目统计源没有该字段，只保留 fkdr 分支 */
    private fun evaluateStats(name: String, stats: BedWarsStats, module: SniperWarningModule) {
        if (sniperAlerted.containsKey(name)) return
        if (stats.fkdr() >= stats.finalKills * 0.1) {
            alert(name, "Stats", module)
            sniperAlerted[name] = true
        }
    }

    private fun alert(name: String, reason: String, module: SniperWarningModule) {
        chat(
            EnumChatFormatting.RED.toString() + "Warning: " + EnumChatFormatting.RESET +
                displayName(name) + EnumChatFormatting.GRAY + " might be a sniper! " +
                EnumChatFormatting.DARK_GRAY + "(" + EnumChatFormatting.WHITE + reason +
                EnumChatFormatting.DARK_GRAY + ")"
        )
        if (module.fetchStats.get()) {
            val cached = BedWarsData.statsFor(name)
            if (cached != null) {
                printStats(name, cached)
            } else {
                statsPrintPending.add(name)
                if (statsQueryName == null && !statsQueryQueue.contains(name)) statsQueryQueue.add(name)
            }
        }
        if (module.sound.get()) playSound(PING_SOUND, 1.0f, 0.2f)
    }

    private fun printStats(name: String, stats: BedWarsStats) {
        chat(
            EnumChatFormatting.GOLD.toString() + displayName(name) + EnumChatFormatting.GRAY + " · " +
                EnumChatFormatting.WHITE + stats.stars + "★" + EnumChatFormatting.GRAY + " · FKDR " +
                EnumChatFormatting.WHITE + format(stats.fkdr()) + EnumChatFormatting.GRAY + " · WLR " +
                EnumChatFormatting.WHITE + format(stats.wlr())
        )
    }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun isNicked(player: EntityPlayer): Boolean {
        val profile = player.gameProfile ?: return false
        return profile.id?.version() == 1
    }

    // ---------------------------------------------------------------- 统计查询（串行）

    private fun drainStatsQueue() {
        val client = client()
        if (statsQueryName != null || statsQueryQueue.isEmpty()) return
        val name = statsQueryQueue.removeFirst()
        statsQueryName = name
        val module = BedWarsModule.of()
        client.setApiKeys(module?.officialKey ?: "", module?.netherKey ?: "")
        client.query(name, 0L)
    }

    private fun client(): HypixelStatsClient {
        statsClient?.let { return it }
        val created = HypixelStatsClient { c ->
            val name = statsQueryName ?: return@HypixelStatsClient
            val state = c.state
            if (state == HypixelStatsClient.State.IDLE || state == HypixelStatsClient.State.LOADING) {
                return@HypixelStatsClient
            }
            val stats = c.stats
            mainThread {
                statsQueryName = null
                if (state == HypixelStatsClient.State.READY && stats != null) {
                    BedWarsData.putStats(name, stats)
                    afterStats(name, stats)
                }
            }
        }
        statsClient = created
        return created
    }

    private fun afterStats(name: String, stats: BedWarsStats) {
        val module = sniperModule()
        if (module != null && module.enabled && module.checkStats.get()) {
            evaluateStats(name, stats, module)
        }
        if (statsPrintPending.remove(name)) printStats(name, stats)
    }

    // ---------------------------------------------------------------- /report 与 /wdr

    private fun handleReportCommand(message: String): Boolean {
        val trimmed = message.trim()
        if (!trimmed.startsWith("/")) return false
        val parts = trimmed.substring(1).split(" ").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        val command = parts[0].lowercase(Locale.ROOT)
        if (command != "report" && command != "wdr" && command != "watchdogreport") return false
        val args = parts.drop(1)
        if (args.isEmpty()) return false

        val module = autoBlacklistModule()
        val blacklistEnabled = module != null && module.forReports.get() &&
            if (command == "report") module.whenReportCommand.get() else module.whenWdrCommand.get()

        val forwarded = if (command == "watchdogreport") {
            "/wdr " + args.joinToString(" ")
        } else {
            "/" + command + " " + args.joinToString(" ")
        }
        forwardToServer(forwarded)

        if (blacklistEnabled) {
            val reasons = if (args.size > 1) {
                BlacklistStore.formatReasons(args.drop(1).toTypedArray())
            } else {
                "cheating"
            }
            blacklistPlayer(args[0], reasons)
        }
        return true
    }

    private fun forwardToServer(command: String) {
        mc.netHandler?.addToSendQueue(C01PacketChatMessage(command))
    }

    private fun blacklistPlayer(player: String, reasons: String) {
        MojangNameLookup.lookup(player) { uuid ->
            val key = uuid ?: player
            if (!BlacklistStore.isBlacklisted(key)) {
                // 回调可能在异步线程：这里只读静态单例，不走注册表查询
                if (AutoBlacklistModule.of()?.showNotifications?.get() == true) {
                    chat(
                        EnumChatFormatting.RED.toString() + "Auto-blacklisted " +
                            EnumChatFormatting.RESET + displayName(player) + EnumChatFormatting.RED + "."
                    )
                }
            }
            BlacklistStore.appendReason(key, reasons)
        }
    }

    // ---------------------------------------------------------------- 公共小工具

    /** 队友：创造模式 / 原版同队判定 / BedWars 皮革盔甲同色 / 计分板名字主色相同 */
    private fun isTeammate(player: EntityPlayer): Boolean {
        val self = mc.thePlayer ?: return false
        if (player === self) return false
        if (player.capabilities.isCreativeMode) return true
        try {
            if (self.isOnSameTeam(player)) return true
            if (BedWarsData.matchActive && sameArmorColor(self, player)) return true
            val selfColor = mostFrequentColor(teamFormatted(self))
            val targetColor = mostFrequentColor(teamFormatted(player))
            if (selfColor != null && selfColor == targetColor) return true
        } catch (t: Throwable) {
            return false
        }
        return false
    }

    private fun teamFormatted(player: EntityPlayer): String {
        val team = player.team ?: return player.name ?: ""
        return team.formatString(player.name)
    }

    private fun mostFrequentColor(text: String): String? {
        val counts = HashMap<String, Int>()
        var i = 0
        while (i < text.length - 1) {
            val c = text[i]
            val code = text[i + 1]
            if (c == '§' && ((code in '0'..'9') || (code in 'a'..'f'))) {
                val color = "§$code"
                counts[color] = (counts[color] ?: 0) + 1
            }
            i++
        }
        return counts.maxByOrNull { it.value }?.key
    }

    private fun sameArmorColor(a: EntityPlayer, b: EntityPlayer): Boolean {
        val colorA = armorColor(a) ?: return false
        return colorA == armorColor(b)
    }

    private fun armorColor(player: EntityPlayer): Int? {
        val stack: ItemStack = player.getCurrentArmor(2) ?: return null
        val armor = stack.item as? ItemArmor ?: return null
        if (armor.armorMaterial != ItemArmor.ArmorMaterial.LEATHER) return null
        return armor.getColor(stack)
    }

    private fun displayName(name: String): String {
        return try {
            val team = mc.theWorld?.scoreboard?.getPlayersTeam(name) ?: return name
            team.getColorPrefix() + name + team.getColorSuffix()
        } catch (t: Throwable) {
            name
        }
    }

    private fun chat(text: String) {
        mc.thePlayer?.addChatMessage(ChatComponentText(text))
    }

    private fun playSound(name: String, volume: Float, pitch: Float) {
        mc.thePlayer?.playSound(name, volume, pitch)
    }

    private fun mainThread(block: () -> Unit) {
        try {
            Minecraft.getMinecraft().addScheduledTask(block)
        } catch (t: Throwable) {
            DangoLogger.warn("AntiSnipe stats callback dropped: {}", t.toString())
        }
    }
}
