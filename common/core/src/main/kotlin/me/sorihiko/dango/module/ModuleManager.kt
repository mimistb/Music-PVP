package me.sorihiko.dango.module

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.event.EventManager
import me.sorihiko.dango.service.Registry
import me.sorihiko.dango.service.Service

class ModuleManager(private val eventManager: EventManager) : Service, Registry<Module> {

    val modules = mutableMapOf<Category, MutableList<Module>>()

    /**
     * 类型 → 模块的索引。`get(Class)` 在 tick / 聊天收包这类热路径上每帧都会被调用，
     * 原实现每次都把所有分类摊平再线性扫描，属于纯浪费；这里注册时建索引，查询 O(1)。
     */
    private val byType = HashMap<Class<*>, Module>()

    override fun load() {
        modules.values.flatten().forEach { module ->
            try {
                module.init()
                eventManager.addListener(module)
            }catch (e: Exception) {
                DangoLogger.error("${module.name} init failed, skip. {}", e)
            }
        }
    }

    override fun unload() {
    }

    override fun register(item: Module) {
        if (!(item.javaClass.isAnnotationPresent(ModuleMeta::class.java))) {
            throw IllegalArgumentException("Module must with @ModuleMeta annotation")
        }
        modules.computeIfAbsent(item.category) { mutableListOf() }.add(item)
        byType[item.javaClass] = item
    }

    fun <T : Module> get(clazz: Class<T>): T {
        byType[clazz]?.let { return clazz.cast(it) }

        val module = modules.values.flatten().firstOrNull { clazz.isInstance(it) }
            ?: throw IllegalStateException("Module not registered: ${clazz.name}")

        // 允许按父类查询时也缓存，后续调用不再扫描
        byType[clazz] = module
        return clazz.cast(module)
    }

    override fun unregister(item: Module) {
        modules.entries.removeIf { (_, v) ->
            v.contains(item)
        }
        byType.values.removeIf { it === item }
    }
}