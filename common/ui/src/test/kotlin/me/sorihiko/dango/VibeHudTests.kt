package me.sorihiko.dango

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.impls.music.KeystrokesHudModule
import me.sorihiko.dango.module.impls.music.ScoreboardLine
import me.sorihiko.dango.module.impls.music.ScoreboardModule
import me.sorihiko.dango.module.impls.music.TargetInfoModule
import me.sorihiko.dango.module.impls.music.VibeTargetHud
import me.sorihiko.dango.module.impls.music.mixRgb
import me.sorihiko.dango.module.impls.music.rainbowRgb
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import kotlin.system.exitProcess

/**
 * Vibe HUD 移植回归测试（无头，不启动 Minecraft）。
 *
 * 覆盖本轮移植的四块内容：
 * 1. 计分板模块替换（[ScoreboardModule] + [ScoreboardLine]）与旧模块彻底移除；
 * 2. 按键显示新增「经典方格」样式后，两套样式可即时切换；
 * 3. 目标信息 HUD 的 5 套原有 + 11 套 Vibe 样式（共 16）全部可寻址、尺寸表完整；
 * 4. 计分板标题配色工具（[mixRgb] / [rainbowRgb]）的边界行为。
 *
 * 运行：legacy 目录下 `gradlew :common:ui:vibeHudTest --offline`
 */
private var passed = 0
private val failures = mutableListOf<String>()

private fun verify(label: String, condition: Boolean, detail: String = "") {
    if (condition) {
        passed++
    } else {
        failures += if (detail.isEmpty()) label else "$label ($detail)"
        println("  [FAIL] $label${if (detail.isEmpty()) "" else " -> $detail"}")
    }
}

private fun section(title: String) {
    println("\n== $title ==")
}

private fun safe(label: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        failures += "$label: $t"
        println("  [FAIL] $label -> $t")
    }
}

private fun intValue(target: Any, name: String): IntValue =
    target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as IntValue

private fun floatValue(target: Any, name: String): FloatValue =
    target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as FloatValue

fun main() {
    println("Vibe HUD 移植回归测试")

    // ---------------------------------------------------------------- 旧模块移除
    section("旧计分板模块移除")
    safe("旧 UI 模块类已删除") {
        verify(
            "ScoreboardHudModule 不存在",
            !classExists("me.sorihiko.dango.module.impls.music.ScoreboardHudModule"),
        )
    }
    safe("模块注册表") {
        val modules = DangoClient.defaultModules()
        val scoreboards = modules.filter { it.name == "Scoreboard" }
        verify("Scoreboard 只注册一次", scoreboards.size == 1, "实际 ${scoreboards.size}")
        verify("新模块类型", scoreboards.firstOrNull() is ScoreboardModule)
        verify("没有同名模块", modules.map { it.name }.let { it.size == it.toSet().size })
        verify("数量与历史一致", modules.size >= 30, "实际 ${modules.size}")
    }

    // ---------------------------------------------------------------- 计分板
    section("计分板数据契约")
    safe("ScoreboardLine 值语义") {
        val a = ScoreboardLine("§aPlayer", 42)
        val b = ScoreboardLine("§aPlayer", 42)
        val c = ScoreboardLine("§aPlayer", 41)
        verify("相等", a == b)
        verify("哈希一致", a.hashCode() == b.hashCode())
        verify("分数不同则不等", a != c)
        verify("toString 去掉 §", !a.toString().contains('§'), a.toString())
        verify("same 空数组", ScoreboardLine.same(emptyArray(), emptyArray()))
        verify("same 等值数组", ScoreboardLine.same(arrayOf(a), arrayOf(b)))
        verify("same 长度不同", !ScoreboardLine.same(arrayOf(a), emptyArray()))
        verify("same 元素不同", !ScoreboardLine.same(arrayOf(a), arrayOf(c)))
    }
    safe("分数格式化") {
        verify("50%", ScoreboardLine.formatPercent(0.5f) == "50%", ScoreboardLine.formatPercent(0.5f))
        verify("0%", ScoreboardLine.formatPercent(0f) == "0%")
        verify("100%", ScoreboardLine.formatPercent(1f) == "100%")
    }
    safe("标题发布 / 清空") {
        val rows = arrayOf(ScoreboardLine("A", 1), ScoreboardLine("B", 2))
        ScoreboardModule.publish("§e标题", rows)
        verify("标题已发布", ScoreboardModule.title == "§e标题")
        verify("行已发布", ScoreboardModule.rows.size == 2)
        ScoreboardModule.publish(null, emptyArray())
        verify("可清空标题", ScoreboardModule.title == null)
        verify("可清空行", ScoreboardModule.rows.isEmpty())
    }
    safe("模块常量") {
        verify("域名与原实现一致", ScoreboardModule.DOMAIN == "bipas.gay", ScoreboardModule.DOMAIN)
        verify("颜色模式 3 种", ScoreboardModule.MODE_NAMES.size == 3)
        verify("模式名", ScoreboardModule.MODE_NAMES.toList() == listOf("固定", "渐变", "彩虹"))
        val module = ScoreboardModule()
        verify("模块分类", module.category == Category.MUSIC)
        verify("模块名保持 Scoreboard", module.name == "Scoreboard")
    }
    safe("设置项范围") {
        val module = ScoreboardModule()
        val alpha = floatValue(module, "alpha")
        verify("透明度 0..1", alpha.minRange == 0f && alpha.maxRange == 1f)
        val mode = intValue(module, "domainMode")
        verify("颜色模式 0..2", mode.minRange == 0 && mode.maxRange == 2)
        val speed = floatValue(module, "domainSpeed")
        verify("速度 0.1..5", speed.minRange == 0.1f && speed.maxRange == 5f)
        val saturation = floatValue(module, "domainSaturation")
        verify("饱和度 0..1", saturation.minRange == 0f && saturation.maxRange == 1f)
        mode.setValue(2)
        verify("模式可实时切换", mode.value == 2)
        mode.setValue(99)
        verify("模式越界被夹取", mode.value == 2)
    }

    // ---------------------------------------------------------------- 按键显示
    section("按键显示样式")
    safe("样式表") {
        verify("2 套样式", KeystrokesHudModule.STYLE_NAMES.size == 2)
        verify("样式名", KeystrokesHudModule.STYLE_NAMES.toList() == listOf("液态玻璃", "经典方格"))
        verify("样式名唯一", KeystrokesHudModule.STYLE_NAMES.size == KeystrokesHudModule.STYLE_NAMES.toSet().size)
    }
    safe("样式控件可来回切换") {
        val module = KeystrokesHudModule()
        val style = intValue(module, "style")
        verify("上界与样式表一致", style.maxRange == KeystrokesHudModule.STYLE_NAMES.size - 1)
        verify("默认液态玻璃", style.value == 0)
        for (i in KeystrokesHudModule.STYLE_NAMES.indices) {
            style.setValue(i)
            verify("切换到 ${KeystrokesHudModule.STYLE_NAMES[i]}", style.value == i)
        }
        style.setValue(1)
        style.setValue(0)
        verify("切回液态玻璃", style.value == 0)
    }
    safe("Vibe 参数范围") {
        val module = KeystrokesHudModule()
        val keySize = intValue(module, "keySize")
        verify("键帽尺寸 18..42", keySize.minRange == 18 && keySize.maxRange == 42)
        val gap = intValue(module, "keyGap")
        verify("间距 0..8", gap.minRange == 0 && gap.maxRange == 8)
        val cellAlpha = floatValue(module, "cellAlpha")
        verify("底色透明度 0..1", cellAlpha.minRange == 0f && cellAlpha.maxRange == 1f)
        val radius = floatValue(module, "cellRadius")
        verify("圆角 0..16", radius.minRange == 0f && radius.maxRange == 16f)
    }

    // ---------------------------------------------------------------- 目标信息 HUD
    section("目标信息 HUD 样式")
    fun checkTargetStyleTable() {
        val names = TargetInfoModule.STYLE_NAMES
        verify("共 16 套样式", names.size == 16, "实际 ${names.size}")
        verify("样式名唯一", names.size == names.toSet().size)
        verify("原有 5 套保留", names.take(5).toList() == listOf("现代", "月亮", "展览", "渐层", "新款"))
        verify("Vibe 11 套", names.drop(5).toList() == listOf(
            "Novoline", "Tenacity", "Zeroday", "Rise", "LiquidGlass", "Lavalamp",
            "ThunderHack", "NurikZapen", "NurikZapen Mini", "CelkaPasta", "LiquidBounce",
        ))
    }
    safe("样式表", ::checkTargetStyleTable)
    safe("样式控件可切换全部 16 套") {
        val module = TargetInfoModule()
        val style = intValue(module, "style")
        verify("上界与样式表一致", style.maxRange == TargetInfoModule.STYLE_NAMES.size - 1)
        var last = -1
        for (i in TargetInfoModule.STYLE_NAMES.indices.reversed()) {
            style.setValue(i)
            val ok = style.value == i && style.value != last
            verify("切换到 ${TargetInfoModule.STYLE_NAMES[i]}", ok)
            last = style.value
        }
        verify("回到默认现代", style.setValue(0).let { style.value == 0 })
    }
    safe("Vibe 尺寸表") {
        val hud = VibeTargetHud()
        for (mode in 5..15) {
            val h = hud.heightFor(mode)
            verify("样式 ${TargetInfoModule.STYLE_NAMES[mode]} 高度有效", h >= 38f && h <= 90f, "实际 $h")
        }
        verify("未知样式回退到默认高度", hud.heightFor(99) == hud.heightFor(15))
        verify("需要护甲行 11..15", (11..15).all { hud.usesItems(it) })
        verify("不需要护甲行 5..10", (5..10).none { hud.usesItems(it) })
        verify("格子缓冲 5×4", hud.rects().size == 20)
    }
    safe("Vibe 参数范围") {
        val module = TargetInfoModule()
        val width = floatValue(module, "vibeWidth")
        verify("宽度 120..320", width.minRange == 120f && width.maxRange == 320f)
        val radius = floatValue(module, "vibeRadius")
        verify("圆角 0..14", radius.minRange == 0f && radius.maxRange == 14f)
        val bgAlpha = floatValue(module, "vibeBgAlpha")
        verify("底色透明度 0.3..1", bgAlpha.minRange == 0.3f && bgAlpha.maxRange == 1f)
        val count = intValue(module, "vibeParticleCount")
        verify("粒子数量 0..40", count.minRange == 0 && count.maxRange == 40)
        count.setValue(999)
        verify("粒子数量越界夹取", count.value == 40)
        count.setValue(12)
    }

    // ---------------------------------------------------------------- 配色工具
    section("计分板标题配色")
    safe("mixRgb 端点") {
        verify("t=0 取起点", mixRgb(0x112233, 0xAABBCC, 0f) == 0x112233)
        verify("t=1 取终点", mixRgb(0x112233, 0xAABBCC, 1f) == 0xAABBCC)
        verify("t 越界向下夹取", mixRgb(0x112233, 0xAABBCC, -3f) == 0x112233)
        verify("t 越界向上夹取", mixRgb(0x112233, 0xAABBCC, 3f) == 0xAABBCC)
    }
    safe("mixRgb 单调") {
        var prev = -1
        var lastR = -1
        for (i in 0..10) {
            val c = mixRgb(0x000000, 0xFFFFFF, i / 10f)
            val r = (c shr 16) and 0xFF
            verify("t=$i 分量递增", r >= prev, "$r < $prev")
            verify("t=$i 三通道一致", r == (c shr 8 and 0xFF) && r == (c and 0xFF))
            prev = r
            lastR = r
        }
        verify("终点为纯白", lastR == 255)
    }
    safe("rainbowRgb 相位") {
        verify("相位 0 为红", rainbowRgb(0.0, 1f) == 0xFF0000, Integer.toHexString(rainbowRgb(0.0, 1f)))
        verify("相位 1 回绕到红", rainbowRgb(1.0, 1f) == 0xFF0000)
        verify("负相位回绕", rainbowRgb(-1.0, 1f) == 0xFF0000)
        verify("饱和度为 0 时为白", rainbowRgb(0.37, 0f) == 0xFFFFFF)
        verify("饱和度越界夹取", rainbowRgb(0.37, 5f) == rainbowRgb(0.37, 1f))
    }
    safe("rainbowRgb 全相位分量合法") {
        for (i in 0..60) {
            val c = rainbowRgb(i / 60.0, 0.8f)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            verify("相位 $i 分量 0..255", r in 0..255 && g in 0..255 && b in 0..255, "$r,$g,$b")
        }
    }

    // ---------------------------------------------------------------- 汇总
    println("\n==============================================")
    if (failures.isEmpty()) {
        println("ALL $passed CHECKS PASSED")
        exitProcess(0)
    } else {
        println("FAILED ${failures.size} / ${passed + failures.size}")
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}

private fun classExists(name: String): Boolean = try {
    Class.forName(name, false, ScoreboardModule::class.java.classLoader)
    true
} catch (_: ClassNotFoundException) {
    false
}
