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

    @ModuleValue("聊天透明度", "0 ~ 100%，作用于聊天背景与文字")
    val chatOpacity = IntValue(0, 100, 100)

    // ---------------------------------------------------------------- 第四批：反作弊

    @ModuleValue("反作弊", "信号聚合 · 确认 · 标签")
    val acEnabled = BooleanValue(true)

    @ModuleValue("提示音", "确认作弊时播放提示音")
    val acSound = BooleanValue(true)

    @ModuleValue("自动格挡检查", "启用 Autoblock 检查项")
    val acAutoBlock = BooleanValue(true)

    @ModuleValue("合法搭桥检查", "启用 Legit Scaffold 检查项")
    val acLegitScaffold = BooleanValue(true)

    @ModuleValue("搭桥 A 检查", "启用 Scaffold A 检查项")
    val acScaffoldA = BooleanValue(true)

    @ModuleValue("搭桥 B 检查", "启用 Scaffold B 检查项")
    val acScaffoldB = BooleanValue(true)

    @ModuleValue("无减速检查", "启用 No Slow 检查项")
    val acNoSlow = BooleanValue(true)

    @ModuleValue("确认条数", "窗口内累计多少条信号算确认，1 ~ 10")
    val acConfirmCount = IntValue(1, 10, 3)

    @ModuleValue("统计窗口", "秒，1 ~ 60")
    val acWindowSeconds = IntValue(1, 60, 10)

    @ModuleValue("置信度门槛", "单条信号达到即确认，0 ~ 1")
    val acMinConfidence = FloatValue(0f, 1f, 0.6f, 2)

    @ModuleValue("合法搭桥倍率", "低置信度项门槛放大倍数，1 ~ 5")
    val acLegitMultiplier = IntValue(1, 5, 2)

    // ---------------------------------------------------------------- 第四批：聊天数据

    @ModuleValue("聊天数据", "解析 · 历史 · 队伍过滤")
    val adChatEnabled = BooleanValue(true)

    @ModuleValue("忽略队友", "队友发言不进聊天历史")
    val adChatIgnoreTeammates = BooleanValue(true)

    @ModuleValue("紧凑黑名单", "黑名单玩家只输出紧凑行")
    val adChatCompactBlacklist = BooleanValue(true)

    @ModuleValue("最低星级", "低于该星级不输出统计，0 = 不过滤")
    val adChatMinStars = IntValue(0, 500, 0)

    @ModuleValue("最低 FKDR", "低于该值不输出床战统计，0 = 不过滤")
    val adChatMinFkdr = FloatValue(0f, 10f, 0f, 2)

    @ModuleValue("最低 SW KDR", "低于该值不输出空战统计，0 = 不过滤")
    val adChatMinSwKdr = FloatValue(0f, 10f, 0f, 2)

    @ModuleValue("历史容量", "保留最近多少条，10 ~ 1000")
    val adChatHistory = IntValue(10, 1000, 200)

    @ModuleValue("单条长度上限", "超出截断，8 ~ 512")
    val adChatMaxLength = IntValue(8, 512, 256)

    // ---------------------------------------------------------------- 第四批：本次游戏

    @ModuleValue("会话统计", "局数 · 连胜 · 平均时长")
    val adSessionEnabled = BooleanValue(true)

    @ModuleValue("掉线保持", "秒，0 = 不自动清空，0 ~ 600")
    val adSessionIdleSeconds = IntValue(0, 600, 120)

    @ModuleValue("失败保留连胜", "失败时不重置连胜")
    val adSessionKeepWinstreak = BooleanValue(false)

    @ModuleValue("最长会话", "分钟，0 = 不限制，0 ~ 180")
    val adSessionMaxMinutes = IntValue(0, 180, 0)

    @ModuleValue("平均时长取整", "秒，1 ~ 60")
    val adSessionAvgRoundSeconds = IntValue(1, 60, 1)

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

    // ---------------------------------------------------------------- 第三批：性能优化 55 项

    // 渲染 / 实体
    @ModuleValue("名字标签剔除", "不画盔甲架的名字标签（架子照常画）")
    val perfIgnoreStands = BooleanValue(false)

    @ModuleValue("资源加载快速路径", "资源包加载时跳过冗余校验")
    val perfFastLoad = BooleanValue(true)

    @ModuleValue("方块模型批渲染", "同材质模型合并提交")
    val perfBatchModel = BooleanValue(true)

    @ModuleValue("降低环境粒子取样", "环境粒子取样数 1000 → 100")
    val perfLowAnimTick = BooleanValue(false)

    @ModuleValue("粒子固定最高亮度", "粒子按最高亮度绘制")
    val perfStaticParticle = BooleanValue(false)

    @ModuleValue("限制区块重建频率", "配合下面的重建预算使用")
    val perfLimitChunks = BooleanValue(true)

    @ModuleValue("资源包图标降采样", "资源包列表的图标降采样显示")
    val perfDownscaleIcons = BooleanValue(true)

    @ModuleValue("粒子视锥剔除", "视野外的粒子不再提交")
    val perfParticleCulling = BooleanValue(true)

    @ModuleValue("缓存天空颜色", "天空颜色每帧只算一次")
    val perfCacheSky = BooleanValue(true)

    @ModuleValue("实体遮挡剔除", "被方块遮挡的实体不再渲染")
    val perfEntityCulling = BooleanValue(true)

    @ModuleValue("剔除包含玩家", "遮挡剔除时把玩家也纳入")
    val perfCullPlayers = BooleanValue(false)

    @ModuleValue("缓存护甲纹理", "护甲渲染复用纹理路径")
    val perfCacheArmorTex = BooleanValue(true)

    @ModuleValue("远处告示牌文字剔除", "远处的告示牌不画文字")
    val perfSignCulling = BooleanValue(true)

    @ModuleValue("方块实体距离剔除", "超出距离的方块实体不渲染")
    val perfBlockEntityCulling = BooleanValue(false)

    @ModuleValue("方块实体剔除距离", "8 ~ 64 格")
    val perfBlockEntityDistance = IntValue(8, 64, 32)

    @ModuleValue("HUD 用自定义字体", "HUD 文字改用本项目字体")
    val perfCustomHudFont = BooleanValue(false)

    @ModuleValue("HUD 字号", "12 ~ 28")
    val perfCustomHudFontSize = IntValue(12, 28, 16)

    @ModuleValue("直写后备缓冲", "跳过 framebuffer（可能与光影冲突）")
    val perfFastRender = BooleanValue(false)

    // 内容隐藏（改动后自动重建世界）
    @ModuleValue("隐藏草", "不渲染矮草")
    val perfHideGrass = BooleanValue(false)

    @ModuleValue("隐藏双高草", "不渲染双格高草")
    val perfHideDoubleGrass = BooleanValue(false)

    @ModuleValue("隐藏花", "不渲染单格花")
    val perfHideFlowers = BooleanValue(false)

    @ModuleValue("隐藏双格花", "不渲染双格高花")
    val perfHideDoubleFlowers = BooleanValue(false)

    @ModuleValue("隐藏栅栏", "不渲染栅栏方块")
    val perfHideFences = BooleanValue(false)

    @ModuleValue("隐藏栅栏门", "不渲染栅栏门")
    val perfHideFenceGates = BooleanValue(false)

    @ModuleValue("隐藏头颅", "不渲染头颅方块实体")
    val perfHideSkulls = BooleanValue(false)

    @ModuleValue("隐藏盔甲架", "不渲染盔甲架模型")
    val perfHideArmorStands = BooleanValue(false)

    @ModuleValue("隐藏物品展示框", "不渲染展示框")
    val perfHideItemFrames = BooleanValue(false)

    @ModuleValue("隐藏框内地图", "展示框里的地图不画")
    val perfHideMapFrames = BooleanValue(false)

    @ModuleValue("隐藏身上插的箭", "不渲染插在自己身上的箭")
    val perfHideStuckArrows = BooleanValue(false)

    @ModuleValue("隐藏落地的箭", "不渲染地上的箭")
    val perfHideGroundArrows = BooleanValue(false)

    @ModuleValue("隐藏岩浆粒子", "不生成岩浆粒子")
    val perfHideLavaParticles = BooleanValue(false)

    @ModuleValue("隐藏刷怪笼粒子", "不生成刷怪笼粒子")
    val perfHideSpawnerParticles = BooleanValue(false)

    @ModuleValue("隐藏刷怪笼里的生物", "刷怪笼里的生物不渲染")
    val perfHideMobInSpawner = BooleanValue(false)

    @ModuleValue("隐藏传送门粒子", "不生成传送门粒子")
    val perfHidePortalParticles = BooleanValue(false)

    // 数值 / 模式
    @ModuleValue("粒子数量上限", "0 ~ 2000")
    val perfParticlesLimit = IntValue(0, 2000, 400)

    @ModuleValue("区块重建预算", "每帧最多重建的区块数，1 ~ 250")
    val perfChunkUpdateLimit = IntValue(1, 250, 50)

    @ModuleValue("遮挡剔除间隔", "毫秒，10 ~ 500（按 5 吸附）")
    val perfEntityCullingInterval = IntValue(10, 500, 50)

    @ModuleValue("遮挡剔除实体下限", "低于该数量的实体不剔除，0 ~ 256")
    val perfEntityCullingMin = IntValue(0, 256, 0)

    @ModuleValue("玩家渲染距离", "0.10 ~ 1.00 倍")
    val perfPlayerDist = FloatValue(0.1f, 1f, 1f, 2)

    @ModuleValue("友好生物渲染距离", "0.10 ~ 1.00 倍")
    val perfPassiveDist = FloatValue(0.1f, 1f, 1f, 2)

    @ModuleValue("敌对生物渲染距离", "0.10 ~ 1.00 倍")
    val perfHostileDist = FloatValue(0.1f, 1f, 1f, 2)

    @ModuleValue("其它实体渲染距离", "0.10 ~ 1.00 倍")
    val perfMiscDist = FloatValue(0.1f, 1f, 1f, 2)

    @ModuleValue("贴图分辨率下限", "Default / Half / Quarter / Eighth / Sixteenth")
    val perfTextureResolution = IntValue(0, 4, 0)

    @ModuleValue("复用可见区块", "重复使用上一帧的可见区块列表")
    val perfReuseChunks = BooleanValue(true)

    @ModuleValue("复用级别", "Conservative / Balanced / Aggressive")
    val perfReuseLevel = IntValue(0, 2, 0)

    @ModuleValue("自适应区块预算", "相机静止时提高区块预算")
    val perfAdaptiveChunk = BooleanValue(false)

    @ModuleValue("合成模型变换", "一个矩阵替代 6 次定长调用")
    val perfComposedModel = BooleanValue(false)

    @ModuleValue("原版字体整串提交", "原版字体按整串提交")
    val perfBatchVanillaFont = BooleanValue(false)

    @ModuleValue("字形快速查表", "查表替代线性扫描")
    val perfFastGlyph = BooleanValue(true)

    @ModuleValue("乱码慢速重排", "乱码文本每 tick 重排一次")
    val perfSlowObfuscation = BooleanValue(false)

    @ModuleValue("阴影文本单次录制", "阴影与正文一次录制")
    val perfMergeShadow = BooleanValue(true)

    @ModuleValue("物品模型缓存", "物品 display list 缓存")
    val perfCacheItemModels = BooleanValue(false)

    @ModuleValue("碰撞快速路径", "碰撞查询走快速路径")
    val perfFastCollision = BooleanValue(false)

    @ModuleValue("纹理上传快速路径", "纹理上传跳过冗余检查")
    val perfFastTextureUpload = BooleanValue(true)

    // ---------------------------------------------------------------- 第三批：鱼竿 · 亮度 · 模糊

    @ModuleValue("增强鱼竿", "加粗钓线，看得更清楚")
    val betterFishingRod = BooleanValue(false)

    @ModuleValue("钓线宽度", "1.0 ~ 10.0，按 0.5 吸附")
    val rodStringWidth = FloatValue(1f, 10f, 1f, 1)

    @ModuleValue("永久亮度", "关闭后还原开启前的亮度")
    val fullBright = BooleanValue(false)

    @ModuleValue("动态模糊", "Old 帧混合 / New 后处理")
    val motionBlur = BooleanValue(false)

    @ModuleValue("模糊模式", "Old / New")
    val mbMode = IntValue(0, 1, 1)

    @ModuleValue("模糊强度", "0 ~ 10，按 0.5 吸附")
    val mbStrength = FloatValue(0f, 10f, 2f, 1)

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
