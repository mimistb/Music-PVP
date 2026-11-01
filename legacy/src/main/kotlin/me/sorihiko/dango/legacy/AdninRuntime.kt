package me.sorihiko.dango.legacy

import fpsport.adnin.anticheat.Alert
import fpsport.adnin.anticheat.AnticheatApi
import fpsport.adnin.anticheat.AnticheatConfig
import fpsport.adnin.anticheat.AnticheatModule
import fpsport.adnin.anticheat.PlayerLabel
import fpsport.adnin.chat.ChatConfig
import fpsport.adnin.chat.ChatDataModule
import fpsport.adnin.chat.ChatParser
import fpsport.adnin.session.GameSessionMode
import fpsport.adnin.session.SessionConfig
import fpsport.adnin.session.SessionModule

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.module.impls.music.FpsToolsModule

import net.minecraft.client.Minecraft
import net.minecraft.util.ChatComponentText

import java.util.Locale

/**
 * AdOverlay 三模块（反作弊 / 聊天数据 / 会话统计）的宿主运行层。
 *
 * <p>只做「设置值 → *Config → updateConfig」「事件 → 模块」和「模块 → 聊天/音效」的搬运：
 * 不新增 UI、不绘制、不做检测算法（检测端按逆向报告 §7.1 由宿主钩子负责）。
 * 会话统计只产出 [session] 里的快照数据，当前不渲染（UI 冻结）。
 */
object AdninRuntime {

    private val minecraft: Minecraft get() = Minecraft.getMinecraft()

    /** 配置模板：只在宿主层构造，模块内部始终拿副本。 */
    private val acConfig = AnticheatConfig()

    /** 队伍判定由宿主注入：读原版 scoreboard，模块本身不解析队伍。 */
    private val chatConfig = ChatConfig().apply {
        teammates = ChatConfig.TeamLookup { sender -> isSameTeam(sender) }
    }

    private val sessionConfig = SessionConfig()

    val anticheat = AnticheatModule(acConfig)
    val chat = ChatDataModule(chatConfig)
    val session = SessionModule(sessionConfig)

    /** 参数指纹：只有设置页数值变化时才重建 Config，避免每 tick 无谓拷贝。 */
    private var acFingerprint: List<Any?>? = null
    private var chatFingerprint: List<Any?>? = null
    private var sessionFingerprint: List<Any?>? = null

    private var selfName = ""
    private var matchActive = false
    private var victorySeen = false

    init {
        anticheat.setFlagSoundSink(AnticheatApi.FlagSoundSink { name, volume, pitch ->
            minecraft.thePlayer?.playSound(name, volume, pitch)
        })
        anticheat.addAlertListener { alert -> printAlert(alert) }
    }

    // ---------------------------------------------------------------- 每 tick

    fun sync(m: FpsToolsModule) {
        try {
            val on = m.enabled
            syncAnticheat(m, on)
            syncChat(m, on)
            syncSession(m, on)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music adnin sync failed: {}", t)
        }
    }

    fun tick(nowMs: Long) {
        try {
            val name = minecraft.thePlayer?.name
            if (name != null && name != selfName) {
                selfName = name
                anticheat.setSelfName(name)
            }
            session.tick(nowMs)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music adnin tick failed: {}", t)
        }
    }

    // ---------------------------------------------------------------- 事件

    /** 聊天包：进聊天数据模块；剥色后以 VICTORY 开头时记为本局胜利（不依赖该模块开关）。 */
    fun onChat(text: String?, nowMs: Long) {
        try {
            if (text == null) return
            // 胜负标记独立判定：聊天数据模块关闭时也必须能统计到胜利
            if (!victorySeen) {
                val body = ChatParser.stripColors(text).trim()
                if (body.startsWith("VICTORY", ignoreCase = true)) {
                    victorySeen = true
                }
            }
            chat.receive(text, nowMs)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music chat data failed: {}", t)
        }
    }

    /** BedWars 对局状态跳变：开始 / 结算。击杀与床数暂由宿主统计端提供（当前传 0）。 */
    fun onMatchActive(active: Boolean, nowMs: Long) {
        try {
            if (active == matchActive) return
            matchActive = active
            if (active) {
                victorySeen = false
                session.onGameStart(GameSessionMode.BEDWARS, nowMs)
            } else {
                session.onGameEnd(victorySeen, 0, 0, 0, nowMs)
                victorySeen = false
            }
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music session transition failed: {}", t)
        }
    }

    // ---------------------------------------------------------------- 配置同步

    private fun syncAnticheat(m: FpsToolsModule, on: Boolean) {
        val values = listOf<Any?>(
            on, m.acEnabled.get(), m.acSound.get(), m.acAutoBlock.get(), m.acLegitScaffold.get(),
            m.acScaffoldA.get(), m.acScaffoldB.get(), m.acNoSlow.get(),
            m.acConfirmCount.value, m.acWindowSeconds.value,
            m.acMinConfidence.value, m.acLegitMultiplier.value,
        )
        if (values == acFingerprint) return
        acFingerprint = values

        val cfg = acConfig.copy()
        cfg.anticheat = on && m.acEnabled.get()
        cfg.flagSound = m.acSound.get()
        cfg.autoblock = m.acAutoBlock.get()
        cfg.legitScaffold = m.acLegitScaffold.get()
        cfg.scaffoldA = m.acScaffoldA.get()
        cfg.scaffoldB = m.acScaffoldB.get()
        cfg.noSlow = m.acNoSlow.get()
        cfg.confirmFlagCount = m.acConfirmCount.value.coerceIn(1, 10)
        cfg.flagWindowMs = m.acWindowSeconds.value.coerceIn(1, 60).toLong() * 1000L
        cfg.minConfidence = m.acMinConfidence.value.coerceIn(0f, 1f).toDouble()
        cfg.legitScaffoldMultiplier = m.acLegitMultiplier.value.coerceIn(1, 5)
        anticheat.updateConfig(cfg)
    }

    private fun syncChat(m: FpsToolsModule, on: Boolean) {
        val values = listOf<Any?>(
            on, m.adChatEnabled.get(), m.adChatIgnoreTeammates.get(), m.adChatCompactBlacklist.get(),
            m.adChatMinStars.value, m.adChatMinFkdr.value, m.adChatMinSwKdr.value,
            m.adChatHistory.value, m.adChatMaxLength.value,
        )
        if (values == chatFingerprint) return
        chatFingerprint = values

        val cfg = chatConfig.copy()
        cfg.chatOverlay = on && m.adChatEnabled.get()
        cfg.ignoreTeammates = m.adChatIgnoreTeammates.get()
        cfg.compactBlacklist = m.adChatCompactBlacklist.get()
        cfg.minStars = m.adChatMinStars.value.coerceIn(0, 500)
        cfg.minFkdr = m.adChatMinFkdr.value.coerceIn(0f, 10f).toDouble()
        cfg.minSwKdr = m.adChatMinSwKdr.value.coerceIn(0f, 10f).toDouble()
        cfg.historyCapacity = m.adChatHistory.value.coerceIn(10, 1000)
        cfg.maxMessageLength = m.adChatMaxLength.value.coerceIn(8, 512)
        chat.updateConfig(cfg)
    }

    private fun syncSession(m: FpsToolsModule, on: Boolean) {
        val values = listOf<Any?>(
            on, m.adSessionEnabled.get(), m.adSessionIdleSeconds.value,
            m.adSessionKeepWinstreak.get(), m.adSessionMaxMinutes.value,
            m.adSessionAvgRoundSeconds.value,
        )
        if (values == sessionFingerprint) return
        sessionFingerprint = values

        val cfg = sessionConfig.copy()
        cfg.sessionStats = on && m.adSessionEnabled.get()
        cfg.idleTimeoutMs = m.adSessionIdleSeconds.value.coerceIn(0, 600).toLong() * 1000L
        cfg.keepWinstreakOnLoss = m.adSessionKeepWinstreak.get()
        cfg.maxSessionMs = m.adSessionMaxMinutes.value.coerceIn(0, 180).toLong() * 60_000L
        cfg.avgTimeRoundSeconds = m.adSessionAvgRoundSeconds.value.coerceIn(1, 60)
        session.updateConfig(cfg)
    }

    // ---------------------------------------------------------------- 输出

    /** 告警只走一条聊天输出，避免与宿主其它告警叠出两份。 */
    private fun printAlert(alert: Alert) {
        val player = minecraft.thePlayer ?: return
        val label = if (alert.label == PlayerLabel.NONE) "" else " " + alert.label.displayName
        val text = "[AC] " + alert.player + " " + alert.type.name + label +
                String.format(Locale.ROOT, " (%.2f / %d)", alert.confidence, alert.flagCount)
        player.addChatMessage(ChatComponentText(text))
    }

    /** 队友判定：原版 scoreboard 同一队伍；自己视为队友（用于过滤自己的发言）。 */
    private fun isSameTeam(sender: String): Boolean {
        val player = minecraft.thePlayer ?: return false
        if (sender.equals(player.name, ignoreCase = true)) return true
        val world = minecraft.theWorld ?: return false
        val mine = world.scoreboard.getPlayersTeam(player.name) ?: return false
        val other = world.scoreboard.getPlayersTeam(sender) ?: return false
        return mine.registeredName == other.registeredName
    }
}
