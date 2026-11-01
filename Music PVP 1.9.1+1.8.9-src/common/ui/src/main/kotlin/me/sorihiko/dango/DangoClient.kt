package me.sorihiko.dango

import me.sorihiko.dango.gfx.Gfx
import me.sorihiko.dango.module.impls.music.AutoLyricsModule
import me.sorihiko.dango.module.impls.music.BedWarsModule
import me.sorihiko.dango.module.impls.music.DynamicIslandModule
import me.sorihiko.dango.module.impls.music.FpsToolsModule
import me.sorihiko.dango.module.impls.music.HitColorHudModule
import me.sorihiko.dango.module.impls.music.HudInfoModule
import me.sorihiko.dango.module.impls.music.HudLayoutModule
import me.sorihiko.dango.module.impls.music.KeystrokesHudModule
import me.sorihiko.dango.module.impls.music.Lyrics2DModule
import me.sorihiko.dango.module.impls.music.LyricsModule
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.module.impls.music.SongCardModule
import me.sorihiko.dango.module.impls.music.TabStatsHudModule
import me.sorihiko.dango.module.impls.music.TargetInfoHudModule
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.platform.Platform

/**
 * 客户端入口：平台层在 mod 初始化时调用 [start]，把游戏接口（[Platform]）和绘图接口（[Gfx]）交进来。
 */
object DangoClient {

    lateinit var gfx: Gfx
        private set

    val platform: Platform get() = DangoMusic.instance.platform

    @JvmStatic
    fun start(platform: Platform, gfx: Gfx) {
        this.gfx = gfx
        DangoMusic.start(
            platform,
            listOf(
                NeteaseMusicModule(),
                LyricsModule(),
                AutoLyricsModule(),
                SongCardModule(),
                DynamicIslandModule(),
                HudLayoutModule(),
                Lyrics2DModule(),
                HudInfoModule(),
                KeystrokesHudModule(),
                TargetInfoHudModule(),
                TabStatsHudModule(),
                BedWarsModule(),
                HitColorHudModule(),
                FpsToolsModule(),
            ),
        )
    }

    /** 平台层打开播放器界面时用：新建一个播放器界面，[close] 由平台层提供（关掉这个界面） */
    @JvmStatic
    fun newMusicScreen(close: () -> Unit): MusicScreen =
        MusicScreen(DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java), close)
}
