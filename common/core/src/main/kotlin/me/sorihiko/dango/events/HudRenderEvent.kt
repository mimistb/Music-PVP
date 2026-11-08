package me.sorihiko.dango.events

import me.sorihiko.dango.event.Event

/**
 * 2D HUD 组件的最终绘制时机。
 *
 * 与 [WorldRenderEndEvent] 不同，这个事件在所有原版 GUI（聊天栏、计分板、TAB 列表）之后派发，
 * 用来保证自定义 HUD 永远画在最上层，不会被聊天栏或其它界面盖住。
 */
data class HudRenderEvent(
    val partialTicks: Float,
) : Event
