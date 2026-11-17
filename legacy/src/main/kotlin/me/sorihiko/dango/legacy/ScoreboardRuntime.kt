package me.sorihiko.dango.legacy

import me.sorihiko.dango.core.ScoreboardHooks
import me.sorihiko.dango.module.impls.music.ScoreboardLine
import me.sorihiko.dango.module.impls.music.ScoreboardModule
import net.minecraft.client.Minecraft
import net.minecraft.scoreboard.ScorePlayerTeam

/**
 * 计分板：每 tick 读取侧边栏并发布给 [ScoreboardModule]。
 *
 * 取数与 Vibe 的 HudManager#drawScoreboard 完全一致：
 * 1. 先按本地玩家所在队伍的颜色取 displaySlot(3 + colorIndex)，取不到再退回 displaySlot 1；
 * 2. 跳过以 `#` 开头的行；
 * 3. 最多 15 行，按分数升序发布（HUD 侧倒序绘制，最高分在最上面）。
 *
 * 显示名与标题都保留 § 颜色码，HUD 侧逐段解析上色。
 * 原版侧边栏由 ScoreboardTransformer + ScoreboardHooks 在渲染入口取消。
 */
internal object ScoreboardRuntime {

    private const val MAX_ROWS = 15

    /** 上一帧发布的内容，内容没变就不重建数组，避免 HUD 每帧重算宽度 */
    private var lastTitle: String? = null
    private var lastRows: Array<ScoreboardLine> = emptyArray()

    fun tick() {
        val module = ScoreboardModule.of()
        ScoreboardHooks.enabled = module?.enabled == true
        if (module == null || !module.enabled) {
            if (lastTitle != null || lastRows.isNotEmpty()) {
                lastTitle = null
                lastRows = emptyArray()
                ScoreboardModule.publish(null, emptyArray())
            }
            return
        }
        val world = Minecraft.getMinecraft().theWorld
        if (world == null) {
            clearIfNeeded()
            return
        }
        val scoreboard = world.scoreboard
        val objective = sidebarObjective(scoreboard, Minecraft.getMinecraft().thePlayer?.name)
            ?: return clearIfNeeded()

        val all = ArrayList<ScoreboardLine>(16)
        for (score in scoreboard.getSortedScores(objective)) {
            val name = score.playerName ?: continue
            val team: ScorePlayerTeam? = scoreboard.getPlayersTeam(name)
            val shown = ScorePlayerTeam.formatPlayerName(team, name)
            all.add(ScoreboardLine(shown, score.scorePoints))
        }
        if (all.isEmpty()) {
            clearIfNeeded()
            return
        }
        // 先过滤 `#` 占位行再取最后 15 行；顺序与 Vibe 原实现一致（分数升序发布）。
        val rows = ScoreboardLine.pickVisible(all, MAX_ROWS)
        if (rows.isEmpty()) {
            // 全是占位行：没有可显示的内容，不要留一个只有标题的空面板
            clearIfNeeded()
            return
        }
        val title = objective.displayName ?: ""
        publishIfChanged(title, rows)
    }

    /**
     * 本地玩家队伍颜色对应的侧边栏优先，缺失时退回 displaySlot 1。
     * Hypixel 会把不同小游戏放在不同颜色的队伍槽里，这一步不能省。
     */
    private fun sidebarObjective(scoreboard: net.minecraft.scoreboard.Scoreboard, playerName: String?): net.minecraft.scoreboard.ScoreObjective? {
        if (playerName != null) {
            val team = scoreboard.getPlayersTeam(playerName)
            // 1.8.9 把队伍颜色索引留在了未映射的 func_98299_i() 里，没有 getColorIndex()
            val colorIndex = team?.func_98299_i() ?: -1
            if (colorIndex >= 0) {
                val objective = scoreboard.getObjectiveInDisplaySlot(3 + colorIndex)
                if (objective != null) return objective
            }
        }
        return scoreboard.getObjectiveInDisplaySlot(1)
    }

    private fun clearIfNeeded() {
        if (lastTitle != null || lastRows.isNotEmpty()) {
            lastTitle = null
            lastRows = emptyArray()
            ScoreboardModule.publish(null, emptyArray())
        }
    }

    private fun publishIfChanged(title: String, rows: Array<ScoreboardLine>) {
        if (title == lastTitle && ScoreboardLine.same(rows, lastRows)) return
        lastTitle = title
        lastRows = rows
        ScoreboardModule.publish(title, rows)
    }
}
