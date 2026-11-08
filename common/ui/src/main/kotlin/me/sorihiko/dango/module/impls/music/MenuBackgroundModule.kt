package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.module.values.StringValue

/**
 * 主标题界面（GuiMainMenu）的自定义背景。
 *
 * 图片由 coremod 侧异步读取并上传纹理，这里只负责保存玩家选择：
 * 关闭时保留原版液态玻璃底，开启后按填充模式铺在渐变底之上，再叠一层暗色遮罩保证标题可读。
 */
@ModuleMeta(Category.MUSIC, "MenuBackground")
class MenuBackgroundModule : Module() {

    @ModuleValue("图片路径", "本地图片绝对路径（png / jpg / bmp），可点下方「浏览」选择")
    val path = StringValue("")

    @ModuleValue("不透明度", "背景图片整体透明度")
    val opacity = IntValue(0, 100, 65)

    @ModuleValue("填充模式", "拉伸铺满 / 居中原始比例 / 平铺重复")
    val fillMode = IntValue(0, 2, 0)

    @ModuleValue("暗色遮罩", "图片上叠一层黑色遮罩，数值越大标题越清晰")
    val dim = IntValue(0, 80, 22)

    /** 旧配置没有这一项时默认打开，玩家可以直接在设置页选图 */
    override fun init() {
        enabled = true
        instance = this
    }

    companion object {
        @Volatile
        private var instance: MenuBackgroundModule? = null

        @JvmStatic
        fun of(): MenuBackgroundModule? = instance
    }
}
