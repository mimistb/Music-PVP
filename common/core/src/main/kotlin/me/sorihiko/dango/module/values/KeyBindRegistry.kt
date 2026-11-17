package me.sorihiko.dango.module.values

import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleManager
import me.sorihiko.dango.platform.Keys

/**
 * 键位注册表：把全客户端所有 [KeyBindValue] 汇总成一张表，统一提供
 * 键位映射解析、实时冲突检测、整表导出 / 恢复。
 *
 * 设置页、自检、配置迁移都从这里取数据，避免各自反射一遍导致结果不一致。
 */
object KeyBindRegistry {

    /** 一条绑定：所属模块、字段名、界面标签、值对象 */
    class Ref(
        val module: String,
        val field: String,
        val label: String,
        val value: KeyBindValue,
    ) {
        /** 导出 / 恢复用的稳定键 */
        val key: String = "$module.$field"

        override fun toString(): String = "$key=${value.displayName()}"
    }

    /** 原版默认键位：客户端内绑定撞上这些键时给出提示 */
    private val vanilla: List<Pair<Int, String>> = listOf(
        17 to "W（前进）",
        31 to "S（后退）",
        30 to "A（左移）",
        32 to "D（右移）",
        57 to "空格（跳跃）",
        42 to "Shift（潜行）",
        54 to "Shift（潜行）",
        29 to "Ctrl（疾跑）",
        15 to "Tab（玩家列表）",
        1 to "Esc（暂停）",
        16 to "Q（丢弃）",
        18 to "E（背包）",
        20 to "T（聊天）",
        28 to "Enter（聊天）",
        53 to "/（命令）",
        61 to "F3（调试）",
        63 to "F5（视角）",
    )

    // 反射结果按类缓存：设置页每帧查询冲突，不能每帧扫 declaredFields
    private val fieldCache = HashMap<Class<*>, List<java.lang.reflect.Field>>()

    /** 读取全部绑定；顺序按模块注册顺序，保证 UI 稳定 */
    fun collect(manager: ModuleManager): List<Ref> {
        val out = ArrayList<Ref>()
        for (module in manager.modules.values.flatten()) {
            for (field in bindFields(module)) {
                val value = try {
                    field.get(module) as? KeyBindValue
                } catch (_: Throwable) {
                    null
                } ?: continue
                val label = field.getAnnotation(ModuleValue::class.java)?.name
                    ?.takeIf { it.isNotEmpty() } ?: field.name
                out += Ref(module.name, field.name, label, value)
            }
        }
        return out
    }

    private fun bindFields(module: Module): List<java.lang.reflect.Field> =
        fieldCache.getOrPut(module.javaClass) {
            module.javaClass.declaredFields
                .filter { it.type == KeyBindValue::class.java }
                .onEach { it.isAccessible = true }
        }

    /** 两两冲突（同一主键且修饰键集合互为子集） */
    fun conflicts(refs: List<Ref>): List<Pair<Ref, Ref>> {
        val out = ArrayList<Pair<Ref, Ref>>()
        for (i in refs.indices) {
            for (j in i + 1 until refs.size) {
                val a = refs[i]
                val b = refs[j]
                if (!a.value.isSet || !b.value.isSet) continue
                if (a.value.conflictsWith(b.value)) out += a to b
            }
        }
        return out
    }

    /** [ref] 与其它绑定的冲突方 */
    fun conflictsFor(ref: Ref, refs: List<Ref>): List<Ref> =
        refs.filter { it !== ref && it.value.isSet && ref.value.isSet && ref.value.conflictsWith(it.value) }

    /** 原版默认键位对应功能名，没有返回 null */
    fun vanillaNameFor(code: Int): String? = vanilla.firstOrNull { it.first == code }?.second

    /** [ref] 撞上的原版默认键位（返回原版功能名，空表示不冲突） */
    fun vanillaConflicts(ref: Ref): String? {
        if (!ref.value.isSet) return null
        return vanillaNameFor(ref.value.primary)
    }

    /** 整表快照："模块名.字段名" → "42+33" */
    fun snapshot(refs: List<Ref> = collect(me.sorihiko.dango.DangoMusic.instance.moduleManager)): Map<String, String> =
        refs.associate { it.key to it.value.storage() }

    /**
     * 整表恢复。未知键忽略（兼容旧存档），非法值由 [KeyBindValue.load] 过滤；
     * 返回实际恢复的条目数。
     */
    fun restore(map: Map<String, String>, refs: List<Ref>): Int {
        var applied = 0
        for (ref in refs) {
            val raw = map[ref.key] ?: continue
            ref.value.load(raw)
            applied++
        }
        return applied
    }

    /** 全部绑定恢复默认键位 */
    fun resetAll(refs: List<Ref>) {
        refs.forEach { it.value.resetDefault() }
    }

    /** 冲突描述（设置页提示用）：客户端内冲突优先，其次原版键位 */
    fun describeConflict(ref: Ref, refs: List<Ref>): String? {
        val inner = conflictsFor(ref, refs).firstOrNull()
        if (inner != null) return "${inner.module} · ${inner.label}"
        return vanillaConflicts(ref)?.let { "原版 $it" }
    }

    /** 键码显示名的转出（保持设置页 / 日志文案一致） */
    fun label(code: Int): String = KeyBindValue.labelOf(code)

    /** 解析 "42+33"；非法返回空数组（供第三方输入设备映射复用） */
    fun parse(raw: String): IntArray {
        val probe = KeyBindValue()
        probe.load(raw)
        if (!probe.isSet) return IntArray(0)
        return intArrayOf(*probe.modifiers, probe.primary)
    }

    /** 组合描述是否包含修饰键（键盘 / 鼠标混用时的判定入口） */
    fun modifierLabels(value: KeyBindValue): List<String> = value.modifiers.map { label(it) }

    /** 是否属于修饰键（录制时忽略） */
    fun isModifier(code: Int): Boolean = Keys.isModifier(code) || code in -104..-100
}
