package me.sorihiko.dango.forge

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.module.impls.music.HitColorHudModule

import java.lang.reflect.Field

/**
 * 打击颜色桥：把设置里的颜色写进 coremod 在 RendererLivingEntity 上加的四个静态字段。
 *
 * 类由 coremod（[me.sorihiko.dango.core.HitColorTransformer]）在加载时插入，
 * 所以这里只在运行时反射取字段，不直接引用游戏类，避免开发环境 / 无补丁环境崩溃。
 * 取不到就只停用打击颜色，不影响其它任何功能。
 */
object HitColorBridge {

    private const val CLASS_NAME = "net.minecraft.client.renderer.entity.RendererLivingEntity"
    private val FIELD_NAMES = arrayOf("dango\$hitR", "dango\$hitG", "dango\$hitB", "dango\$hitA")

    private var fields: Array<Field>? = null
    private var failed = false

    /** 每个客户端 tick 调用一次（在模块事件之前） */
    fun tick() {
        val module = HitColorHudModule.of() ?: return
        module.update()
        if (failed) return

        val f = fields ?: try {
            val type = Class.forName(CLASS_NAME, false, HitColorBridge::class.java.classLoader)
            val arr = Array(4) { i ->
                type.getDeclaredField(FIELD_NAMES[i]).apply { isAccessible = true }
            }
            fields = arr
            arr
        } catch (t: Throwable) {
            failed = true
            DangoLogger.error("HitColor: {} 上没有补丁字段（coremod 未生效），打击颜色停用", CLASS_NAME)
            return
        }

        try {
            val rgba = module.rgba()
            for (i in 0..3) f[i].setFloat(null, rgba[i])
        } catch (t: Throwable) {
            failed = true
            DangoLogger.error("HitColor: 写入受伤闪光颜色失败，打击颜色停用", t)
        }
    }
}
