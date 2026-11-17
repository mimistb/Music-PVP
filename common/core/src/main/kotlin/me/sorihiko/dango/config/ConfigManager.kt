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
import me.sorihiko.dango.module.values.KeyBindValue
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

    /** 孤儿键清理的保留规则；来自配置文件里的 `schemaPolicy`，缺省按保守策略 */
    private var cleanupPolicy = ConfigSchema.Policy()

    /** 统一按模块注册顺序遍历，保证读配置 / 存盘 / 档案应用顺序完全一致 */
    private fun modules(): List<Module> = moduleManager.all()

    /** schema 定义：模块名 → (设置项名 → 值类型)，供 [ConfigSchema] 判断孤儿键 */
    private fun definitions(): Map<String, Map<String, Class<*>>> {
        val out = LinkedHashMap<String, Map<String, Class<*>>>()
        for (module in modules()) {
            val fields = LinkedHashMap<String, Class<*>>()
            for (field in valueFields(module)) fields[field.name] = field.type
            out[module.name] = fields
        }
        return out
    }

    /**
     * 反射结果按类缓存：类结构在运行期不会变化，而自动存盘每 2 秒就要扫一遍所有模块，
     * 重复调用 declaredFields / isAnnotationPresent 是纯开销。缓存后只在第一次付出反射成本。
     */
    private val fieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, List<Field>>()

    private fun valueFields(module: Module): List<Field> =
        fieldCache[module.javaClass] ?: module.javaClass.declaredFields
            .filter { it.isAnnotationPresent(ModuleValue::class.java) }
            .onEach { it.isAccessible = true }
            .also { fieldCache[module.javaClass] = it }

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

            // 先做 schema 迁移 + 孤儿键清理，再按字段读取（清理结果会写回文件）
            runSchemaPass(root)

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
            pendingEnabled = enabledMap
            DangoLogger.info("Config loaded: {}", file.toString())
        } catch (e: Exception) {
            DangoLogger.error("Config load failed, using defaults. {}", e)
        }
    }

    /**
     * schema 迁移 + 孤儿键清理。
     *
     * 读取配置里的 `schemaPolicy` 作为保留规则；迁移/清理确实改动了结构时：
     * 1) 按 `backup` 把原文件备份成 `config.json.bak`；
     * 2) 把过程写进 `config-cleanup.log`，同时输出到控制台日志；
     * 3) 立刻把清理后的结构写回，避免下次启动重复清理。
     */
    private fun runSchemaPass(root: JsonObject) {
        cleanupPolicy = ConfigSchema.policyFrom(root)
        val report = try {
            ConfigSchema.migrate(root, definitions(), emptySet(), cleanupPolicy)
        } catch (e: Exception) {
            DangoLogger.error("Config schema pass failed. {}", e)
            return
        }
        for (line in report.log) DangoLogger.info("Config schema: {}", line)
        if (!report.dirty) return

        try {
            if (cleanupPolicy.backup) {
                val backup = file.resolveSibling(file.fileName.toString() + ".bak")
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING)
            }
            writeCleanupLog(report)
            Files.write(file, gson.toJson(root).toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            DangoLogger.error("Config schema writeback failed. {}", e)
        }
    }

    /** 清理日志：每条一行，带时间戳，追加写入 config/Music PVP/config-cleanup.log */
    private fun writeCleanupLog(report: ConfigSchema.Report) {
        val logFile = file.resolveSibling("config-cleanup.log")
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
            .format(java.util.Date())
        val text = buildString {
            append('[').append(stamp).append("] ").append(report.summary()).append('\n')
            if (report.removedModules.isNotEmpty()) {
                append("  移除模块: ").append(report.removedModules.joinToString(", ")).append('\n')
            }
            if (report.removedValues.isNotEmpty()) {
                append("  移除设置项: ").append(report.removedValues.joinToString(", ")).append('\n')
            }
            if (report.removedUiKeys.isNotEmpty()) {
                append("  移除界面键: ").append(report.removedUiKeys.joinToString(", ")).append('\n')
            }
        }
        try {
            Files.createDirectories(logFile.parent)
            Files.write(
                logFile,
                text.toByteArray(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        } catch (e: Exception) {
            DangoLogger.warn("Config cleanup log failed: {}", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun applyValue(value: Any?, json: JsonElement) {
        when (value) {
            is BooleanValue -> value.set(json.asBoolean)
            is IntValue -> value.setValue(json.asInt)
            is FloatValue -> value.setValue(json.asFloat)
            is StringValue -> value.set(json.asString)
            // 组合键：新格式是 "42+33" 字符串；旧配置是单个 Int 键码，直接按单键读回
            is KeyBindValue -> value.load(
                if (json.isJsonPrimitive && json.asJsonPrimitive.isNumber) json.asInt.toString()
                else json.asString,
            )
        }
    }

    // ---------------------------------------------------------------- 保存

    private fun serialize(): String {
        val root = JsonObject()
        root.addProperty(ConfigSchema.KEY, ConfigSchema.CURRENT)
        root.add("schemaPolicy", ConfigSchema.policyTo(cleanupPolicy))
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
                    is KeyBindValue -> values.addProperty(field.name, v.storage())
                }
            }
            obj.add("values", values)
            root.add(module.name, obj)
        }
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
            // 配置里出现的模块按存盘值恢复；没出现的（旧配置缺键 / 新版本新增）按模块声明的默认值走。
            // 这样既尊重用户关闭，也不再需要模块在 init() 里提前写 enabled（那时监听器还没注册）。
            val restored = HashSet<Module>()
            pendingEnabled?.forEach { (module, enabled) ->
                try {
                    module.enabled = enabled
                    restored.add(module)
                } catch (ex: Exception) {
                    DangoLogger.warn("Restore module state failed: {}", module.name)
                }
            }
            for (module in modules()) {
                if (module in restored) continue
                try {
                    module.enabled = module.defaultEnabled
                } catch (ex: Exception) {
                    DangoLogger.warn("Apply module default failed: {}", module.name)
                }
            }
            pendingEnabled = null
            if (fresh) {
                for (module in modules()) {
                    try {
                        module.enabled = module.enabledOnFreshConfig
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
        // 应用后马上落盘，避免玩家下次启动又回到旧参数
        saveIfChanged()
    }
}
