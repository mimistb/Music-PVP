package me.sorihiko.dango.module.values

/**
 * 字符串设置项（API Key、查询玩家名这类不能当数字用的输入）。
 * 值在 tick / 后台线程里读写，所以用 @Volatile；长度上限避免配置文件被写爆。
 */
class StringValue(value: String = "") {

    @Volatile
    private var value: String = normalize(value)

    fun get(): String = value

    fun set(value: String) {
        this.value = normalize(value)
    }

    private fun normalize(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.length <= MAX_LENGTH) trimmed else trimmed.substring(0, MAX_LENGTH)
    }

    companion object {
        const val MAX_LENGTH = 128
    }
}
