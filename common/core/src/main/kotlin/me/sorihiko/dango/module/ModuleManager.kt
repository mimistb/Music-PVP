package me.sorihiko.dango.module

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.event.EventManager
import me.sorihiko.dango.service.Registry
import me.sorihiko.dango.service.Service

class ModuleManager(private val eventManager: EventManager) : Service, Registry<Module> {

    val modules = mutableMapOf<Category, MutableList<Module>>()

    /**
     * 注册顺序：加载顺序、配置读写顺序、界面顺序都以这里为准。
     *
     * 不能依赖 [modules] 这个按分类分桶的 Map 的遍历顺序——一旦新增分类，
     * 顺序就会变成分类枚举 / 首次出现顺序，跨模块 init 依赖会静默错位。
     */
    private val order = mutableListOf<Module>()

    /**
     * 类型 → 模块的索引。`get(Class)` 在 tick / 聊天收包这类热路径上每帧都会被调用，
     * 原实现每次都把所有分类摊平再线性扫描，属于纯浪费；这里注册时建索引，查询 O(1)。
     */
    private val byType = HashMap<Class<*>, Module>()

    override fun load() {
        for (module in order) {
            // 单个模块初始化失败不能影响后面所有模块：原来只捕获 Exception，
            // 一旦某个模块 init 里抛出 NoClassDefFoundError / LinkageError 这类 Error，
            // 循环会直接中断，排在后面的模块（例如反作弊、自动黑名单、狙击预警）
            // 永远拿不到 init() 里的静态实例，功能静默变成空转。
            try {
                module.init()
                eventManager.addListener(module)
            } catch (t: Throwable) {
                DangoLogger.error("${module.name} init failed, skip. {}", t)
            }
        }
    }

    override fun unload() {
    }

    override fun register(item: Module) {
        if (!(item.javaClass.isAnnotationPresent(ModuleMeta::class.java))) {
            throw IllegalArgumentException("Module must with @ModuleMeta annotation")
        }
        // 配置文件以模块名为键：重名会让两个模块写进同一个节点、互相覆盖，必须在注册期拦住
        val duplicated = order.firstOrNull { it.name == item.name }
        if (duplicated != null) {
            throw IllegalArgumentException(
                "Duplicate module name '${item.name}': ${duplicated.javaClass.name} / ${item.javaClass.name}",
            )
        }
        order.add(item)
        modules.computeIfAbsent(item.category) { mutableListOf() }.add(item)
        byType[item.javaClass] = item
    }

    /** 按注册顺序返回全部模块 */
    fun all(): List<Module> = order.toList()

    fun <T : Module> get(clazz: Class<T>): T {
        byType[clazz]?.let { return clazz.cast(it) }

        val module = order.firstOrNull { clazz.isInstance(it) }
            ?: throw IllegalStateException("Module not registered: ${clazz.name}")

        // 允许按父类查询时也缓存，后续调用不再扫描
        byType[clazz] = module
        return clazz.cast(module)
    }

    override fun unregister(item: Module) {
        // 只摘掉这一个实例：原来按分类整桶删除，会把同分类的其它模块一起清掉
        order.removeAll { it === item }
        val bucket = modules[item.category]
        if (bucket != null) {
            bucket.removeAll { it === item }
            if (bucket.isEmpty()) modules.remove(item.category)
        }
        byType.values.removeAll { it === item }
    }
}
