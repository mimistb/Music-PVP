package me.sorihiko.dango.module.values

import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.platform.Platform

/**
 * 组合键绑定：单键、Shift/Ctrl/Alt + 主键都支持。
 *
 * 存储结构是一个键码数组（修饰键在前、主键在最后），配置文件里写成 "42+33" 这样的字符串；
 * 读旧配置时如果拿到的是单个数字（旧的 IntValue 键码），会自动当作单键。
 *
 * [defaultCodes] 是默认键位：重置 / 首次运行都回到它，保证移植前的默认键位继续可用。
 * [allowEmpty] 为 false 时不允许清空（例如「打开播放器」必须始终有键）。
 */
class KeyBindValue(
    private val defaultCodes: IntArray = IntArray(0),
    val allowEmpty: Boolean = true,
) {

    private var codes: IntArray = defaultCodes.copyOf()

    /** 主键码（数组最后一个），没有绑定时是 0 */
    val primary: Int get() = codes.lastOrNull() ?: 0

    /** 修饰键码（主键之前的部分） */
    val modifiers: IntArray
        get() = if (codes.isEmpty()) IntArray(0) else codes.copyOf(codes.size - 1)

    val isSet: Boolean get() = codes.isNotEmpty()

    /** 写回默认键位 */
    fun resetDefault() {
        codes = defaultCodes.copyOf()
    }

    /** 清空绑定（allowEmpty 为 false 时忽略） */
    fun clear() {
        if (!allowEmpty) return
        codes = IntArray(0)
    }

    /** 设置组合键；key 无效（0 或负数）时按清空处理 */
    fun setCombo(modifierCodes: Collection<Int>, key: Int) {
        if (key <= 0) {
            clear()
            return
        }
        val mods = LinkedHashSet<Int>()
        for (code in modifierCodes) {
            if (code > 0 && code != key) mods += code
        }
        codes = IntArray(mods.size + 1).also { out ->
            mods.forEachIndexed { i, code -> out[i] = code }
            out[mods.size] = key
        }
    }

    /** 保存格式：修饰键 + 主键，用 '+' 连接；空绑定存空串 */
    fun storage(): String = codes.joinToString("+")

    /**
     * 读取保存格式。兼容旧配置：纯数字（旧 IntValue 键码）按单键处理；
     * 非法键码（0、超出 LWJGL 范围）被过滤，全部过滤后视为空绑定。
     */
    fun load(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || text == "0") {
            codes = IntArray(0)
            return
        }
        val parts = text.split('+').mapNotNull { it.trim().toIntOrNull() }
        // 键盘码 1..0xFFFF（含第三方输入设备 / 自定义键码映射），鼠标按键 -200..-1
        val valid = parts.filter { it in 1..0xFFFF || it in -200..-1 }
        codes = when {
            valid.isEmpty() -> IntArray(0)
            // 旧配置可能只有修饰键：视作无效，保持空绑定
            else -> valid.toIntArray()
        }
    }

    /** 显示名：Ctrl + Shift + A；没有绑定时显示「无」 */
    fun displayName(): String {
        if (codes.isEmpty()) return "无"
        return codes.joinToString(" + ") { labelOf(it) }
    }

    /**
     * 按住判断：组合里的每个键都必须按着。
     * 空绑定永远返回 false。
     */
    fun isDown(platform: Platform): Boolean {
        if (codes.isEmpty()) return false
        for (code in codes) {
            if (!down(platform, code)) return false
        }
        return true
    }

    /** 界面按键回调：主键匹配且修饰键按着时返回 true */
    fun matchesPress(keyCode: Int, platform: Platform): Boolean {
        if (primary != keyCode || primary <= 0) return false
        for (mod in modifiers) {
            if (!down(platform, mod)) return false
        }
        return true
    }

    /**
     * 冲突判断：主键相同，且修饰键集合互相包含（任意一方是另一方的子集）。
     * 例如 A 与 Ctrl+A 也会冲突——按住 Ctrl+A 时，只认 A 的绑定同样会被触发。
     */
    fun conflictsWith(other: KeyBindValue): Boolean {
        if (primary <= 0 || primary != other.primary) return false
        val mine = modifiers.toHashSet()
        val theirs = other.modifiers.toHashSet()
        return mine.containsAll(theirs) || theirs.containsAll(mine)
    }

    private fun down(platform: Platform, code: Int): Boolean = try {
        platform.isKeyDown(code)
    } catch (_: Throwable) {
        false
    }

    companion object {
        /** 鼠标按键（平台层用负数码上报，和键盘码区分） */
        const val MOUSE_LEFT = -100
        const val MOUSE_RIGHT = -101
        const val MOUSE_MIDDLE = -102
        const val MOUSE_SIDE1 = -103
        const val MOUSE_SIDE2 = -104

        /** 修饰键码 → 显示名 */
        fun labelOf(code: Int): String = when (code) {
            Keys.LSHIFT, Keys.RSHIFT -> "Shift"
            Keys.LCONTROL, Keys.RCONTROL -> "Ctrl"
            Keys.LMENU, Keys.RMENU -> "Alt"
            219, 220 -> "Win"
            MOUSE_LEFT -> "鼠标左键"
            MOUSE_RIGHT -> "鼠标右键"
            MOUSE_MIDDLE -> "鼠标中键"
            MOUSE_SIDE1 -> "鼠标侧键1"
            MOUSE_SIDE2 -> "鼠标侧键2"
            else -> Keys.name(code) ?: "#$code"
        }
    }
}
