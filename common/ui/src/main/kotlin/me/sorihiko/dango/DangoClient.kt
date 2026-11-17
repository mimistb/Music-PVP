package me.sorihiko.dango

import me.sorihiko.dango.gfx.Gfx
import me.sorihiko.dango.module.impls.music.AntiCheatModule
import me.sorihiko.dango.module.impls.music.AutoBlacklistModule
import me.sorihiko.dango.module.impls.music.AutoLyricsModule
import me.sorihiko.dango.module.impls.music.ArmorHudModule
import me.sorihiko.dango.module.impls.music.BedWarsModule
import me.sorihiko.dango.module.impls.music.BloodParticlesModule
import me.sorihiko.dango.module.impls.music.DynamicIslandModule
import me.sorihiko.dango.module.impls.music.FpsToolsModule
import me.sorihiko.dango.module.impls.music.GaleAntiAimModule
import me.sorihiko.dango.module.impls.music.ChatTranslateSettingsModule
import me.sorihiko.dango.module.impls.music.CustomHeldItemsModule
import me.sorihiko.dango.module.impls.music.HitColorHudModule
import me.sorihiko.dango.module.impls.music.HudInfoModule
import me.sorihiko.dango.module.impls.music.HudLayoutModule
import me.sorihiko.dango.module.impls.music.InventoryDisplayHudModule
import me.sorihiko.dango.module.impls.music.KeystrokesHudModule
import me.sorihiko.dango.module.impls.music.KillIconHudModule
import me.sorihiko.dango.module.impls.music.Lyrics2DModule
import me.sorihiko.dango.module.impls.music.LyricsModule
import me.sorihiko.dango.module.impls.music.MechvibesModule
import me.sorihiko.dango.module.impls.music.NametagStatsModule
import me.sorihiko.dango.module.impls.music.PotionStatusHudModule
import me.sorihiko.dango.module.impls.music.ProjectileTrailModule
import me.sorihiko.dango.module.impls.music.ScoreboardModule
import me.sorihiko.dango.module.impls.music.ShinyPotsModule
import me.sorihiko.dango.module.impls.music.SniperWarningModule
import me.sorihiko.dango.module.impls.music.SlowSwingModule
import me.sorihiko.dango.module.impls.music.SpeedometerHudModule
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.module.impls.music.PartyVoiceKeyModule
import me.sorihiko.dango.module.impls.music.SongCardModule
import me.sorihiko.dango.module.impls.music.TabStatsHudModule
import me.sorihiko.dango.module.impls.music.TargetInfoModule
import me.sorihiko.dango.module.impls.music.WeatherChangerModule
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.module.Module
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
        DangoMusic.start(platform, defaultModules())
    }

    /**
     * 全部内置模块。列表顺序就是加载顺序（init + 事件监听注册），
     * 兼容性测试复用这一份清单，避免测试和真实注册表各维护一套。
     */
    @JvmStatic
    fun defaultModules(): List<Module> = listOf(
        NeteaseMusicModule(),
        PartyVoiceKeyModule(),
        LyricsModule(),
        AutoLyricsModule(),
        SongCardModule(),
        DynamicIslandModule(),
        HudLayoutModule(),
        Lyrics2DModule(),
        HudInfoModule(),
        KeystrokesHudModule(),
        TargetInfoModule(),
        ArmorHudModule(),
        TabStatsHudModule(),
        BedWarsModule(),
        NametagStatsModule(),
        HitColorHudModule(),
        KillIconHudModule(),
        ChatTranslateSettingsModule(),
        FpsToolsModule(),
        PotionStatusHudModule(),
        ProjectileTrailModule(),
        ScoreboardModule(),
        ShinyPotsModule(),
        SlowSwingModule(),
        SpeedometerHudModule(),
        WeatherChangerModule(),
        InventoryDisplayHudModule(),
        MechvibesModule(),
        BloodParticlesModule(),
        CustomHeldItemsModule(),
        GaleAntiAimModule(),
        AntiCheatModule(),
        AutoBlacklistModule(),
        SniperWarningModule(),
    )

    /** 平台层打开播放器界面时用：新建一个播放器界面，[close] 由平台层提供（关掉这个界面） */
    @JvmStatic
    fun newMusicScreen(close: () -> Unit): MusicScreen =
        MusicScreen(DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java), close)
}
