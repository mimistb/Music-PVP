package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.platform.Keys

import kotlin.math.max
import kotlin.math.min

/**
 * 单行文本框的编辑状态：光标 + 选区 + 复制 / 粘贴 / 剪切 / 全选。
 *
 * <p>界面里所有输入框（搜索、筛选、房间码、设置里的文字与数值）共用这一份逻辑，
 * 因此 Ctrl+C / Ctrl+V / Ctrl+X / Ctrl+A 在哪儿都能用，剪贴板走游戏自带的系统剪贴板。
 *
 * <p>[accept] 负责过滤 / 转换字符：返回 null 表示这个字符不接受，返回别的字符表示替换成它
 * （例如房间码只收字母数字并转大写）。
 */
internal class TextInput(
    var maxLength: Int,
    var accept: (Char) -> Char? = { if (Keys.isAllowedChar(it)) it else null },
) {
    var text = ""
        private set

    /** 光标位置：0 .. text.length */
    var caret = 0
        private set

    /** 选区另一端；和 [caret] 相同表示没有选中 */
    private var anchor = 0

    val selStart: Int get() = min(caret, anchor)
    val selEnd: Int get() = max(caret, anchor)
    val hasSelection: Boolean get() = anchor != caret

    /** 换掉整段文字，光标放到末尾 */
    fun set(value: String) {
        text = sanitize(value).take(maxLength)
        caret = text.length
        anchor = caret
    }

    fun clear() {
        text = ""
        caret = 0
        anchor = 0
    }

    /**
     * 显示用：在光标处插入闪烁竖线。
     * [mask] 不为空时每个字符都显示成该掩码（密码框 / API Key）。
     */
    fun display(blink: Boolean, mask: Char? = null): String {
        val base = if (mask == null) text else mask.toString().repeat(text.length)
        if (!blink || caret > base.length) return base
        return base.substring(0, caret) + "|" + base.substring(caret)
    }

    fun selectAll() {
        anchor = 0
        caret = text.length
    }

    /**
     * 按键：方向键 / Home / End / 退格 / Delete / Ctrl+A / C / X / V / 可打印字符。
     * 返回 true 表示这个按键被文本框吃掉了。
     */
    fun handleKey(typedChar: Char, keyCode: Int): Boolean {
        if (DangoClient.platform.ctrlDown) {
            when (keyCode) {
                Keys.A -> { selectAll(); return true }
                Keys.C -> { copy(); return true }
                Keys.X -> { cut(); return true }
                Keys.V -> { paste(); return true }
            }
        }
        when (keyCode) {
            Keys.BACK -> { backspace(); return true }
            Keys.DELETE -> { delete(); return true }
            Keys.LEFT -> { moveCaret(-1); return true }
            Keys.RIGHT -> { moveCaret(1); return true }
            Keys.HOME -> { caret = 0; anchor = 0; return true }
            Keys.END -> { caret = text.length; anchor = caret; return true }
        }
        val c = accept(typedChar) ?: return false
        insert(c.toString())
        return true
    }

    private fun sanitize(value: String): String {
        val out = StringBuilder(value.length)
        for (c in value) accept(c)?.let { out.append(it) }
        return out.toString()
    }

    private fun insert(s: String) {
        val base = if (hasSelection) text.removeRange(selStart, selEnd) else text
        val at = selStart
        val room = maxLength - base.length
        if (room <= 0 || s.isEmpty()) {
            text = base
            caret = at
            anchor = at
            return
        }
        val add = s.take(room)
        text = base.substring(0, at) + add + base.substring(at)
        caret = at + add.length
        anchor = caret
    }

    private fun moveCaret(delta: Int) {
        if (hasSelection) {
            caret = if (delta < 0) selStart else selEnd
            anchor = caret
            return
        }
        caret = (caret + delta).coerceIn(0, text.length)
        anchor = caret
    }

    private fun backspace() {
        if (hasSelection) {
            text = text.removeRange(selStart, selEnd)
            caret = selStart
            anchor = caret
            return
        }
        if (caret <= 0) return
        text = text.removeRange(caret - 1, caret)
        caret--
        anchor = caret
    }

    private fun delete() {
        if (hasSelection) {
            text = text.removeRange(selStart, selEnd)
            caret = selStart
            anchor = caret
            return
        }
        if (caret >= text.length) return
        text = text.removeRange(caret, caret + 1)
    }

    /** 没有选区时复制整段（单行输入框没有 Shift 选择，直接复制全部更顺手） */
    private fun copy() {
        val s = if (hasSelection) text.substring(selStart, selEnd) else text
        if (s.isNotEmpty()) DangoClient.platform.clipboard = s
    }

    private fun cut() {
        val s = if (hasSelection) text.substring(selStart, selEnd) else text
        if (s.isEmpty()) return
        DangoClient.platform.clipboard = s
        if (hasSelection) {
            text = text.removeRange(selStart, selEnd)
            caret = selStart
            anchor = caret
        } else {
            clear()
        }
    }

    private fun paste() {
        val clip = DangoClient.platform.clipboard.replace(Regex("[\\r\\n\\t]"), " ")
        if (clip.isEmpty()) return
        insert(sanitize(clip))
    }
}
