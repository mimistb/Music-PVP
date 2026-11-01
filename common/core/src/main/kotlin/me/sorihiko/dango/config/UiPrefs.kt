package me.sorihiko.dango.config

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

import java.util.concurrent.ConcurrentHashMap

/**
 * 界面偏好（不属于任何模块）：设置页每个分组卡片的展开 / 收起状态。
 *
 * 由 [ConfigManager] 与模块设置一起读写 `config/Music PVP/config.json`，
 * 各平台用的是同一个目录与 UTF-8 编码，所以状态不会因为换客户端而丢。
 *
 * 键固定为「分段名/分组标题」；没记录过的分组按收起处理（默认不展开任何参数配置）。
 */
object UiPrefs {

    private val openGroups = ConcurrentHashMap<String, Boolean>()

    fun isGroupOpen(key: String): Boolean = openGroups[key] ?: false

    fun setGroupOpen(key: String, open: Boolean) {
        openGroups[key] = open
    }

    /** ConfigManager 读回：root 里的 "ui" 对象 */
    fun load(root: JsonObject) {
        openGroups.clear()
        val groups = root.get("groups") as? JsonObject ?: return
        for ((key, value) in groups.entrySet()) {
            val p = value as? JsonPrimitive ?: continue
            if (p.isBoolean) openGroups[key] = p.asBoolean
        }
    }

    /** ConfigManager 写盘：把状态放进 "ui" 对象 */
    fun store(root: JsonObject) {
        val groups = JsonObject()
        for ((key, open) in openGroups) groups.addProperty(key, open)
        root.add("groups", groups)
    }
}
