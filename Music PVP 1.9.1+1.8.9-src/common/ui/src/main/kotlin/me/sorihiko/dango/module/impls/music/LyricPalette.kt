package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.utils.Color

/**
 * 歌词颜色：金 / 红 / 粉 / 白。
 * 顺序和设置页里的色块完全一致（0 = 金，1 = 红，2 = 粉，3 = 白）。
 */
object LyricPalette {

    val all = arrayOf(
        Color(1f, 0.74f, 0.30f, 1f),   // 金
        Color(1f, 0.27f, 0.34f, 1f),   // 红
        Color(1f, 0.45f, 0.70f, 1f),   // 粉
        Color(1f, 1f, 1f, 0.96f),      // 白
    )

    fun of(index: Int): Color = all[index.coerceIn(0, all.size - 1)]
}
