package me.sorihiko.dango.module

import com.google.gson.JsonObject
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.event.Listener
import me.sorihiko.dango.events.ModuleToggleEvent

abstract class Module : Listener {
    val category = javaClass.getAnnotation(ModuleMeta::class.java).category
    val name = javaClass.getAnnotation(ModuleMeta::class.java).name

    /**
     * 配置里没有这个模块的键时使用的默认开关。
     *
     * 不能在 init() 里直接写 enabled——那时事件监听器还没注册，
     * 开关事件会丢失，而且用户配置里缺键时会变成关不掉。
     */
    open val defaultEnabled: Boolean get() = false

    /**
     * 全新配置（首次安装）时是否跟随其它模块一起打开。
     *
     * 默认 true 保持原有“新装全开”的行为；默认关闭的新模块覆盖为 false，
     * 避免首次启动就被强制打开。
     */
    open val enabledOnFreshConfig: Boolean get() = true

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