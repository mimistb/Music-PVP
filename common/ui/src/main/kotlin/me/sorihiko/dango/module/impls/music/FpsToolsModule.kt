package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.module.values.StringValue
import me.sorihiko.dango.utils.Color

/**
 * PVP 工具的开关容器：所有字段都是 [ModuleValue]，由 ConfigManager 自动存盘，
 * 每 tick 被 legacy 的 FpsPortRuntime 读出来同步进 fpsport 的静态字段。
 *
 * 只做配置与展示，逻辑全部在 fpsport / FpsPortRuntime，不在这里画任何东西。
 */
@ModuleMeta(Category.MUSIC, "FpsTools")
class FpsToolsModule : Module() {

    // ---------------------------------------------------------------- 打击 · 粒子

    @ModuleValue("无打击延迟", "左键后每 tick 清零原版攻击冷却")
    val noHitDelay = BooleanValue(true)

    @ModuleValue("关闭受伤抖动", "受击时镜头不再晃动")
    val noHurtCam = BooleanValue(true)

    @ModuleValue("伤害指示", "命中后飘出伤害数字")
    val damageIndicator = BooleanValue(true)

    @ModuleValue("更多粒子", "暴击 / 锋利 / 命中特效")
    val moreParticles = BooleanValue(true)

    @ModuleValue("锋利粒子数", "每次挥击 0 ~ 10 个")
    val mpSharpness = IntValue(0, 10, 2)

    @ModuleValue("暴击粒子数", "每次暴击 0 ~ 10 个")
    val mpCrit = IntValue(0, 10, 2)

    @ModuleValue("始终补暴击粒子", "不要求下落攻击也补暴击粒子")
    val mpAlwaysCrit = BooleanValue(false)

    @ModuleValue("始终补锋利粒子", "不要求附魔锋利也补魔法粒子")
    val mpAlwaysSharpness = BooleanValue(false)

    @ModuleValue("命中特效", "无 / 心形 / 火焰 / 血")
    val mpSpecial = IntValue(0, 3, 0)

    @ModuleValue("击杀特效", "无 / 闪电 / 爆炸")
    val mpKillEffect = IntValue(0, 2, 0)

    @ModuleValue("伤害数字大小", "0.03 ~ 0.15")
    val diScale = FloatValue(0.03f, 0.15f, 0.065f, 3)

    @ModuleValue("粒子控制", "关闭后丢弃全部粒子效果")
    val particlesModifier = BooleanValue(false)

    @ModuleValue("保留方块破坏粒子", "关闭后连方块破坏粒子一起丢")
    val pmKeepBlock = BooleanValue(true)

    // ---------------------------------------------------------------- 视角 · 迷雾 · 名牌

    @ModuleValue("平滑缩放", "按住缩放键改变视野")
    val smoothZoom = BooleanValue(true)

    @ModuleValue("缩放按键", "LWJGL 键码，0 = 不使用")
    val keyZoom = IntValue(0, 255, 46)

    @ModuleValue("视野缓动", "缩放时视野平滑过渡")
    val zoomSmoothCamera = BooleanValue(false)

    @ModuleValue("滚轮微调倍率", "缩放时滚轮改倍率而不切物品")
    val zoomWheel = BooleanValue(false)

    @ModuleValue("缓动速度", "1 ~ 10")
    val zoomSpeed = FloatValue(1f, 10f, 4f, 1)

    @ModuleValue("缩放倍率", "1 ~ 20 倍")
    val zoomScale = FloatValue(1f, 20f, 4f, 1)

    @ModuleValue("原版平滑相机", "缩放时开启原版的平滑视角")
    val zoomSmoothMouse = BooleanValue(false)

    @ModuleValue("自由视角", "按住切第三人称，松开还原")
    val freeLook = BooleanValue(false)

    @ModuleValue("自由视角按键", "LWJGL 键码")
    val keyFreeLook = IntValue(0, 255, 56)

    @ModuleValue("自由视角灵敏度", "0.2 ~ 3.0 倍")
    val freeLookSens = FloatValue(0.2f, 3f, 1f, 2)

    @ModuleValue("自定义迷雾", "只改雾的颜色（距离不可改）")
    val customFog = BooleanValue(false)

    @ModuleValue("雾颜色", "青 / 白 / 黑 / 紫")
    val fogColor = IntValue(0, 3, 0)

    @ModuleValue("水/岩浆内生效", "默认只在空气中改色")
    val fogLiquids = BooleanValue(false)

    @ModuleValue("关闭火焰遮挡", "第一人称着火时不再挡视野")
    val fireModifier = BooleanValue(false)

    @ModuleValue("名牌过滤", "隐藏 NPC 或自己的名牌")
    val levelTag = BooleanValue(false)

    @ModuleValue("显示自己名牌", "关闭则不显示自己的名字")
    val levelShowSelf = BooleanValue(true)

    @ModuleValue("隐藏 NPC 名牌", "没有玩家信息的实体不显示")
    val levelHideNPC = BooleanValue(true)

    // ---------------------------------------------------------------- 自动化

    @ModuleValue("强制疾跑", "自动保持疾跑")
    val sprint = BooleanValue(false)

    @ModuleValue("按一下切换", "关闭则一直保持疾跑")
    val sprintToggle = BooleanValue(true)

    @ModuleValue("自动 GG", "对局结束时自动发言")
    val autoGG = BooleanValue(false)

    @ModuleValue("GG 内容", "发送的文字")
    val ggMessage = StringValue("gg")

    @ModuleValue("服务器模式", "Hypixel / 普通")
    val ggServer = IntValue(0, 1, 0)

    @ModuleValue("下一局延迟", "秒，0 ~ 30")
    val ggDelay = IntValue(0, 30, 5)

    @ModuleValue("自动下一局", "只对 Hypixel 的 /play 可点击消息生效")
    val ggAutoPlay = BooleanValue(false)

    @ModuleValue("快捷发言", "按下按键直接发送一条消息")
    val autoText = BooleanValue(false)

    @ModuleValue("快捷发言按键", "LWJGL 键码")
    val keyAutoText = IntValue(0, 255, 34)

    @ModuleValue("快捷发言内容", "要发送的文字")
    val msgAutoText = StringValue("gg")

    @ModuleValue("快捷发言 2 按键", "LWJGL 键码，0 = 不使用")
    val keyQuick2 = IntValue(0, 255, 0)

    @ModuleValue("快捷发言 2 内容", "要发送的文字")
    val msgQuick2 = StringValue("")

    @ModuleValue("快捷发言 3 按键", "LWJGL 键码，0 = 不使用")
    val keyQuick3 = IntValue(0, 255, 0)

    @ModuleValue("快捷发言 3 内容", "要发送的文字")
    val msgQuick3 = StringValue("")

    @ModuleValue("快捷发言 4 按键", "LWJGL 键码，0 = 不使用")
    val keyQuick4 = IntValue(0, 255, 0)

    @ModuleValue("快捷发言 4 内容", "要发送的文字")
    val msgQuick4 = StringValue("")

    // ---------------------------------------------------------------- 方块 · 物品 · 聊天 · 视角

    @ModuleValue("方块高亮", "准星指向的方块加填充与描边")
    val blockOverlay = BooleanValue(false)

    @ModuleValue("高亮填充", "半透明填充面")
    val boFill = BooleanValue(true)

    @ModuleValue("高亮描边", "方框线")
    val boOutline = BooleanValue(true)

    @ModuleValue("穿墙显示", "关闭深度测试，被遮挡也能看到")
    val boThrough = BooleanValue(false)

    @ModuleValue("描边宽度", "0.5 ~ 5 像素")
    val boWidth = FloatValue(0.5f, 5f, 1f, 1)

    @ModuleValue("填充颜色", "白 / 粉 / 蓝 / 金 / 绿 / 红 / 黑 / 浅灰")
    val boFillColor = IntValue(0, 7, 0)

    @ModuleValue("填充透明度", "0 ~ 100%")
    val boFillAlpha = IntValue(0, 100, 20)

    @ModuleValue("描边颜色", "白 / 粉 / 蓝 / 金 / 绿 / 红 / 黑 / 浅灰")
    val boOutlineColor = IntValue(0, 7, 0)

    @ModuleValue("描边透明度", "0 ~ 100%")
    val boOutlineAlpha = IntValue(0, 100, 100)

    @ModuleValue("物品物理", "掉落物随重力翻滚，落地回正")
    val itemPhysics = BooleanValue(false)

    @ModuleValue("翻转角度", "初始朝向，0 ~ 360 度")
    val ipRotate = FloatValue(0f, 360f, 90f, 0)

    @ModuleValue("渲染缩放", "3D 物品大小，0.2 ~ 1.0")
    val ipScale = FloatValue(0.2f, 1f, 0.5f, 2)

    @ModuleValue("翻滚速度", "空中每帧转多少度，0 ~ 10")
    val ipRollSpeed = FloatValue(0f, 10f, 1.1f, 1)

    @ModuleValue("随机偏移", "多数量物品的散开强度，0 ~ 0.5")
    val ipSpread = FloatValue(0f, 0.5f, 0.15f, 2)

    @ModuleValue("层叠间距", "平面物品堆叠间隔，0.01 ~ 0.2")
    val ipStackGap = FloatValue(0.01f, 0.2f, 0.05375f, 3)

    @ModuleValue("按数量渲染", "关闭后一堆物品也只画一份")
    val ipRespectCount = BooleanValue(true)

    @ModuleValue("清洁视角", "不再显示自己身上的药水粒子")
    val cleanView = BooleanValue(false)

    @ModuleValue("聊天增强", "折叠重复消息 / 复制消息")
    val betterChat = BooleanValue(false)

    @ModuleValue("折叠重复消息", "相同文字合并成一条并标记次数")
    val chatFold = BooleanValue(false)

    @ModuleValue("动作栏也折叠", "动作栏消息同样参与合并")
    val chatFoldActionBar = BooleanValue(false)

    @ModuleValue("消息复制按钮", "消息尾部追加 [C]，点击复制")
    val chatCopy = BooleanValue(false)

    @ModuleValue("复制最小长度", "少于该字符数不追加 [C]，0 ~ 32")
    val chatMinLength = IntValue(0, 32, 3)

    // ---------------------------------------------------------------- 信息胶囊：护甲 · 点击速度

    @ModuleValue("护甲显示", "在信息胶囊区域单独一块显示头盔到靴子的耐久")
    val armorDisplay = BooleanValue(false)

    @ModuleValue("显示部位名", "关闭后只显示耐久数值，更紧凑")
    val armorShowSlots = BooleanValue(true)

    @ModuleValue("显示空槽", "没有护甲的槽位用 – 占位")
    val armorShowEmpty = BooleanValue(false)

    @ModuleValue("耐久显示方式", "百分比 / 剩余-最大")
    val armorPercent = IntValue(0, 1, 0)

    @ModuleValue("只显示受损护甲", "满耐久的护甲不显示")
    val armorOnlyDamaged = BooleanValue(false)

    @ModuleValue("低耐久阈值", "低于该百分比时高亮，0 ~ 100")
    val armorLowThreshold = IntValue(0, 100, 20)

    @ModuleValue("低耐久高亮", "低于阈值时用告警色显示")
    val armorLowWarn = BooleanValue(true)

    @ModuleValue("点击速度", "在信息胶囊区域单独一块显示 CPS")
    val cpsDisplay = BooleanValue(false)

    @ModuleValue("显示左键", "统计左键点击")
    val cpsShowLeft = BooleanValue(true)

    @ModuleValue("显示右键", "统计右键点击")
    val cpsShowRight = BooleanValue(true)

    @ModuleValue("统计窗口", "统计最近多少毫秒，500 ~ 2000")
    val cpsWindow = IntValue(500, 2000, 1000)

    @ModuleValue("只在游戏内统计", "打开界面时的点击不计入")
    val cpsInGameOnly = BooleanValue(true)

    // ---------------------------------------------------------------- 第一人称动画

    @ModuleValue("防砍动画", "手持剑右键格挡时的第一人称动画")
    val blockAnim = BooleanValue(true)

    @ModuleValue("动画样式", "Lunar / 1.7 / Debug / Jello / Jigsaw / Luna / Push / Swang / Swank / Swong / Sigma")
    val blockStyle = IntValue(0, 10, 0)

    @ModuleValue("水平偏移", "-0.5 ~ 0.5 格")
    val blockOffX = FloatValue(-0.5f, 0.5f, 0f, 2)

    @ModuleValue("垂直偏移", "-0.5 ~ 0.5 格")
    val blockOffY = FloatValue(-0.5f, 0.5f, 0f, 2)

    @ModuleValue("前后偏移", "-0.5 ~ 0.5 格")
    val blockOffZ = FloatValue(-0.5f, 0.5f, 0f, 2)

    // ---------------------------------------------------------------- 性能

    @ModuleValue("性能模块", "帧率上限 / 世界刷新")
    val performance = BooleanValue(false)

    @ModuleValue("帧率上限", "0 = 不限，1 ~ 260")
    val fpsLimit = IntValue(0, 260, 0)

    @ModuleValue("渲染距离", "区块数，0 = 跟随原版")
    val perfRenderDistance = IntValue(0, 16, 0)

    @ModuleValue("实体渲染距离", "格，0 = 不限")
    val perfEntityDistance = IntValue(0, 128, 0)

    @ModuleValue("关闭实体阴影", "关闭后实体不再投阴影")
    val perfEntityShadows = BooleanValue(false)

    @ModuleValue("玻璃质量", "0 高 / 1 中 / 2 低")
    val perfGlassQuality = IntValue(0, 2, 1)

    override fun init() {
        instance = this
    }

    companion object {
        @Volatile
        private var instance: FpsToolsModule? = null

        fun of(): FpsToolsModule? = instance

        /** 雾颜色 0xRRGGBB：设置页色板与 FpsPortRuntime 共用这一份数据 */
        val fogPalette = intArrayOf(0x00C8FF, 0xFFFFFF, 0x000000, 0xB45CFF)

        fun fogSwatches(): List<Color> = fogPalette.map { rgb ->
            Color(
                ((rgb shr 16) and 0xFF) / 255f,
                ((rgb shr 8) and 0xFF) / 255f,
                (rgb and 0xFF) / 255f,
                1f,
            )
        }

        /** 方块高亮的填充 / 描边颜色（透明度由单独的滑条控制；最后两个是黑与浅灰） */
        val overlayPalette = intArrayOf(0xFFFFFF, 0xFF6E9E, 0x51A8FF, 0xFFBD4C, 0x55E08B, 0xFF5A5A, 0x000000, 0xC8C8C8)

        fun overlaySwatches(): List<Color> = overlayPalette.map { rgb ->
            Color(
                ((rgb shr 16) and 0xFF) / 255f,
                ((rgb shr 8) and 0xFF) / 255f,
                (rgb and 0xFF) / 255f,
                1f,
            )
        }
    }
}
