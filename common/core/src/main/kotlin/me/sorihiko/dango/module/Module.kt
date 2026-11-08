package me.sorihiko.dango.module

import com.google.gson.JsonObject
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.event.Listener
import me.sorihiko.dango.events.ModuleToggleEvent

abstract class Module : Listener {
    val category = javaClass.getAnnotation(ModuleMeta::class.java).category
    val name = javaClass.getAnnotation(ModuleMeta::class.java).name
    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                DangoMusic.callEvent(ModuleToggleEvent.Enable(this))
                onEnable()
            } else {
                onDisable()
                DangoMusic.callEvent(ModuleToggleEvent.Disable(this))
            }
        }

    open fun init() {}

    /**
     * 旧配置兼容钩子。ConfigManager 在按字段名读取新值之前调用，
     * 模块可在这里把已废弃的键迁移到新键（例如把单一 targetLanguage 拆分到两个语言设置）。
     */
    open fun migrateLegacyConfig(values: JsonObject) {}

    protected open fun onEnable() {}
    protected open fun onDisable() {}
}