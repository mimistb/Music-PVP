package me.sorihiko.dango.hud

import glidehud.api.KeyStateProvider
import glidehud.api.TargetInfoProvider

/**
 * 游戏层（1.8.9）在启动时把按键 / 准星目标的取数实现放进来；
 * 按键显示、敌人状态这类 HUD 模块只读这里的数据，自己不做任何游戏查询。
 */
object HudData {

    @Volatile
    var keys: KeyStateProvider? = null

    @Volatile
    var targets: TargetInfoProvider? = null

    /** 目标的世界坐标（敌人状态面板跟着人走时用） */
    @Volatile
    var targetScreen: TargetScreen? = null
}
