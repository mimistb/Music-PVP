package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue

/**
 * 组件位置：游戏里所有 HUD 组件的整体偏移 / 微调。
 *
 * 灵动岛、歌曲卡片打开聊天栏后可以直接拖动，这里存的就是拖动后的位置；
 * 也可以在这里用滑条一点一点微调。单位都是"界面逻辑像素"（1280×800 的坐标系），
 * 只有左上角的"正在播放"用的是原版 GUI 像素。
 */
@ModuleMeta(Category.MUSIC, "HudLayout")
class HudLayoutModule : Module() {

    @ModuleValue("灵动岛水平偏移", "向左 / 向右；打开聊天栏后可直接拖动灵动岛")
    val islandX = FloatValue(-400f, 400f, 0f, 0)

    @ModuleValue("灵动岛垂直偏移", "向上 / 向下；打开聊天栏后可直接拖动灵动岛")
    val islandY = FloatValue(-300f, 300f, 0f, 0)

    @ModuleValue("歌曲卡片水平偏移", "在歌曲卡片自己的 X 坐标上再微调")
    val cardX = FloatValue(-400f, 400f, 0f, 0)

    @ModuleValue("歌曲卡片垂直偏移", "在歌曲卡片自己的 Y 坐标上再微调")
    val cardY = FloatValue(-300f, 300f, 0f, 0)

    @ModuleValue("正在播放水平偏移", "左上角正在播放信息的水平微调（原版 GUI 像素）")
    val nowX = FloatValue(-300f, 300f, 0f, 0)

    @ModuleValue("正在播放垂直偏移", "左上角正在播放信息的垂直微调（原版 GUI 像素）")
    val nowY = FloatValue(-200f, 200f, 0f, 0)

    // ---------------------------------------------------------------- 拖动吸附

    @ModuleValue("启用拖动吸附", "拖动 HUD 时自动对齐；关闭后完全跟手")
    val snapEnabled = BooleanValue(true)

    @ModuleValue("吸附模式", "位掩码：1=对齐元素 2=网格 4=屏幕边缘；默认三种全开")
    val snapMask = IntValue(0, 7, 7)

    @ModuleValue("吸附灵敏度", "距目标多少像素内触发吸附（像素）")
    val snapTolerance = FloatValue(1f, 24f, 6f, 0)

    @ModuleValue("网格尺寸", "网格吸附的格子大小（像素）")
    val gridSize = FloatValue(2f, 40f, 8f, 0)

    @ModuleValue("吸附参考线", "吸附触发时显示对齐参考线")
    val snapGuides = BooleanValue(true)

    /** HUD 布局是其它 HUD 组件的基础配置，配置里缺键时默认开启 */
    override val defaultEnabled: Boolean get() = true

    override fun init() {
        instance = this
    }

    companion object {
        @Volatile
        private var instance: HudLayoutModule? = null

        /** 各 HUD 组件读偏移用；模块还没初始化时返回 null */
        fun of(): HudLayoutModule? = instance
    }
}
