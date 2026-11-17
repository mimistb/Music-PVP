package me.sorihiko.dango.legacy

import fpsport.adnin.chat.ChatConfig
import fpsport.adnin.chat.ChatDataModule
import fpsport.adnin.chat.ChatParser
import fpsport.adnin.session.GameSessionMode
import fpsport.adnin.session.SessionConfig
import fpsport.adnin.session.SessionModule

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.module.impls.music.FpsToolsModule

import net.minecraft.client.Minecraft

/**
 * AdOverlay 两模块（聊天数据 / 会话统计）的宿主运行层。
 *
 * <p>只做「设置值 → *Config → updateConfig」和「事件 → 模块」的搬运：
 * 不新增 UI、不绘制、不做检测算法。
 * 会话统计只产出 [session] 里的快照数据，当前不渲染（UI 冻结）。
 */
object AdninRuntime {

    private val minecraft: Minecraft get() = Minecraft.getMinecraft()

    /** 队伍判定由宿主注入：读原版 scoreboard，模块本身不解析队伍。 */
    private val chatConfig = ChatConfig().apply {
        teammates = ChatConfig.TeamLookup { sender -> isSameTeam(sender) }
    }

    private val sessionConfig = SessionConfig()

    val chat = ChatDataModule(chatConfig)
    val session = SessionModule(sessionConfig)

    /** 参数指纹：只有设置页数值变化时才重建 Config，避免每 tick 无谓拷贝。 */
    private var chatFingerprint: List<Any?>? = null
    private var sessionFingerprint: List<Any?>? = null

    private var matchActive = false
    private var victorySeen = false

    // ---------------------------------------------------------------- 每 tick

    fun sync(m: FpsToolsModule) {
        try {
            val on = m.enabled
            syncChat(m, on)
            syncSession(m, on)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music adnin sync failed: {}", t)
        }
    }

    fun tick(nowMs: Long) {
        try {
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
