package me.sorihiko.dango

import bedwars.MatchTracker
import bedwars.PlayerIdentity
import bedwars.Team
import me.sorihiko.dango.music.isNeteaseHost

import kotlin.system.exitProcess

/**
 * 本轮 bug 修复的回归用例（纯逻辑部分）。
 * 运行：在 common/ 目录执行 gradlew :core:bugfixTest
 */

private var checks = 0
private val failures = mutableListOf<String>()

private fun check(name: String, cond: Boolean, detail: String = "") {
    checks++
    if (cond) {
        println("PASS  $name")
    } else {
        println("FAIL  $name" + if (detail.isNotEmpty()) "  -> $detail" else "")
        failures += name
    }
}

/** 两个队伍各一人 + 自己（无显式标签，只有名字颜色） */
private fun rosterWithColourOnly(): List<PlayerIdentity> = listOf(
    PlayerIdentity("", "Alice", Team.RED, Team.RED),
    PlayerIdentity("", "Bob", Team.BLUE, Team.BLUE),
    PlayerIdentity("", "Me", Team.UNKNOWN, Team.RED),
)

/** 同上，但自己既有显式 BLUE 标签、名字又是红色：显式标签应优先 */
private fun rosterWithTagAndColour(): List<PlayerIdentity> = listOf(
    PlayerIdentity("", "Alice", Team.RED, Team.RED),
    PlayerIdentity("", "Bob", Team.BLUE, Team.BLUE),
    PlayerIdentity("", "Me", Team.BLUE, Team.RED),
)

private fun settle(tracker: MatchTracker, roster: List<PlayerIdentity>, relaxed: Boolean): MatchTracker.State {
    var state = tracker.update(null, roster, Team.UNKNOWN, 0, 0, "Me", relaxed)
    // 严格模式需要连续两个样本
    state = tracker.update(null, roster, Team.UNKNOWN, 0, 0, "Me", relaxed)
    return state
}

private fun testRosterColourEvidence() {
    val tracker = MatchTracker()
    val state = settle(tracker, rosterWithColourOnly(), relaxed = false)
    check(
        "TAB 名单：无显式标签时用名字颜色确定自己队伍",
        state.matchActive && state.ownTeam == Team.RED,
        "matchActive=${state.matchActive}, ownTeam=${state.ownTeam}, rosterValid=${state.rosterValid}",
    )
}

private fun testRosterTagWins() {
    val tracker = MatchTracker()
    val state = settle(tracker, rosterWithTagAndColour(), relaxed = false)
    check(
        "TAB 名单：显式队伍标签优先于名字颜色",
        state.matchActive && state.ownTeam == Team.BLUE,
        "matchActive=${state.matchActive}, ownTeam=${state.ownTeam}",
    )
}

private fun testRelaxedColourEvidence() {
    val tracker = MatchTracker()
    val state = tracker.update(null, rosterWithColourOnly(), Team.UNKNOWN, 0, 0, "Me", true)
    check(
        "通用模式：名字颜色证据一个样本即可激活",
        state.matchActive && state.ownTeam == Team.RED,
        "matchActive=${state.matchActive}, ownTeam=${state.ownTeam}",
    )
}

private fun testNeteaseHostBoundary() {
    check("网易云域名：music.163.com", isNeteaseHost("music.163.com"))
    check("网易云域名：p1.music.163.com", isNeteaseHost("p1.music.163.com"))
    check("网易云域名：evilmusic.163.com 不带 Cookie", !isNeteaseHost("evilmusic.163.com"))
    check("网易云域名：music.163.com.evil.com 不带 Cookie", !isNeteaseHost("music.163.com.evil.com"))
}

fun main() {
    testRosterColourEvidence()
    testRosterTagWins()
    testRelaxedColourEvidence()
    testNeteaseHostBoundary()

    println()
    if (failures.isEmpty()) {
        println("ALL $checks CHECKS PASSED")
    } else {
        println("${failures.size} / $checks CHECKS FAILED: ${failures.joinToString(", ")}")
        exitProcess(1)
    }
}
