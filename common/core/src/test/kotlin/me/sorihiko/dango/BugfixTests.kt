package me.sorihiko.dango

import bedwars.MatchTracker
import bedwars.PlayerIdentity
import bedwars.Team
import me.sorihiko.dango.lyrics.LyricsProvider
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.music.isNeteaseHost
import me.sorihiko.dango.module.values.KeyBindValue
import me.sorihiko.dango.platform.Keys

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

private fun testKeyRecordingIgnoresModifiers() {
    check(
        "快捷键录制：Shift 不算绑定",
        Keys.isModifier(Keys.LSHIFT) && Keys.isModifier(Keys.RSHIFT),
    )
    check(
        "快捷键录制：Ctrl / Alt / Win 不算绑定",
        Keys.isModifier(Keys.LCONTROL) && Keys.isModifier(Keys.RCONTROL) &&
            Keys.isModifier(Keys.LMENU) && Keys.isModifier(Keys.RMENU) &&
            Keys.isModifier(219) && Keys.isModifier(220),
    )
    check(
        "快捷键录制：字母 / 空格 / CapsLock 可以正常绑定",
        !Keys.isModifier(Keys.A) && !Keys.isModifier(Keys.SPACE) && !Keys.isModifier(Keys.CAPITAL),
    )
}

private fun testKeyBindEscapeClear() {
    // 录制中 Esc 走的是「清除绑定」分支，不能被当成普通按键写进绑定
    check("快捷键：Esc 不是修饰键，会走清除分支", !Keys.isModifier(Keys.ESCAPE))
    check(
        "快捷键：Backspace / Delete 同样可清除",
        !Keys.isModifier(Keys.BACK) && !Keys.isModifier(Keys.DELETE),
    )

    val single = KeyBindValue(intArrayOf(Keys.A))
    check("快捷键：清除前已绑定", single.isSet && single.primary == Keys.A)
    single.clear()
    check(
        "快捷键：Esc 清除后为无绑定",
        !single.isSet && single.primary == 0 && single.storage().isEmpty(),
    )
    check("快捷键：清除后显示「无」", single.displayName() == "无")

    // 组合键：清除时修饰键要一起清掉
    val combo = KeyBindValue()
    combo.setCombo(listOf(Keys.LSHIFT), Keys.A)
    check("快捷键：组合键写入", combo.modifiers.size == 1 && combo.primary == Keys.A)
    combo.clear()
    check("快捷键：组合键清除后修饰键也清空", combo.modifiers.isEmpty() && !combo.isSet)

    // 打开播放器的按键不允许为空：Esc 只退出录制，保留原绑定
    val locked = KeyBindValue(intArrayOf(Keys.PERIOD), allowEmpty = false)
    locked.clear()
    check(
        "快捷键：allowEmpty=false 时保留原绑定",
        locked.isSet && locked.primary == Keys.PERIOD,
    )

    // 冲突：清除后冲突自动消失
    val a = KeyBindValue()
    val b = KeyBindValue()
    a.setCombo(emptyList(), Keys.K)
    b.setCombo(emptyList(), Keys.K)
    check("快捷键：相同主键判为冲突", a.conflictsWith(b))
    a.clear()
    check("快捷键：清除后不再冲突", !a.conflictsWith(b))

    // 清除 → 重新绑定 → 存档往返
    val round = KeyBindValue()
    round.setCombo(listOf(Keys.LCONTROL), Keys.K)
    round.clear()
    val clearedReload = KeyBindValue().also { it.load(round.storage()) }
    check("快捷键：清除后存档为空绑定", !clearedReload.isSet)
    round.setCombo(emptyList(), Keys.L)
    val rebound = KeyBindValue().also { it.load(round.storage()) }
    check(
        "快捷键：清除后可重新绑定并读回",
        rebound.isSet && rebound.primary == Keys.L,
        "storage=${round.storage()}",
    )

    // 不同输入设备：配置里读到鼠标键绑定后，Esc 同样能清除；鼠标键不会被当成修饰键
    val mouse = KeyBindValue().also { it.load(KeyBindValue.MOUSE_SIDE1.toString()) }
    check(
        "快捷键：鼠标键绑定可从配置读入",
        mouse.isSet && mouse.primary == KeyBindValue.MOUSE_SIDE1,
    )
    check("快捷键：鼠标键不是修饰键", !Keys.isModifier(KeyBindValue.MOUSE_SIDE1))
    mouse.clear()
    check("快捷键：鼠标键清除后为空", !mouse.isSet)
}

private fun testNeteaseLrcMixed() {
    val raw = """
        {"t":0,"c":[{"tx":"作词: "},{"tx":"某人"}]}
        {"t":1000,"c":[{"tx":"第一句"}]}
        [00:02.50]第二句
        [00:02.50]第二句
        {"t":4000,"c":[{"tx":"第三句"}]}
    """.trimIndent()
    val lines = LyricsProvider.parseNeteaseLrc(raw)
    check(
        "歌词：JSON 行 + 传统行混合解析",
        lines.size == 3,
        "lines=${lines.map { it.timeMs to it.text }}",
    )
    check("歌词：按时间排序", lines.map { it.timeMs } == lines.map { it.timeMs }.sorted())
    check("歌词：credit 行被过滤", lines.none { it.text.contains("作词") })
    check("歌词：同一时间同一文本去重", lines.count { it.text == "第二句" } == 1)
}

private fun testNeteaseLrcOnlyJson() {
    val raw = """
        {"t":500,"c":[{"tx":"你"},{"tx":"好"}]}
        {"t":1500,"c":[{"tx":"世界"}]}
    """.trimIndent()
    val lines = LyricsProvider.parseNeteaseLrc(raw)
    check(
        "歌词：纯 JSON 行也能解析",
        lines.size == 2 && lines[0].text == "你好" && lines[0].timeMs == 500L,
        "lines=${lines.map { it.timeMs to it.text }}",
    )
}

private fun testLyricMatchScore() {
    fun track(name: String, vararg artists: String) =
        NeteaseTrack(1L, name, artists.toList(), 0L, "")

    check(
        "歌词匹配：同名同歌手最高分",
        LyricsProvider.score("晴天", listOf("周杰伦"), track("晴天", "周杰伦")) == 130,
    )
    check(
        "歌词匹配：仅歌名包含达到阈值",
        LyricsProvider.score("晴天", emptyList(), track("晴天 (Live)")) == 60,
    )
    check(
        "歌词匹配：无关歌名不命中",
        LyricsProvider.score("晴天", emptyList(), track("七里香")) == 0,
    )
}

fun main() {
    testRosterColourEvidence()
    testRosterTagWins()
    testRelaxedColourEvidence()
    testNeteaseHostBoundary()
    testKeyRecordingIgnoresModifiers()
    testKeyBindEscapeClear()
    testNeteaseLrcMixed()
    testNeteaseLrcOnlyJson()
    testLyricMatchScore()

    println()
    if (failures.isEmpty()) {
        println("ALL $checks CHECKS PASSED")
    } else {
        println("${failures.size} / $checks CHECKS FAILED: ${failures.joinToString(", ")}")
        exitProcess(1)
    }
}
