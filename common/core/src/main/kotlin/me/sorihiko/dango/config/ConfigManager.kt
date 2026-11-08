package me.sorihiko.dango.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.event.Listener
import me.sorihiko.dango.events.TickEvent
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleManager
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.module.values.StringValue

import java.lang.reflect.Field
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 把每个模块的开关状态和 @ModuleValue 设置存到 config/Music PVP/config.json，启动时自动读回。
 *
 * - 设置值在启动时立刻读回；
 * - 模块开关要等着色器、字体都加载完（第 3 个 tick）再恢复，避免 onEnable 时资源还没准备好；
 * - 之后每 2 秒检查一次，有变化才写文件；游戏关闭时再存一次。
 */
class ConfigManager(
    private val moduleManager: ModuleManager,
    private val file: Path,
) : Listener {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var pendingEnabled: Map<Module, Boolean>? = null
    private var ticks = 0
    private var ready = false
    private var lastSaved: String? = null

    private fun modules(): List<Module> = moduleManager.modules.values.flatten()

    private fun valueFields(module: Module): List<Field> =
        module.javaClass.declaredFields.filter {
            it.isAnnotationPresent(ModuleValue::class.java)
        }.onEach { it.isAccessible = true }

    // ---------------------------------------------------------------- 读取

    /** 第一次运行（没有配置文件）：所有功能默认打开 */
    private var fresh = false

    fun load() {
        if (!Files.exists(file)) {
            fresh = true
            DangoLogger.info("No config found, using defaults: {}", file.toString())
            return
        }
        try {
            val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            val root = JsonParser().parse(text).asJsonObject
            val enabledMap = mutableMapOf<Module, Boolean>()

            for (module in modules()) {
                val obj = root.get(module.name) as? JsonObject ?: continue
                (obj.get("enabled") as? JsonPrimitive)?.let { enabledMap[module] = it.asBoolean }

                val values = obj.get("values") as? JsonObject ?: continue
                // 先让模块把旧键迁移到新键，再按字段名读取
                try {
                    module.migrateLegacyConfig(values)
                } catch (e: Exception) {
                    DangoLogger.warn("Config migration skipped: {}", module.name)
                }
                for (field in valueFields(module)) {
                    val json = values.get(field.name) ?: continue
                    try {
                        applyValue(field.get(module), json)
                    } catch (e: Exception) {
                        DangoLogger.warn("Config value skipped: {}.{}", module.name, field.name)
                    }
                }
            }
            // 界面偏好（设置页分组的展开状态）和模块设置存在同一个文件里
            (root.get("ui") as? JsonObject)?.let { UiPrefs.load(it) }

            pendingEnabled = enabledMap
            DangoLogger.info("Config loaded: {}", file.toString())
        } catch (e: Exception) {
            DangoLogger.error("Config load failed, using defaults. {}", e)
        }
    }

    private fun applyValue(value: Any?, json: JsonElement) {
        when (value) {
            is BooleanValue -> value.set(json.asBoolean)
            is IntValue -> value.setValue(json.asInt)
            is FloatValue -> value.setValue(json.asFloat)
            is StringValue -> value.set(json.asString)
        }
    }

    // ---------------------------------------------------------------- 保存

    private fun serialize(): String {
        val root = JsonObject()
        for (module in modules()) {
            val obj = JsonObject()
            obj.addProperty("enabled", module.enabled)
            val values = JsonObject()
            for (field in valueFields(module)) {
                when (val v = field.get(module)) {
                    is BooleanValue -> values.addProperty(field.name, v.get())
                    is IntValue -> values.addProperty(field.name, v.value)
                    is FloatValue -> values.addProperty(field.name, v.value)
                    is StringValue -> values.addProperty(field.name, v.get())
                }
            }
            obj.add("values", values)
            root.add(module.name, obj)
        }
        // 界面偏好（设置页分组展开状态），与模块设置一起存盘、一起上锁
        val ui = JsonObject()
        UiPrefs.store(ui)
        root.add("ui", ui)
        return gson.toJson(root)
    }

    @Synchronized
    fun saveIfChanged() {
        if (!ready) return
        try {
            val text = serialize()
            if (text == lastSaved) return
            Files.createDirectories(file.parent)
            // 先写临时文件再替换，避免写到一半崩溃把配置弄坏
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            lastSaved = text
        } catch (e: Exception) {
            DangoLogger.error("Config save failed. {}", e)
        }
    }

    // ---------------------------------------------------------------- 时机

    @EventHandler
    fun onTick(e: TickEvent) {
        ticks++
        if (!ready && ticks >= 3) {
            pendingEnabled?.forEach { (module, enabled) ->
                try {
                    module.enabled = enabled
                } catch (ex: Exception) {
                    DangoLogger.warn("Restore module state failed: {}", module.name)
                }
            }
            pendingEnabled = null
            if (fresh) {
                for (module in modules()) {
                    try {
                        module.enabled = true
                    } catch (ex: Exception) {
                        DangoLogger.warn("Enable module failed: {}", module.name)
                    }
                }
                fresh = false
            }
            ready = true
            lastSaved = try { serialize() } catch (ex: Exception) { null }
            return
        }
        if (ready && ticks % 40 == 0) saveIfChanged()
    }

    fun installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread({ saveIfChanged() }, "Dango-ConfigSave"))
    }

    // ---------------------------------------------------------------- 参数档案

    /** 用户档案目录：config/Music PVP/profiles */
    private fun profilesDir(): Path = file.parent.resolve("profiles")

    /** 一键导出目录：config/Music PVP/exports */
    private fun exportsDir(): Path = file.parent.resolve("exports")

    /** 档案名清洗：去掉路径分隔符等非法字符，避免写到目录外 */
    private fun sanitize(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.take(48).ifEmpty { "未命名" }
    }

    /** 列出全部档案名（不含 .json），按名称排序 */
    fun listProfiles(): List<String> {
        val dir = profilesDir()
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".json") }
                    .map { it.fileName.toString().removeSuffix(".json") }
                    .sorted()
                    .collect(java.util.stream.Collectors.toList())
            }
        } catch (e: Exception) {
            DangoLogger.error("Profile list failed. {}", e)
            emptyList()
        }
    }

    /** 把当前全部参数保存成一份档案；同名覆盖 */
    fun saveProfile(name: String): Boolean {
        val target = profilesDir().resolve(sanitize(name) + ".json")
        return try {
            Files.createDirectories(target.parent)
            Files.write(target, serialize().toByteArray(StandardCharsets.UTF_8))
            DangoLogger.info("Profile saved: {}", target.toString())
            true
        } catch (e: Exception) {
            DangoLogger.error("Profile save failed. {}", e)
            false
        }
    }

    /** 读取档案并立刻应用到所有模块 */
    fun loadProfile(name: String): Boolean {
        val source = profilesDir().resolve(sanitize(name) + ".json")
        return applyFile(source)
    }

    fun deleteProfile(name: String): Boolean {
        val target = profilesDir().resolve(sanitize(name) + ".json")
        return try {
            Files.deleteIfExists(target)
        } catch (e: Exception) {
            DangoLogger.error("Profile delete failed. {}", e)
            false
        }
    }

    /** 一键导出：把当前参数写到 exports/时间戳.json，并返回文件路径 */
    fun exportCurrent(): Path? {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
        val target = exportsDir().resolve("MusicPVP-$stamp.json")
        return try {
            Files.createDirectories(target.parent)
            Files.write(target, serialize().toByteArray(StandardCharsets.UTF_8))
            DangoLogger.info("Config exported: {}", target.toString())
            target
        } catch (e: Exception) {
            DangoLogger.error("Config export failed. {}", e)
            null
        }
    }

    /** 一键导入：读取 exports 目录里最新的那份导出文件 */
    fun importLatestExport(): Boolean {
        val dir = exportsDir()
        if (!Files.isDirectory(dir)) return false
        var latest: Path? = null
        var best = Long.MIN_VALUE
        try {
            Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".json") }.forEach { p ->
                    val t = Files.getLastModifiedTime(p).toMillis()
                    if (t > best) {
                        best = t
                        latest = p
                    }
                }
            }
        } catch (e: Exception) {
            DangoLogger.error("Config import scan failed. {}", e)
            return false
        }
        return latest?.let { applyFile(it) } ?: false
    }

    fun importFile(path: Path): Boolean = applyFile(path)

    private fun applyFile(source: Path): Boolean {
        if (!Files.isRegularFile(source)) return false
        return try {
            val text = String(Files.readAllBytes(source), StandardCharsets.UTF_8)
            val root = JsonParser().parse(text).asJsonObject
            applyRoot(root)
            DangoLogger.info("Config applied: {}", source.toString())
            true
        } catch (e: Exception) {
            DangoLogger.error("Config apply failed. {}", e)
            false
        }
    }

    /** 把一份解析好的配置立刻灌进所有模块（档案载入 / 导入共用） */
    private fun applyRoot(root: JsonObject) {
        for (module in modules()) {
            val obj = root.get(module.name) as? JsonObject ?: continue
            (obj.get("enabled") as? JsonPrimitive)?.let {
                try {
                    module.enabled = it.asBoolean
                } catch (ex: Exception) {
                    DangoLogger.warn("Profile apply enabled failed: {}", module.name)
                }
            }
            val values = obj.get("values") as? JsonObject ?: continue
            for (field in valueFields(module)) {
                val json = values.get(field.name) ?: continue
                try {
                    applyValue(field.get(module), json)
                } catch (ex: Exception) {
                    DangoLogger.warn("Profile value skipped: {}.{}", module.name, field.name)
                }
            }
        }
        (root.get("ui") as? JsonObject)?.let { UiPrefs.load(it) }
        // 应用后马上落盘，避免玩家下次启动又回到旧参数
        saveIfChanged()
    }
}
