package me.sorihiko.dango.legacy

import fpsport.optimizes.BetterFishingRod
import fpsport.optimizes.NoHitDelay
import fpsport.optimizes.NoHurtCam
import fpsport.optimizes.Performance
import fpsport.optimizes.SmoothZoom
import fpsport.render.BlockAnimation
import fpsport.render.BlockOverlay
import fpsport.render.CleanView
import fpsport.render.CustomFog
import fpsport.render.DamageIndicator
import fpsport.render.FireModifier
import fpsport.render.FreeLook
import fpsport.render.FullBright
import fpsport.render.ItemPhysics
import fpsport.render.MoreParticles
import fpsport.render.MotionBlur
import fpsport.pvp.BlindGuard
import fpsport.pvp.ComboCounter
import fpsport.pvp.EntityMarkers
import fpsport.pvp.HealthAlert
import fpsport.pvp.KnockbackIndicator
import fpsport.pvp.PlacementPreview
import fpsport.pvp.PvpHud
import fpsport.utility.AutoGG
import fpsport.utility.AutoText
import fpsport.utility.BetterChat
import fpsport.utility.CPSDisplay
import fpsport.utility.LevelTag
import fpsport.utility.ParticlesModifier
import fpsport.utility.Sprint
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.core.FpsFlags
import me.sorihiko.dango.module.impls.music.FpsToolsModule
import me.sorihiko.dango.module.impls.music.HudInfoModule

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.settings.KeyBinding
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.item.EntityArmorStand
import net.minecraft.entity.monster.EntityMob
import net.minecraft.entity.passive.EntityAnimal
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.network.play.server.S02PacketChat
import net.minecraft.util.Vec3
import net.minecraft.world.World
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.client.event.EntityViewRenderEvent
import net.minecraftforge.client.event.MouseEvent
import net.minecraftforge.client.event.RenderLivingEvent
import net.minecraftforge.event.entity.living.LivingHurtEvent
import net.minecraftforge.event.entity.player.AttackEntityEvent

import org.lwjgl.input.Keyboard

/**
 * fpsport 的运行层：把设置值同步进 fpsport 的静态字段，并把 Forge 事件转发给对应功能。
 *
 * 不画任何东西、不新建 UI；所有回调都吞异常打日志，保证不会把异常抛进游戏循环。
 * 不可用的三项（OldAnimations / NameProtect / RawInput）不实例化；
 * 点击速度只产出数据，由信息胶囊（HudInfoModule）负责绘制。
 */
object FpsPortRuntime {

    private val noHitDelay = NoHitDelay()
    private val noHurtCam = NoHurtCam()
    private val blockAnimation = BlockAnimation()
    private val smoothZoom = SmoothZoom()
    private val customFog = CustomFog()
    private val damageIndicator = DamageIndicator()
    private val fireModifier = FireModifier()
    private val freeLook = FreeLook()
    private val moreParticles = MoreParticles()
    private val autoGG = AutoGG()
    private val autoText = AutoText()
    private val levelTag = LevelTag()
    private val particlesModifier = ParticlesModifier()
    private val sprint = Sprint()
    private val performance = Performance()

    /** 第二批：清洁视角 / 方块高亮 / 物品物理 / 聊天增强 / 点击速度（数据） */
    private val cleanView = CleanView()
    private val blockOverlay = BlockOverlay()
    private val itemPhysics = ItemPhysics()
    private val betterChat = BetterChat()
    private val cpsDisplay = CPSDisplay()

    /** 第三批：增强鱼竿 / 永久亮度 / 动态模糊 */
    private val betterFishingRod = BetterFishingRod()
    private val fullBright = FullBright()
    private val motionBlur = MotionBlur()

    /** 第五批：PVP 增强（连击 / 击退 / 防盲 / 标记 / 放置 / 低血量） */
    private val comboCounter = ComboCounter()
    private val knockbackIndicator = KnockbackIndicator()
    private val blindGuard = BlindGuard()
    private val entityMarkers = EntityMarkers()
    private val placementPreview = PlacementPreview()
    private val healthAlert = HealthAlert()

    /** 六个隐藏方块开关的位掩码；初值 -1 保证第一 tick 一定会应用一次 */
    private var hidingMask = -1

    private val specials = arrayOf("None", "Heart", "Flame", "Blood")
    private val killEffects = arrayOf("None", "Lightning", "Explosion")

    private var lastWorld: World? = null
    private var fogColorIndex = -1
    // 4 组快捷发言的指纹：键位 storage + 内容，只有真的变化时才重建 AutoText.entries。
    // 用定长数组逐项比较，避免每 tick 新建 Pair / List（20 tick/s 的无谓分配）。
    private val quickFingerprint = arrayOfNulls<String>(8)
    private var forcedSprint = false

    // ---------------------------------------------------------------- 每 tick

    fun tick() {
        try {
            val module = FpsToolsModule.of()
            if (module == null) {
                // 模块没初始化：清空跨模块的 CPS 桥，避免信息胶囊一直显示上一次的旧值
                HudInfoModule.cpsLeft = -1
                HudInfoModule.cpsRight = -1
                return
            }
            val world = mc.theWorld
            if (world !== lastWorld) {
                // 换世界：清掉旧实体引用，避免在新世界按旧坐标放特效
                lastWorld = world
                DamageIndicator.clearState()
                moreParticles.setEnabled(false)
                // 折叠缓存放的是上一局的聊天内容，换世界必须清掉
                BetterChat.reset()
                // PVP 增强的瞬时状态（连击 / 击退 / 防盲亮度）不能跨局保留
                ComboCounter.reset()
                KnockbackIndicator.reset()
                BlindGuard.reset()
                // 离开世界：Old 模式的两个乒乓 FBO 不再有用，释放掉
                if (world == null) {
                    MotionBlur.deleteBlurBuffers()
                    // 共享的整屏背景抓取纹理也一起释放：主菜单不需要一直占着这几 MB 显存
                    me.sorihiko.dango.ui.glass.GlassBackdrop.releaseShared()
                    // 离开世界：把永久亮度写进去的 gamma 还原，别留给后续的 options.txt 存盘
                    FullBright.restoreNow()
                }
            }
            sync(module)
            // 第四批：聊天数据 / 会话统计的参数热更新与会话心跳
            AdninRuntime.sync(module)
            AdninRuntime.tick(System.currentTimeMillis())
            // 点击速度：按配置窗口重算（宿主 HUD 直接读 CPSDisplay.leftCps/rightCps）
            CPSDisplay.onTick()
            // 防盲：低频扫描附近闪电，作为「强光」触发源
            blindGuard.onTick()
            publishHud(module)
            // 自由视角必须每 tick 都跑（打开界面时要能立刻退出），不能只在世界内跑
            freeLook.onUpdate()
            autoGG.onTick()
            Performance.onClientTick()
            // 只有进入世界后才跑游戏内功能，避免在主菜单反复触发空实体/空玩家
            if (mc.thePlayer != null && world != null) {
                noHitDelay.onTick()
                if (damageIndicator.isEnabled()) damageIndicator.onUpdate()
                if (moreParticles.isEnabled()) moreParticles.onUpdate()
                if (smoothZoom.isEnabled()) smoothZoom.onUpdate()
                sprint.onUpdate()
                forceSprint()
            } else {
                // 离开世界 / 玩家为空时必须还原按键，否则强制疾跑会留下“按住疾跑”状态
                releaseSprintKey()
            }
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music fps tools tick failed: {}", t)
        }
    }

    private fun sync(m: FpsToolsModule) {
        val on = m.enabled

        noHitDelay.setEnabled(on && m.noHitDelay.get())
        noHurtCam.setEnabled(on && m.noHurtCam.get())
        FpsFlags.noHurtCam = NoHurtCam.using

        blockAnimation.setEnabled(on && m.blockAnim.get())
        BlockAnimation.style = m.blockStyle.value.coerceIn(0, 10)
        BlockAnimation.offX = m.blockOffX.value
        BlockAnimation.offY = m.blockOffY.value
        BlockAnimation.offZ = m.blockOffZ.value
        smoothZoom.setEnabled(on && m.smoothZoom.get())
        SmoothZoom.zoomBind = m.keyZoom.primary
        SmoothZoom.zoomMods = m.keyZoom.modifiers
        SmoothZoom.smoothCamera = m.zoomSmoothCamera.get()
        SmoothZoom.wheelZoom = m.zoomWheel.get()
        SmoothZoom.smoothMouse = m.zoomSmoothMouse.get()
        SmoothZoom.speed = m.zoomSpeed.value.toDouble()
        SmoothZoom.baseScale = m.zoomScale.value.coerceIn(1f, 20f)
        SmoothZoom.zoomScale = SmoothZoom.zoomScale.coerceIn(1f, 20f)

        customFog.setEnabled(on && m.customFog.get())
        CustomFog.affectWater = m.fogLiquids.get()
        CustomFog.affectLava = m.fogLiquids.get()
        val colorIndex = m.fogColor.value.coerceIn(0, FpsToolsModule.fogPalette.size - 1)
        if (colorIndex != fogColorIndex) {
            fogColorIndex = colorIndex
            val rgb = FpsToolsModule.fogPalette[colorIndex]
            CustomFog.color = java.awt.Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
        }

        damageIndicator.setEnabled(on && m.damageIndicator.get())
        DamageIndicator.scale = m.diScale.value.coerceIn(0.03f, 0.15f)

        fireModifier.setEnabled(on && m.fireModifier.get())
        FpsFlags.hideFirstPersonFire = FireModifier.using

        freeLook.setEnabled(on && m.freeLook.get())
        FreeLook.bind = m.keyFreeLook.primary
        FreeLook.bindMods = m.keyFreeLook.modifiers
        FreeLook.sensitivity = m.freeLookSens.value.coerceIn(0.2f, 3f)

        moreParticles.setEnabled(on && m.moreParticles.get())
        MoreParticles.sharpness = m.mpSharpness.value
        MoreParticles.crit = m.mpCrit.value
        MoreParticles.alwaysCrit = m.mpAlwaysCrit.get()
        MoreParticles.alwaysSharpness = m.mpAlwaysSharpness.get()
        MoreParticles.special = specials[m.mpSpecial.value.coerceIn(0, specials.size - 1)]
        MoreParticles.killEffect = killEffects[m.mpKillEffect.value.coerceIn(0, killEffects.size - 1)]

        autoGG.setEnabled(on && m.autoGG.get())
        AutoGG.message = m.ggMessage.get()
        AutoGG.server = if (m.ggServer.value == 0) "hypixel" else "normal"
        AutoGG.delay = m.ggDelay.value
        AutoGG.autoPlay = m.ggAutoPlay.get()
        AutoGG.detection = "chat"

        autoText.setEnabled(on && m.autoText.get())
        // 4 组快捷发言：逐项和上一 tick 的指纹比较，只有键位或内容变化时才重建
        val k0 = m.keyAutoText.storage()
        val k1 = m.keyQuick2.storage()
        val k2 = m.keyQuick3.storage()
        val k3 = m.keyQuick4.storage()
        val t0 = m.msgAutoText.get()
        val t1 = m.msgQuick2.get()
        val t2 = m.msgQuick3.get()
        val t3 = m.msgQuick4.get()
        val f = quickFingerprint
        if (k0 != f[0] || k1 != f[1] || k2 != f[2] || k3 != f[3] ||
            t0 != f[4] || t1 != f[5] || t2 != f[6] || t3 != f[7]
        ) {
            f[0] = k0; f[1] = k1; f[2] = k2; f[3] = k3
            f[4] = t0; f[5] = t1; f[6] = t2; f[7] = t3
            autoText.entries.clear()
            registerQuick(m.keyAutoText, t0)
            registerQuick(m.keyQuick2, t1)
            registerQuick(m.keyQuick3, t2)
            registerQuick(m.keyQuick4, t3)
        }

        levelTag.setEnabled(on && m.levelTag.get())
        LevelTag.showSelf = m.levelShowSelf.get()
        LevelTag.hideNPC = m.levelHideNPC.get()

        particlesModifier.setEnabled(on && m.particlesModifier.get())
        ParticlesModifier.block = m.pmKeepBlock.get()
        // 只丢环境粒子；crit / flame / heart / 方块破坏粒子由 FpsFlags.shouldDropParticle 无条件保护
        FpsFlags.dropParticles = ParticlesModifier.using

        // 「性能」是独立功能开关：只跟随自身开关，不再被容器模块 enabled 二次门控，
        // 否则设置在 UI 上打开了、FpsToolsModule 却是关闭状态时优化逻辑永远不会执行。
        performance.setEnabled(m.performance.get())
        Performance.fpsLimit = m.fpsLimit.value
        Performance.renderDistance = m.perfRenderDistance.value
        Performance.entityShadows = m.perfEntityShadows.get()
        Performance.glassQuality = m.perfGlassQuality.value.coerceIn(0, 2)
        // 实体距离也是「性能」的子项：总开关关闭时必须归零，否则关掉模块后实体仍会被剔除
        Performance.entityDistance = if (m.performance.get()) m.perfEntityDistance.value else 0

        // ---------------------------------------------------------- 性能：渲染 / 实体
        // 与原工程一致：所有子优化都必须同时满足总开关和自身开关
        val perfOn = m.performance.get()
        Performance.ignoreStands = perfOn && m.perfIgnoreStands.get()
        Performance.fastLoad = perfOn && m.perfFastLoad.get()
        Performance.batchModelRendering = perfOn && m.perfBatchModel.get()
        Performance.lowAnimationTick = perfOn && m.perfLowAnimTick.get()
        Performance.staticParticleColor = perfOn && m.perfStaticParticle.get()
        Performance.limitChunks = perfOn && m.perfLimitChunks.get()
        Performance.downscalePackIcons = perfOn && m.perfDownscaleIcons.get()
        Performance.particleCulling = perfOn && m.perfParticleCulling.get()
        Performance.cacheSkyColor = perfOn && m.perfCacheSky.get()
        Performance.entityCulling = perfOn && m.perfEntityCulling.get()
        Performance.cullPlayers = perfOn && m.perfCullPlayers.get()
        Performance.cacheArmorTextures = perfOn && m.perfCacheArmorTex.get()
        Performance.signTextCulling = perfOn && m.perfSignCulling.get()
        Performance.blockEntityCulling = perfOn && m.perfBlockEntityCulling.get()
        Performance.blockEntityDistance = m.perfBlockEntityDistance.value.coerceIn(8, 64)
        Performance.customHudFont = perfOn && m.perfCustomHudFont.get()
        Performance.customHudFontSize = m.perfCustomHudFontSize.value.coerceIn(12, 28)
        Performance.fastRender = perfOn && m.perfFastRender.get()

        // 隐藏内容：隐藏开关是在区块网格里烘焙的，只有变化时才重算并重建一次世界
        val hideGrass = perfOn && m.perfHideGrass.get()
        val hideDoubleGrass = perfOn && m.perfHideDoubleGrass.get()
        val hideFlowers = perfOn && m.perfHideFlowers.get()
        val hideDoubleFlowers = perfOn && m.perfHideDoubleFlowers.get()
        val hideFences = perfOn && m.perfHideFences.get()
        val hideFenceGates = perfOn && m.perfHideFenceGates.get()
        // 用位掩码代替拼接字符串指纹：每 tick 少一次 String 拼接与分配
        var hideMask = 0
        if (hideGrass) hideMask = hideMask or 1
        if (hideDoubleGrass) hideMask = hideMask or 2
        if (hideFlowers) hideMask = hideMask or 4
        if (hideDoubleFlowers) hideMask = hideMask or 8
        if (hideFences) hideMask = hideMask or 16
        if (hideFenceGates) hideMask = hideMask or 32
        if (hideMask != hidingMask) {
            hidingMask = hideMask
            Performance.hideTallGrass = hideGrass
            Performance.hideDoubleTallGrass = hideDoubleGrass
            Performance.hideFlowers = hideFlowers
            Performance.hideDoubleTallFlowers = hideDoubleFlowers
            Performance.hideFences = hideFences
            Performance.hideFenceGates = hideFenceGates
            Performance.refreshHidingBlocks()
        }
        // 其余隐藏项只在渲染时读取，直接写即可
        Performance.hideSkulls = perfOn && m.perfHideSkulls.get()
        Performance.hideArmorStands = perfOn && m.perfHideArmorStands.get()
        Performance.hideItemFrames = perfOn && m.perfHideItemFrames.get()
        Performance.hideMapsInItemFrames = perfOn && m.perfHideMapFrames.get()
        Performance.hideStuckArrows = perfOn && m.perfHideStuckArrows.get()
        Performance.hideGroundArrows = perfOn && m.perfHideGroundArrows.get()
        Performance.hideLavaParticles = perfOn && m.perfHideLavaParticles.get()
        Performance.hideSpawnerParticles = perfOn && m.perfHideSpawnerParticles.get()
        Performance.hideMobInSpawner = perfOn && m.perfHideMobInSpawner.get()
        Performance.hidePortalParticles = perfOn && m.perfHidePortalParticles.get()

        // 数值 / 模式
        Performance.particlesLimit = m.perfParticlesLimit.value.coerceIn(0, 2000)
        Performance.chunkUpdateLimit = m.perfChunkUpdateLimit.value.coerceIn(1, 250)
        Performance.entityCullingInterval = m.perfEntityCullingInterval.value.coerceIn(10, 500)
        Performance.entityCullingMinEntities = m.perfEntityCullingMin.value.coerceIn(0, 256)
        Performance.entityFovCulling = perfOn && m.perfEntityFov.get()
        Performance.entityFovAngle = m.perfEntityFovAngle.value.coerceIn(60, 170)
        Performance.nametagCulling = perfOn && m.perfNametagCulling.get()
        Performance.nametagDistance = m.perfNametagDistance.value.coerceIn(16, 128)
        Performance.hideClouds = perfOn && m.perfHideClouds.get()
        // 分类系数同样只在「性能」开启时生效，关闭时统一回到 1.0（不剔除）
        val playerDist = if (perfOn) m.perfPlayerDist.value.coerceIn(0.1f, 1f).toDouble() else 1.0
        val passiveDist = if (perfOn) m.perfPassiveDist.value.coerceIn(0.1f, 1f).toDouble() else 1.0
        val hostileDist = if (perfOn) m.perfHostileDist.value.coerceIn(0.1f, 1f).toDouble() else 1.0
        val miscDist = if (perfOn) m.perfMiscDist.value.coerceIn(0.1f, 1f).toDouble() else 1.0
        Performance.playerRenderDistance = playerDist
        Performance.passiveRenderDistance = passiveDist
        Performance.hostileRenderDistance = hostileDist
        Performance.miscRenderDistance = miscDist
        Performance.reuseVisibleChunks = perfOn && m.perfReuseChunks.get()
        Performance.reuseVisibleChunksLevel = m.perfReuseLevel.value.coerceIn(0, 2)
        Performance.adaptiveChunkBudget = perfOn && m.perfAdaptiveChunk.get()
        Performance.composedModelTransform = perfOn && m.perfComposedModel.get()
        Performance.batchVanillaFont = perfOn && m.perfBatchVanillaFont.get()
        Performance.fastGlyphLookup = perfOn && m.perfFastGlyph.get()
        Performance.slowObfuscation = perfOn && m.perfSlowObfuscation.get()
        Performance.mergeTextShadow = perfOn && m.perfMergeShadow.get()
        Performance.cacheItemModels = perfOn && m.perfCacheItemModels.get()
        Performance.fastCollision = perfOn && m.perfFastCollision.get()
        Performance.fastTextureUpload = perfOn && m.perfFastTextureUpload.get()
        // 贴图分辨率：改动后延迟到下一 tick 由 textureResolutionApplier 应用
        val textureResolution = m.perfTextureResolution.value.coerceIn(0, 4)
        if (textureResolution != Performance.textureResolution) {
            Performance.textureResolution = textureResolution
            Performance.markWorldRefreshPending()
        }

        // ---------------------------------------------------------- 第三批
        betterFishingRod.setEnabled(on && m.betterFishingRod.get())
        BetterFishingRod.stringWidth = BetterFishingRod.clampStringWidth(m.rodStringWidth.value)

        // 「永久亮度」和「性能」一样是独立功能开关：设置页里「画质增强」分组本身就不跟随
        // 容器模块，再被 on 二次门控就会出现"UI 上明明开着、功能完全不生效"。
        fullBright.setEnabled(m.fullBright.get())
        // 功能关闭时清理历史残留：上一局写进 options.txt 的 gamma=100 会让亮度永久全亮
        if (!m.fullBright.get()) FullBright.sanitizeSavedGamma()

        motionBlur.setEnabled(on && m.motionBlur.get())
        MotionBlur.mode = m.mbMode.value.coerceIn(MotionBlur.MODE_OLD, MotionBlur.MODE_NEW)
        MotionBlur.clampStrength(m.mbStrength.value)

        // ---------------------------------------------------------- 第二批
        cleanView.setEnabled(on && m.cleanView.get())

        blockOverlay.setEnabled(on && m.blockOverlay.get())
        BlockOverlay.fill = m.boFill.get()
        BlockOverlay.outline = m.boOutline.get()
        BlockOverlay.throughBlock = m.boThrough.get()
        BlockOverlay.width = m.boWidth.value.coerceIn(0.5f, 5f)
        val overlay = FpsToolsModule.overlayPalette
        BlockOverlay.fillColor = argb(overlay[m.boFillColor.value.coerceIn(0, overlay.size - 1)], m.boFillAlpha.value)
        BlockOverlay.outlineColor = argb(overlay[m.boOutlineColor.value.coerceIn(0, overlay.size - 1)], m.boOutlineAlpha.value)

        itemPhysics.setEnabled(on && m.itemPhysics.get())
        ItemPhysics.rotateAngle = m.ipRotate.value.coerceIn(0f, 360f)
        ItemPhysics.scale = m.ipScale.value.coerceIn(0.2f, 1f)
        ItemPhysics.rollSpeed = m.ipRollSpeed.value.coerceIn(0f, 10f)
        ItemPhysics.spread = m.ipSpread.value.coerceIn(0f, 0.5f)
        ItemPhysics.stackGap = m.ipStackGap.value.coerceIn(0.01f, 0.2f)
        ItemPhysics.respectCount = m.ipRespectCount.get()

        // 同理：聊天增强只跟随自身开关。折叠 / 复制 / 透明度由下面的子项直接写入，
        // 与 using 无关，因此 using 必须准确反映 betterChat 的开关状态。
        betterChat.setEnabled(m.betterChat.get())
        BetterChat.foldMessage = m.chatFold.get()
        BetterChat.copyMessage = m.chatCopy.get()
        BetterChat.minCopyLength = m.chatMinLength.value.coerceIn(0, 32)
        BetterChat.foldActionBar = m.chatFoldActionBar.get()
        BetterChat.opacity = m.chatOpacity.value.coerceIn(0, 100)
        // 新增的两个独立开关：同样不跟随 using，实时生效
        BetterChat.setSmoothChat(m.chatSmooth.get())
        if (BetterChat.setChatAvatars(m.chatAvatars.get())) {
            // 头像占位改变了折行宽度：立刻重建聊天行，历史消息也一起对齐
            try {
                Minecraft.getMinecraft().ingameGUI?.chatGUI?.refreshChat()
            } catch (t: Throwable) {
                // 刷新失败不影响本 tick 的其它同步
            }
        }

        // 只计数、不画任何东西；数据由 publishHud 送进信息胶囊
        CPSDisplay.windowMs = m.cpsWindow.value.coerceIn(200, 5000)
        cpsDisplay.setEnabled(on && m.cpsDisplay.get())

        // ---------------------------------------------------------- 第五批：PVP 增强
        val pvpPalette = FpsToolsModule.overlayPalette
        comboCounter.setEnabled(on && m.comboCounter.get())
        ComboCounter.windowMs = m.comboTimeout.value.coerceIn(500, 5000).toLong()
        ComboCounter.minHits = m.comboMinHits.value.coerceIn(1, 10)
        ComboCounter.position = m.comboPos.value.coerceIn(0, 2)
        ComboCounter.scale = m.comboScale.value.coerceIn(0.5f, 2f)
        ComboCounter.resetOnHurt = m.comboResetOnHurt.get()

        knockbackIndicator.setEnabled(on && m.knockbackArrow.get())
        KnockbackIndicator.durationMs = m.kbDuration.value.coerceIn(300, 2000)
        KnockbackIndicator.size = m.kbSize.value.coerceIn(12, 48)
        KnockbackIndicator.color = solid(pvpPalette[m.kbColor.value.coerceIn(0, pvpPalette.size - 1)])

        blindGuard.setEnabled(on && m.blindGuard.get())
        BlindGuard.gammaFloor = m.blindGamma.value.coerceIn(0f, 1f)
        BlindGuard.flashMs = m.blindFlashMs.value.coerceIn(200, 3000)

        entityMarkers.setEnabled(on && m.entityMarkers.get())
        EntityMarkers.teammateColor =
            solid(pvpPalette[m.markTeammateColor.value.coerceIn(0, pvpPalette.size - 1)])
        EntityMarkers.enemyColor =
            solid(pvpPalette[m.markEnemyColor.value.coerceIn(0, pvpPalette.size - 1)])
        EntityMarkers.maxDistance = m.markDistance.value.coerceIn(8, 256)
        EntityMarkers.showDistance = m.markShowDistance.get()
        EntityMarkers.size = m.markSize.value.coerceIn(6, 24)

        placementPreview.setEnabled(on && m.placementPreview.get())
        PlacementPreview.fillAlpha = m.placeAlpha.value.coerceIn(0, 100)

        healthAlert.setEnabled(on && m.healthAlert.get())
        HealthAlert.threshold = m.healthThreshold.value.coerceIn(1f, 20f)
        HealthAlert.border = m.healthBorder.value.coerceIn(4, 40)
        HealthAlert.pulseSpeed = m.healthPulse.value.coerceIn(0.5f, 4f)
        HealthAlert.color = solid(pvpPalette[m.healthColor.value.coerceIn(0, pvpPalette.size - 1)])

        // 关键：先把 Sprint 功能本身置为启用，否则 isEnabled() 永远是 false，
        // forceSprint()/onKey() 会被直接短路，强制疾跑完全不生效。
        sprint.setEnabled(on && m.sprint.get())
        Sprint.toggleSprint = m.sprintToggle.get()

        // 模块整体关闭或关掉「强制疾跑」时立刻还原疾跑按键，避免残留按键状态
        if (!on || !m.sprint.get()) releaseSprintKey()
    }

    private fun registerQuick(key: me.sorihiko.dango.module.values.KeyBindValue, message: String) {
        if (key.isSet && message.isNotEmpty()) {
            autoText.entries.add(AutoText.Entry(key.primary, key.modifiers, message))
        }
    }

    /**
     * 把点击速度的展示数据写进信息胶囊的静态桥（common 不能反向依赖 legacy）。
     *
     * <p>CPS 用 -1 表示「该侧不显示」。
     */
    private fun publishHud(m: FpsToolsModule) {
        val on = m.enabled
        if (on && m.cpsDisplay.get()) {
            HudInfoModule.cpsLeft = if (m.cpsShowLeft.get()) CPSDisplay.leftCps().toInt() else -1
            HudInfoModule.cpsRight = if (m.cpsShowRight.get()) CPSDisplay.rightCps().toInt() else -1
        } else {
            HudInfoModule.cpsLeft = -1
            HudInfoModule.cpsRight = -1
        }
    }

    /** 把调色板的 RGB 与 0~100 的透明度合成 ARGB。 */
    private fun argb(rgb: Int, alphaPercent: Int): Int {
        val a = (alphaPercent.coerceIn(0, 100) * 255 / 100) shl 24
        return a or (rgb and 0xFFFFFF)
    }

    /** 调色板 RGB → 不透明 ARGB。 */
    private fun solid(rgb: Int): Int = (0xFF shl 24) or (rgb and 0xFFFFFF)

    /** 强制疾跑：写按键状态 + 每 tick 直接维持疾跑，两个途径都覆盖，确保真的跑起来 */
    private fun forceSprint() {
        val player = mc.thePlayer
        // 功能关闭 / 切换疾跑处于关闭态 / 没有玩家 / 打开界面 / 正在使用物品时，
        // 不强按按键，并立即还原上一次按下的状态
        if (!sprint.isEnabled() || !Sprint.sprint || player == null
                || mc.currentScreen != null || player.isUsingItem) {
            releaseSprintKey()
            return
        }
        val key = mc.gameSettings.keyBindSprint.keyCode
        // 疾跑键被解绑（keyCode == 0）时 setKeyBindState 会作用到所有按键，必须跳过；
        // 这种情况只靠下面的 setSprinting 维持。
        if (key != 0) {
            KeyBinding.setKeyBindState(key, true)
            forcedSprint = true
        }
        // 原版只在「向前移动」时允许起跑；这里直接置位，避免按键状态被原版输入覆盖后起不来
        // 潜行时不要强制起跑，否则会被原版立刻判定为下蹲移动
        if (player.movementInput.moveForward >= 0.8f && !player.isSprinting && !player.isSneaking) {
            player.setSprinting(true)
        }
    }

    private fun releaseSprintKey() {
        if (!forcedSprint) return
        forcedSprint = false
        val key = mc.gameSettings.keyBindSprint.keyCode
        val player = mc.thePlayer
        // 只有在我们把键按住、而玩家自己没按的时候才还原，避免抢走真实疾跑键
        if (key != 0 && !Keyboard.isKeyDown(key)) {
            KeyBinding.setKeyBindState(key, false)
            if (player != null && player.isSprinting) player.setSprinting(false)
        }
    }

    // ---------------------------------------------------------------- 每帧

    fun onRender3D(partialTicks: Float) {
        try {
            // 防盲必须在永久亮度之前：先抬到保护下限，全亮再覆盖成 100，两者不会互相回滚
            BlindGuard.onUpdate()
            // 永久亮度：每帧重写 gamma，防光影 / 资源包自己改回去
            FullBright.onUpdate()
            // 帧率上限不依赖世界内容，放在世界判空之前，避免空世界帧漏掉限制
            Performance.applyFpsLimit()
            if (mc.theWorld == null) return
            damageIndicator.onRender3D()
            BlockOverlay.onRender3D()
            PlacementPreview.onRender3D()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music fps render failed: {}", t)
        }
    }

    /** HUD 全部画完之后：PVP 增强 HUD + 动态模糊（Old 帧混合 / New 后处理）都在这里叠加 */
    fun onRenderOverlay(partialTicks: Float) {
        try {
            renderPvpHud(partialTicks)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music pvp hud failed: {}", t)
        }
        try {
            // onRenderOverlay 本身不判开关，关闭时绝不能画
            if (!motionBlur.isEnabled()) return
            motionBlur.onRenderOverlay()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music motion blur failed: {}", t)
        }
    }

    /** PVP 增强的统一 HUD 入口：四个功能共用一次正交投影，避免反复保存 / 恢复 GL 状态。 */
    private fun renderPvpHud(partialTicks: Float) {
        if (mc.thePlayer == null || mc.theWorld == null) return
        if (!ComboCounter.using && !KnockbackIndicator.using
            && !EntityMarkers.using && !HealthAlert.using
        ) return
        val scaled = ScaledResolution(mc)
        val w = scaled.scaledWidth
        val h = scaled.scaledHeight
        PvpHud.begin()
        try {
            // 低血量边框压在最底层，标记 / 连击 / 箭头叠在上面
            HealthAlert.render(w, h)
            EntityMarkers.render(w, h, partialTicks)
            ComboCounter.render(w, h)
            KnockbackIndicator.render(w, h)
        } finally {
            PvpHud.end()
        }
    }

    // ---------------------------------------------------------------- 事件

    fun onFov(event: EntityViewRenderEvent.FOVModifier) {
        try {
            event.fov = SmoothZoom.adjustFov(0f, true, event.fov)
            // 标记投影必须用这一帧真正生效的 FOV，缩放时才不会整体偏移
            EntityMarkers.viewFov = event.fov
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music zoom fov failed: {}", t)
        }
    }

    /** 致盲雾：降低原版失明时的雾密度，避免视野完全变黑 */
    fun onFogDensity(event: EntityViewRenderEvent.FogDensity) {
        try {
            BlindGuard.applyFogDensity(event)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music fog density failed: {}", t)
        }
    }

    fun onFogColors(event: EntityViewRenderEvent.FogColors) {
        try {
            if (!CustomFog.using || !CustomFog.shouldOverrideFog()) return
            val color = CustomFog.color
            event.red = color.red / 255f
            event.green = color.green / 255f
            event.blue = color.blue / 255f
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music fog color failed: {}", t)
        }
    }

    /** 自由视角：在相机事件里接管朝向（1.8.9 的 CameraSetup 字段可写） */
    fun onCameraSetup(event: EntityViewRenderEvent.CameraSetup) {
        try {
            FreeLook.applyCamera(event)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music free look failed: {}", t)
        }
    }

    fun onMouse(event: MouseEvent) {
        try {
            // 点击速度只统计「按下」；开启「只在游戏内」时不把菜单点击算进去
            if (event.buttonstate && event.button in 0..1) {
                val module = FpsToolsModule.of()
                if (module == null || !module.cpsInGameOnly.get() || mc.currentScreen == null) {
                    CPSDisplay.onMouseClick(event.button)
                }
            }
            if (!event.isCancelable) return
            // 按住缩放键时滚轮改的是缩放倍率：把事件喂给 SmoothZoom 并拦下，
            // 避免同时切换快捷栏。用 isZoomKeyActive 而不是 zoom，倍率为 1 时也能正常调
            if (event.dwheel != 0 && SmoothZoom.wheelZoom && SmoothZoom.isZoomKeyActive()) {
                SmoothZoom.addWheel(event.dwheel)
                event.isCanceled = true
            }
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music zoom wheel failed: {}", t)
        }
    }

    fun onAttack(event: AttackEntityEvent) {
        try {
            val target = event.target
            // 开关关闭时不再记录目标 / 生成粒子（否则关掉开关也照样出特效）
            if (damageIndicator.isEnabled()) damageIndicator.onAttack(target)
            if (moreParticles.isEnabled()) moreParticles.onAttack(target)
            // 连击不在这里计数：空挥也会触发 AttackEntityEvent，改为在 onLivingHurt 里按实际伤害计数
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music attack effects failed: {}", t)
        }
    }

    /** 伤害事件：自己打出伤害时计连击；自己受伤时记录击退方向与受击方位 */
    fun onLivingHurt(event: LivingHurtEvent) {
        try {
            val player = mc.thePlayer ?: return
            val source = event.source
            // 连击只在真正造成伤害时 +1（空挥 / 被格挡 / 无敌帧都不计）
            if (source.entity === player && event.entity !== player && event.entity is EntityLivingBase) {
                ComboCounter.onHit()
            }
            if (event.entity !== player || event.isCanceled) return
            KnockbackIndicator.onHurt(source)
            ComboCounter.onHurt()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music hurt effects failed: {}", t)
        }
    }

    fun onChat(event: ClientChatReceivedEvent) {
        try {
            // 聊天数据模块先收；动作栏（type 2）不进历史，避免刷屏
            if (event.type != 2.toByte()) {
                AdninRuntime.onChat(event.message?.unformattedText, System.currentTimeMillis())
            }
            if (!autoGG.isEnabled()) return
            // 没有网络包回调，用聊天组件合成一个 S02PacketChat 复用原逻辑
            autoGG.onPacket(true, S02PacketChat(event.message))
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music auto gg failed: {}", t)
        }
    }

    fun onKeyInput() {
        try {
            if (!Keyboard.getEventKeyState()) return
            if (Keyboard.isRepeatEvent()) return
            val key = Keyboard.getEventKey()
            if (key == 0) return
            if (autoText.isEnabled()) autoText.onKey(key)
            if (sprint.isEnabled()) sprint.onKey(key)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music key tools failed: {}", t)
        }
    }

    // 视野锥剔除会对每个实体调用一次，缓存视线向量，避免每帧重复创建 Vec3
    private var fovLookVec: Vec3? = null
    private var fovLookYaw = 0f
    private var fovLookPitch = 0f

    /** 实体渲染距离 / 分类系数 / 视野锥剔除；任何一项命中都直接取消本次活体渲染 */
    fun onRenderLivingPre(e: RenderLivingEvent.Pre<EntityLivingBase>) {
        try {
            val player = mc.thePlayer ?: return
            val entity = e.entity ?: return
            if (entity === player) return

            // 0) 隐藏盔甲架：整只实体跳过，省掉骨骼 / 装备 / 手臂的整套模型提交
            if ((Performance.hideArmorStands || Performance.ignoreStands) && entity is EntityArmorStand) {
                e.isCanceled = true
                return
            }

            val d = entity.getDistanceToEntity(player)

            // 1) 统一渲染距离（0 = 不限）
            val limit = Performance.entityDistance
            if (limit > 0 && d > limit.toFloat()) {
                e.isCanceled = true
                return
            }

            // 2) 分类距离系数：让「玩家/被动/敌对/其他」四个滑条真正生效
            val coeff = when (entity) {
                is EntityPlayer -> Performance.playerRenderDistance
                is EntityAnimal -> Performance.passiveRenderDistance
                is EntityMob -> Performance.hostileRenderDistance
                else -> Performance.miscRenderDistance
            }
            if (coeff < 0.999) {
                val base = if (limit > 0) limit.toDouble()
                else (mc.gameSettings?.renderDistanceChunks ?: 8) * 16.0
                if (d > base * coeff) {
                    e.isCanceled = true
                    return
                }
            }

            // 3) 视野锥剔除（新增）：只保留视野角内的活体，背后的直接跳过
            if (Performance.entityFovCulling && d > 3.0f) {
                val look = lookVector(player)
                val vx = entity.posX - player.posX
                val vy = entity.posY + entity.height * 0.5 - (player.posY + player.getEyeHeight().toDouble())
                val vz = entity.posZ - player.posZ
                val len = Math.sqrt(vx * vx + vy * vy + vz * vz)
                if (len > 1.0E-4) {
                    val dot = (vx * look.xCoord + vy * look.yCoord + vz * look.zCoord) / len
                    // 用余弦阈值直接比较，省掉每帧每个实体的 acos + 角度换算
                    val cosHalf = Math.cos(Math.toRadians(Performance.entityFovAngle.coerceIn(60, 170) / 2.0))
                    if (dot < cosHalf) {
                        e.isCanceled = true
                    }
                }
            }
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music entity distance failed: {}", t)
        }
    }

    /** 一帧内玩家朝向不变，视线向量只算一次即可给所有实体复用 */
    private fun lookVector(player: EntityPlayer): Vec3 {
        val cached = fovLookVec
        if (cached != null && player.rotationYaw == fovLookYaw && player.rotationPitch == fovLookPitch) {
            return cached
        }
        val v = player.getLookVec()
        fovLookVec = v
        fovLookYaw = player.rotationYaw
        fovLookPitch = player.rotationPitch
        return v
    }

    fun onNametag(event: RenderLivingEvent.Specials.Pre<EntityLivingBase>) {
        try {
            val entity = event.entity
            // 性能：远处名牌剔除（默认关，开启后直接省掉远处的名字/血条提交）
            if (Performance.nametagCulling && entity != null) {
                val player = mc.thePlayer
                if (player != null && entity !== player
                    && entity.getDistanceToEntity(player) > Performance.nametagDistance
                ) {
                    event.isCanceled = true
                    return
                }
            }
            if (!LevelTag.using) return
            if (!LevelTag.qualify(entity)) {
                event.isCanceled = true
                return
            }
            if (entity === mc.thePlayer && !LevelTag.showSelf) {
                event.isCanceled = true
            }
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music level tag failed: {}", t)
        }
    }
}
