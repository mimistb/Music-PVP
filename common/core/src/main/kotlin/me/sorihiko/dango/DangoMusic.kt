package me.sorihiko.dango

import me.sorihiko.dango.config.ConfigManager
import me.sorihiko.dango.event.Event
import me.sorihiko.dango.event.EventManager
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleManager
import me.sorihiko.dango.platform.Platform

import java.nio.file.Files
import java.nio.file.Path

/**
 * Dango Music 的核心：事件系统 + 模块 + 设置。所有 Minecraft 版本共用这一份。
 * 和游戏打交道的地方都通过 [Platform]，由各版本的平台层在启动时传进来。
 */
class DangoMusic private constructor(val platform: Platform) {
    companion object {
        /** Forge / 资源命名空间用的 modid，不能带空格，改显示名时保持不动 */
        const val MOD_ID = "dangomusic"
        const val NAME = "Dango Music"

        /** 配置文件目录名：.minecraft/config/Music PVP */
        const val CONFIG_DIR = "Music PVP"

        lateinit var instance: DangoMusic
            private set

        /** 平台层在 mod 初始化时调用：注册模块、读取设置 */
        @JvmStatic
        fun start(platform: Platform, modules: List<Module>) {
            instance = DangoMusic(platform)
            instance.init(modules)
        }

        @JvmStatic
        fun callEvent(event: Event) {
            if (::instance.isInitialized) instance.eventManager.callEvent(event)
        }
    }

    /** 配置、登录凭证、缓存都放在 .minecraft/config/Music PVP/ */
    val clientDir: Path = platform.configDir

    val eventManager = EventManager()
    val moduleManager = ModuleManager(eventManager)
    val configManager = ConfigManager(moduleManager, clientDir.resolve("config.json"))

    private fun init(modules: List<Module>) {
        migrateLegacyConfigDir()
        clientDir.toFile().mkdirs()

        modules.forEach { moduleManager.register(it) }
        moduleManager.load()

        configManager.load()
        eventManager.addListener(configManager)
        configManager.installShutdownHook()
    }

    /**
     * 旧版本的目录是 config/dangomusic。改名后新目录还不存在时把旧目录整体搬过来，
     * 这样升级后设置、登录 Cookie 和本地音乐库都不会丢。
     */
    private fun migrateLegacyConfigDir() {
        val legacy = clientDir.resolveSibling(MOD_ID)
        if (Files.exists(clientDir) || !Files.exists(legacy)) return
        try {
            Files.move(legacy, clientDir)
        } catch (ignored: Exception) {
            // 移动失败（占用、权限等）就保持旧目录，不影响启动
        }
    }
}
