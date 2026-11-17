package me.sorihiko.dango

import me.sorihiko.dango.event.EventManager
import me.sorihiko.dango.events.TickEvent
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleManager
import me.sorihiko.dango.module.impls.music.AutoLyricsModule
import me.sorihiko.dango.module.impls.music.FpsToolsModule
import me.sorihiko.dango.module.impls.music.HudInfoModule
import me.sorihiko.dango.module.impls.music.HudLayoutModule
import me.sorihiko.dango.module.impls.music.NametagStatsModule
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.module.impls.music.SongCardModule
import me.sorihiko.dango.module.impls.music.TabStatsHudModule
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.KeyBindValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.platform.Platform
import me.sorihiko.dango.platform.PlayerPose
import me.sorihiko.dango.ui.GlassStyle
import me.sorihiko.dango.ui.glass.GlassBackdropPolicy
import me.sorihiko.dango.ui.glass.GlassCaptureSchedule
import me.sorihiko.dango.utils.GammaGuard

import java.lang.reflect.Field
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.system.exitProcess

/**
 * 模块兼容性边界测试（无头，不启动 Minecraft）。
 *
 * 覆盖：全量注册 / 全量启用 / 单独启用 / 任意组合 / 参数边界 / 配置往返 /
 * 旧配置缺键默认值 / 注册表健壮性。
 *
 * 运行：common 目录下 `gradlew :ui:compatTest --offline`
 */
private var passed = 0
private val failures = mutableListOf<String>()

private fun check(label: String, condition: Boolean, detail: String = "") {
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

private inline fun safe(label: String, block: () -> Unit) {
    try {
        block()
        passed++
    } catch (t: Throwable) {
        failures += "$label: $t"
        println("  [FAIL] $label -> $t")
    }
}

private class FakePlatform(private val dir: Path) : Platform {
    override val configDir: Path get() = dir
    override val playerName: String = "CompatTester"
    override val windowWidth: Int = 1920
    override val windowHeight: Int = 1080
    override val mouseX: Int = 960
    override val mouseY: Int = 540
    override fun isMouseDown(button: Int): Boolean = false
    override fun isKeyDown(key: Int): Boolean = false
    override val ctrlDown: Boolean = false
    override var clipboard: String = ""
    override val inGame: Boolean = false
    override val chatOpen: Boolean = false
    override val guiScale: Int = 2
    override val fps: Int = 60
    override val fov: Float = 70f
    override val pingMs: Int = 0
    override val player: PlayerPose? = null
    override fun openMusicScreen() {}
}

private fun valueFields(module: Module): List<Field> =
    module.javaClass.declaredFields.filter { it.isAnnotationPresent(ModuleValue::class.java) }

private fun <T : Module> find(modules: List<Module>, clazz: Class<T>): T? =
    modules.firstOrNull { clazz.isInstance(it) }?.let { clazz.cast(it) }

@Suppress("UNCHECKED_CAST")
private fun managerGet(manager: ModuleManager, module: Module): Module =
    manager.get(module.javaClass as Class<Module>)

fun main() {
    println("Dango Music 模块兼容性边界测试")

    val modules = DangoClient.defaultModules()

    // ---------------------------------------------------------------- 注册与初始化
    section("注册与初始化")
    check("模块总数 34", modules.size == 34, "实际 ${modules.size}")
    check("模块名唯一", modules.map { it.name }.toSet().size == modules.size)

    val dir = Files.createTempDirectory("dango-compat").resolve(DangoMusic.CONFIG_DIR)
    DangoMusic.start(FakePlatform(dir), modules)
    val manager = DangoMusic.instance.moduleManager

    check("注册表数量一致", manager.all().size == modules.size, "实际 ${manager.all().size}")
    check(
        "加载顺序 = 注册顺序",
        manager.all().map { it.name } == modules.map { it.name },
    )
    safe("byType 索引覆盖全部模块") {
        for (module in modules) {
            check("${module.name} 可按类型取回", managerGet(manager, module) === module)
        }
    }

    // ---------------------------------------------------------------- 默认值声明
    section("默认启用声明")
    check("HudLayoutModule 默认启用", HudLayoutModule().defaultEnabled)
    check("AutoLyricsModule 默认启用", AutoLyricsModule().defaultEnabled)
    check("HudLayoutModule 构造后不提前开启", !HudLayoutModule().enabled)
    check("AutoLyricsModule 构造后不提前开启", !AutoLyricsModule().enabled)
    check("FpsToolsModule 默认关闭", !FpsToolsModule().defaultEnabled)

    // ---------------------------------------------------------------- 全量启用
    section("全量启用")
    repeat(3) { DangoMusic.callEvent(TickEvent) }
    check("首次启动后默认启用的模块全部启用", modules.filter { it.enabledOnFreshConfig }.all { it.enabled })
    check("NametagStats 全新配置保持关闭", modules.first { it.name == "NametagStats" }.enabled == false)

    // ---------------------------------------------------------------- 参数边界
    section("参数边界")
    safe("全部模块的参数默认值 / 键位往返") {
        for (module in modules) {
            for (field in valueFields(module)) {
                field.isAccessible = true
                when (val value = field.get(module)) {
                    is FloatValue -> {
                        check(
                            "${module.name}.${field.name} 默认值在范围内",
                            value.value >= value.minRange - 1e-4f && value.value <= value.maxRange + 1e-4f,
                            "value=${value.value} range=${value.minRange}..${value.maxRange}",
                        )
                        val snapshot = value.value
                        value.setValue(snapshot)
                        check("${module.name}.${field.name} 写入幂等", value.value == snapshot)
                    }
                    is IntValue -> {
                        check(
                            "${module.name}.${field.name} 默认值在范围内",
                            value.value in value.minRange..value.maxRange,
                            "value=${value.value} range=${value.minRange}..${value.maxRange}",
                        )
                        val snapshot = value.value
                        value.setValue(snapshot)
                        check("${module.name}.${field.name} 写入幂等", value.value == snapshot)
                    }
                    is KeyBindValue -> {
                        val storage = value.storage()
                        value.load(storage)
                        check("${module.name}.${field.name} 键位往返一致", value.storage() == storage)
                    }
                    else -> Unit
                }
            }
        }
    }

    // ---------------------------------------------------------------- 单独启用
    section("单独启用 / 单独关闭")
    for (target in modules) {
        for (module in modules) module.enabled = module === target
        safe("单独启用 ${target.name} 后 tick") {
            DangoMusic.callEvent(TickEvent)
            check("${target.name} 保持启用", target.enabled)
        }
        for (module in modules) module.enabled = false
        safe("全部关闭后 tick") {
            DangoMusic.callEvent(TickEvent)
            check("全部关闭后无模块被意外启用", modules.none { it.enabled })
        }
    }

    // ---------------------------------------------------------------- 任意组合
    section("任意组合（32 组，固定 seed）")
    val random = Random(20261115L)
    repeat(32) { round ->
        for (module in modules) module.enabled = random.nextBoolean()
        safe("组合 #$round tick") { DangoMusic.callEvent(TickEvent) }
    }
    for (module in modules) module.enabled = true
    safe("全量启用复测") {
        DangoMusic.callEvent(TickEvent)
        check("复测后全部启用", modules.all { it.enabled })
    }

    // ---------------------------------------------------------------- 跨模块只读访问
    section("依赖缺失时的只读访问")
    safe("关闭半数模块后读取依赖模块") {
        for (module in modules) module.enabled = modules.indexOf(module) % 2 == 0
        val songCard = SongCardModule.of()
        check("SongCardModule 实例可用", songCard != null)
        @Suppress("UNUSED_VARIABLE")
        val showing = songCard?.showing ?: false
        val tabStats = TabStatsHudModule.of()
        check("TabStatsHudModule 实例可用", tabStats != null)
        @Suppress("UNUSED_VARIABLE")
        val tabOpen = tabStats?.isOpen ?: false
        DangoMusic.callEvent(TickEvent)
    }

    // ---------------------------------------------------------------- 配置往返
    section("配置档案往返")
    val config = DangoMusic.instance.configManager
    val expected = modules.mapIndexed { index, _ -> index % 3 == 0 }
    modules.forEachIndexed { index, module -> module.enabled = expected[index] }

    val fps = manager.get(FpsToolsModule::class.java)
    val originalZoom = fps.zoomSpeed.value
    val zoomTarget = if (originalZoom != fps.zoomSpeed.maxRange) fps.zoomSpeed.maxRange else fps.zoomSpeed.minRange
    fps.zoomSpeed.setValue(zoomTarget)

    check("档案保存成功", config.saveProfile("compat-check"))
    modules.forEach { it.enabled = !it.enabled }
    fps.zoomSpeed.setValue(originalZoom)

    check("档案载入成功", config.loadProfile("compat-check"))
    check(
        "档案恢复模块开关",
        modules.mapIndexed { index, module -> module.enabled == expected[index] }.all { it },
    )
    check("档案恢复参数值", fps.zoomSpeed.value == zoomTarget, "实际 ${fps.zoomSpeed.value}，期望 $zoomTarget")
    for (module in modules) module.enabled = true

    // ---------------------------------------------------------------- 注册表健壮性
    section("注册表健壮性")
    val local = ModuleManager(EventManager())
    val first = FpsToolsModule()
    val second = NeteaseMusicModule()
    val third = HudInfoModule()
    local.register(first)
    local.register(second)
    local.register(third)
    check("注册顺序保持", local.all() == listOf(first, second, third))
    check("分类桶包含全部模块", local.modules.values.flatten().toSet() == setOf(first, second, third))

    val duplicateRejected = try {
        local.register(FpsToolsModule())
        false
    } catch (e: IllegalArgumentException) {
        true
    }
    check("重名模块注册被拦截", duplicateRejected)

    local.unregister(second)
    check("注销单个模块后其它模块仍在", local.all() == listOf(first, third))
    check("分类桶未整桶误删", local.modules.values.flatten().toSet() == setOf(first, third))

    // ---------------------------------------------------------------- 旧配置缺键
    section("旧配置缺键时的默认启用")
    val legacyDir = Files.createTempDirectory("dango-compat-legacy").resolve(DangoMusic.CONFIG_DIR)
    Files.createDirectories(legacyDir)
    Files.write(legacyDir.resolve("config.json"), "{}".toByteArray(StandardCharsets.UTF_8))
    val legacyModules = DangoClient.defaultModules()
    DangoMusic.start(FakePlatform(legacyDir), legacyModules)
    repeat(3) { DangoMusic.callEvent(TickEvent) }

    val legacyHudLayout = find(legacyModules, HudLayoutModule::class.java)
    val legacyAutoLyrics = find(legacyModules, AutoLyricsModule::class.java)
    val legacyFps = find(legacyModules, FpsToolsModule::class.java)
    check("缺键时 HudLayout 默认开启", legacyHudLayout?.enabled == true)
    check("缺键时 AutoLyrics 默认开启", legacyAutoLyrics?.enabled == true)
    check("缺键时普通模块保持关闭", legacyFps?.enabled == false)
    check("缺键时 NametagStats 保持关闭", find(legacyModules, NametagStatsModule::class.java)?.enabled == false)

    // ---------------------------------------------------------------- 液态玻璃 / 永久亮度
    section("液态玻璃背景可用性（面板变黑回归）")
    check("背景就绪且两张纹理都有效才允许采样", GlassBackdropPolicy.canSample(true, 12, 34))
    check("从未抓取过背景时拒绝采样", !GlassBackdropPolicy.canSample(false, 12, 34))
    check("纹理已释放（-1）时拒绝采样", !GlassBackdropPolicy.canSample(true, -1, 34))
    check("任意一张纹理无效都拒绝采样", !GlassBackdropPolicy.canSample(true, 12, 0))
    check("窗口重建后的瞬时状态按无效处理", !GlassBackdropPolicy.canSample(true, -1, -1))

    section("玻璃 fallback 不至于整块纯黑")
    val glassFallback = GlassStyle().fallback
    check(
        "fallback 半透明且不是纯黑",
        glassFallback.a < 1f && (glassFallback.r + glassFallback.g + glassFallback.b) > 0.05f
    )

    section("永久亮度 gamma 保护")
    check("普通亮度会被记录为原值", GammaGuard.rememberedGamma(0.5f) == 0.5f)
    check("开启前已是上限时不把它当成原值", GammaGuard.isFull(GammaGuard.rememberedGamma(100f)))
    check("亮度上限判定允许浮点误差", GammaGuard.isFull(99.999f))
    check("原版最亮档（1.0）不算永久亮度上限", !GammaGuard.isFull(1.0f))
    check("光影改写的中间值不会被误判为上限", !GammaGuard.isFull(2.5f))

    section("玻璃抓帧时序（打开聊天框后变黑回归）")
    val schedule = GlassCaptureSchedule()
    check("未用过玻璃背景时帧首不抓屏", !schedule.onFrameStart())
    check("默认不在 HUD 阶段", !schedule.inHudPass)
    schedule.beginHudPass()
    check("HUD 阶段内禁止抓屏（聊天黑底已画上）", schedule.inHudPass)
    schedule.noteUsed()
    schedule.endHudPass()
    check("HUD 结束退出该阶段", !schedule.inHudPass)
    check("本帧用过背景后下一帧帧首抓屏", schedule.onFrameStart())

    val hudOnly = GlassCaptureSchedule()
    hudOnly.beginHudPass()
    check("HUD 阶段中不抓屏", hudOnly.inHudPass)
    hudOnly.noteUsed()
    hudOnly.endHudPass()
    check("HUD 阶段用过的画布同样触发下一帧帧首抓屏", hudOnly.onFrameStart())
    hudOnly.reset()
    check("离开世界后时序归零", !hudOnly.onFrameStart() && !hudOnly.inHudPass)

    section("永久亮度还原值（关不掉 / 滑条失效回归）")
    check("仍为上限且有旧值时还原旧值", GammaGuard.restoreValue(100f, 0.4f, true) == 0.4f)
    check(
        "仍为上限但没有旧值时退回原版上限",
        GammaGuard.restoreValue(100f, 100f, false) == GammaGuard.VANILLA_MAX_GAMMA
    )
    check("用户已改过亮度时保留用户值", GammaGuard.restoreValue(0.6f, 0.4f, true) == 0.6f)
    check("没有旧值但用户已改过亮度也保留用户值", GammaGuard.restoreValue(0.2f, 100f, false) == 0.2f)

    // ---------------------------------------------------------------- 汇总
    println()
    if (failures.isEmpty()) {
        println("ALL $passed CHECKS PASSED")
        exitProcess(0)
    } else {
        println("FAILED ${failures.size} / ${passed + failures.size} CHECKS")
        for (failure in failures) println("  - $failure")
        exitProcess(1)
    }
}
