package me.sorihiko.dango.hud

/**
 * 准星目标的屏幕定位数据源：头顶点 / 下巴点的世界坐标。
 * 由 1.8.9 平台层实现，共用界面层只拿世界坐标，自己按摄像机朝向投影到屏幕。
 */
interface TargetScreen {

    /** 目标头顶的世界坐标：out[0..2] = x, y, z；没有目标时返回 false */
    fun headPos(out: DoubleArray): Boolean

    /** 目标下巴（头部下沿）的世界坐标；没有目标时返回 false */
    fun chinPos(out: DoubleArray): Boolean
}
