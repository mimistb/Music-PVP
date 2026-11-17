package me.sorihiko.dango.config

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import me.sorihiko.dango.module.values.KeyBindValue

/**
 * 配置 schema：版本号校验 / 自动迁移 / 孤儿键清理。
 *
 * 配置文件顶层用一个 `schema` 整数标记结构版本。读到比 [CURRENT] 小的版本时按
 * [MIGRATIONS] 顺序逐级迁移；读到更高的版本则只读不写（降级运行时不破坏新版配置）。
 *
 * 清理阶段的「schema 定义」直接来自当前已注册的模块与它们的 `@ModuleValue` 字段，
 * 因此删模块 / 改字段名之后，旧配置里留下的节点会在下次读取时被识别为孤儿键。
 */
object ConfigSchema {

    /**
     * 当前结构版本：2 = 组合键统一为 "42+33" 字符串存储；3 = 移除独立液态玻璃 UI 开关；
     * 4 = 移除设置页分组展开状态（右键改为跳转独立设置页，不再有原地展开）
     */
    const val CURRENT = 4

    /** 顶层版本字段名 */
    const val KEY = "schema"

    /** 保留规则：哪些孤儿键不清理，以及是否保留备份 */
    class Policy(
        /** 保留整段未知模块 */
        val retainUnknownModules: Boolean = false,
        /** 保留未知的模块设置项 */
        val retainUnknownValues: Boolean = false,
        /** 保留未知的设置页分组展开状态 */
        val retainUnknownUiKeys: Boolean = false,
        /** 精确保留的键："模块名" 保留整段，"模块名.字段名" 保留单项 */
        val preserve: Set<String> = emptySet(),
        /** 清理前是否把原文件备份成 config.json.bak */
        val backup: Boolean = true,
    ) {
        fun keepsModule(name: String): Boolean =
            retainUnknownModules || name in preserve

        fun keepsValue(module: String, key: String): Boolean =
            retainUnknownValues || module in preserve || "$module.$key" in preserve
    }

    /** 一次迁移 + 清理的结果；[dirty] 为 true 表示需要写回文件 */
    class Report(
        val fromVersion: Int,
        val toVersion: Int,
        var migrated: Boolean = false,
    ) {
        val removedModules = mutableListOf<String>()
        val removedValues = mutableListOf<String>()
        val removedUiKeys = mutableListOf<String>()
        val log = mutableListOf<String>()

        val dirty: Boolean
            get() = migrated || removedModules.isNotEmpty() ||
                removedValues.isNotEmpty() || removedUiKeys.isNotEmpty()

        fun summary(): String =
            "schema $fromVersion→$toVersion，迁移=$migrated，" +
                "清理模块=${removedModules.size}，设置项=${removedValues.size}，界面键=${removedUiKeys.size}"
    }

    /**
     * 迁移规则表：key 表示「从该版本升到 key+1」。
     * 每条规则只负责结构改写，不做清理（清理统一在迁移之后执行）。
     */
    private val migrations: Map<Int, (JsonObject, Map<String, Map<String, Class<*>>>) -> Boolean> = mapOf(
        1 to { root, definitions ->
            // v1 → v2：组合键从单个 Int 键码统一成 "42+33" 字符串，读回逻辑只保留一种格式
            var changed = false
            for ((module, fields) in definitions) {
                val values = root.get(module)?.takeIf { it.isJsonObject }
                    ?.asJsonObject?.get("values")?.takeIf { it.isJsonObject }
                    ?.asJsonObject ?: continue
                for ((field, type) in fields) {
                    if (type != KeyBindValue::class.java) continue
                    val json = values.get(field) ?: continue
                    if (json.isJsonPrimitive && json.asJsonPrimitive.isNumber) {
                        values.addProperty(field, json.asInt.toString())
                        changed = true
                    }
                }
            }
            if (!root.has("ui") || !root.get("ui").isJsonObject) {
                root.add("ui", JsonObject())
                changed = true
            }
            changed
        },
        2 to { root, _ ->
            // v2 → v3：删除已下线的「独立液态玻璃 UI 模式」开关
            val ui = root.get("ui")?.takeIf { it.isJsonObject }?.asJsonObject
            ui?.remove("singleGlassUi") != null
        },
        3 to { root, _ ->
            // v3 → v4：移除分组展开状态整段（设置页不再原地展开）
            root.remove(UI_KEY) != null
        },
    )

    fun versionOf(root: JsonObject): Int {
        val raw = root.get(KEY) as? JsonPrimitive ?: return 1
        return if (raw.isNumber) raw.asInt.coerceAtLeast(1) else 1
    }

    /**
     * 执行迁移 + 清理，并原地改写 [root]。
     *
     * @param definitions 模块名 → (设置项名 → 值类型)，由 [ConfigManager] 从当前注册的模块反射得到
     * @param knownUiKeys 设置页当前存在的分组键；为空表示未登记，此时不清理界面键
     */
    fun migrate(
        root: JsonObject,
        definitions: Map<String, Map<String, Class<*>>>,
        knownUiKeys: Set<String>,
        policy: Policy = Policy(),
    ): Report {
        val from = versionOf(root)
        val report = Report(from, CURRENT)

        // 1) 版本校验：高于当前版本只读，避免旧客户端把新配置写坏
        if (from > CURRENT) {
            report.log += "配置版本 $from 高于当前支持的 $CURRENT，跳过迁移与清理（只读）"
            return report
        }

        // 2) 逐级迁移
        var version = from
        while (version < CURRENT) {
            val rule = migrations[version]
            if (rule == null) {
                report.log += "缺少 v$version → v${version + 1} 的迁移规则，跳过"
                break
            }
            val changed = try {
                rule(root, definitions)
            } catch (e: Exception) {
                report.log += "v$version → v${version + 1} 迁移失败：${e.message}"
                false
            }
            if (changed) report.migrated = true
            report.log += "已应用迁移 v$version → v${version + 1}"
            version++
        }
        val target = maxOf(version, from)
        root.addProperty(KEY, target)

        // 3) 孤儿模块
        for (name in root.entrySet().map { it.key }) {
            if (name == KEY || name == UI_KEY || name == POLICY_KEY) continue
            if (definitions.containsKey(name)) continue
            if (policy.keepsModule(name)) {
                report.log += "保留未知模块：$name"
                continue
            }
            val node = root.get(name)
            if (node != null && node.isJsonObject) {
                root.remove(name)
                report.removedModules += name
            }
        }

        // 4) 孤儿设置项
        for ((module, fields) in definitions) {
            val values = root.get(module)?.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("values")?.takeIf { it.isJsonObject }
                ?.asJsonObject ?: continue
            for (key in values.entrySet().map { it.key }) {
                if (fields.containsKey(key)) continue
                if (policy.keepsValue(module, key)) {
                    report.log += "保留未知设置项：$module.$key"
                    continue
                }
                values.remove(key)
                report.removedValues += "$module.$key"
            }
        }

        // 5) 孤儿界面键（仅在设置页登记过分组键时执行，避免首次启动清空展开状态）
        val ui = root.get(UI_KEY) as? JsonObject
        val groups = ui?.get("groups") as? JsonObject
        if (groups != null && knownUiKeys.isNotEmpty()) {
            for (key in groups.entrySet().map { it.key }) {
                if (knownUiKeys.contains(key)) continue
                if (policy.retainUnknownUiKeys) {
                    report.log += "保留未知界面键：$key"
                    continue
                }
                groups.remove(key)
                report.removedUiKeys += key
            }
        }

        report.log += report.summary()
        return report
    }

    // ---------------------------------------------------------------- 保留规则读写

    private const val UI_KEY = "ui"
    private const val POLICY_KEY = "schemaPolicy"

    fun policyFrom(root: JsonObject): Policy {
        val obj = root.get(POLICY_KEY) as? JsonObject ?: return Policy()
        fun bool(key: String, def: Boolean): Boolean =
            (obj.get(key) as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean ?: def
        val preserve = (obj.get("preserve") as? com.google.gson.JsonArray)
            ?.mapNotNull { it.asStringOrNull() }?.toSet() ?: emptySet()
        return Policy(
            retainUnknownModules = bool("retainUnknownModules", false),
            retainUnknownValues = bool("retainUnknownValues", false),
            retainUnknownUiKeys = bool("retainUnknownUiKeys", false),
            preserve = preserve,
            backup = bool("backup", true),
        )
    }

    fun policyTo(policy: Policy): JsonObject = JsonObject().apply {
        addProperty("retainUnknownModules", policy.retainUnknownModules)
        addProperty("retainUnknownValues", policy.retainUnknownValues)
        addProperty("retainUnknownUiKeys", policy.retainUnknownUiKeys)
        addProperty("backup", policy.backup)
        add("preserve", com.google.gson.JsonArray().apply {
            policy.preserve.sorted().forEach { add(JsonPrimitive(it)) }
        })
    }

    private fun JsonElement.asStringOrNull(): String? =
        if (isJsonPrimitive && asJsonPrimitive.isString) asString else null
}
