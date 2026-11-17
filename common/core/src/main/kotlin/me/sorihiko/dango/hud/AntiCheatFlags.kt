package me.sorihiko.dango.hud

import java.util.Locale

/**
 * 反作弊标记的共享登记簿。
 *
 * legacy 层（AntiSnipeRuntime）在判定作弊时写入，common/ui 层（TabStats）读取并标红。
 * 只保存「小写玩家名 → 标记时间戳」，5 分钟后自动过期，避免换局 / 长时间挂机后残留红名。
 *
 * 调用方全部在客户端主线程；这里加锁只是防御性保证读写原子性，
 * 统计回调等异步路径万一误调也不会破坏内部状态。
 */
object AntiCheatFlags {

    private const val TTL_MS = 5 * 60 * 1000L

    private val flags = HashMap<String, Long>()

    /** 记录一次作弊标记；名字为空时直接忽略。 */
    fun mark(name: String) {
        val key = key(name) ?: return
        synchronized(flags) { flags[key] = System.currentTimeMillis() }
    }

    /** 该玩家当前是否处于被标记状态（超过 TTL 的旧标记会被顺手清掉）。 */
    fun isFlagged(name: String): Boolean {
        val key = key(name) ?: return false
        val now = System.currentTimeMillis()
        synchronized(flags) {
            val at = flags[key] ?: return false
            if (now - at <= TTL_MS) return true
            flags.remove(key)
            return false
        }
    }

    /** 换世界时清空，避免上一局的标记带到下一局。 */
    fun clear() {
        synchronized(flags) { flags.clear() }
    }

    private fun key(name: String): String? {
        val trimmed = name.trim()
        return if (trimmed.isEmpty()) null else trimmed.lowercase(Locale.ROOT)
    }
}
