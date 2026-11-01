package me.sorihiko.dango.legacy

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
import fpsport.render.ItemPhysics
import fpsport.render.MoreParticles
import fpsport.utility.ArmorDisplay
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

import net.minecraft.client.settings.KeyBinding
import net.minecraft.entity.EntityLivingBase
import net.minecraft.network.play.server.S02PacketChat
import net.minecraft.world.World
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.client.event.EntityViewRenderEvent
import net.minecraftforge.client.event.MouseEvent
import net.minecraftforge.client.event.RenderLivingEvent
import net.minecraftforge.event.entity.player.AttackEntityEvent

import org.lwjgl.input.Keyboard

/**
 * fpsport 的运行层：把设置值同步进 fpsport 的静态字段，并把 Forge 事件转发给对应功能。
 *
 * 不画任何东西、不新建 UI；所有回调都吞异常打日志，保证不会把异常抛进游戏循环。
 * 不可用的三项（OldAnimations / NameProtect / RawInput）不实例化；护甲显示只产出数据，
 * 由信息胶囊（HudInfoModule）负责绘制。
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

    private val specials = arrayOf("None", "Heart", "Flame", "Blood")
    private val killEffects = arrayOf("None", "Lightning", "Explosion")

    private var lastWorld: World? = null
    private var fogColorIndex = -1
    // 4 组快捷发言的指纹；只有内容变化时才重建 AutoText.entries
    private var autoTextFingerprint: List<Pair<Int, String>>? = null
    private var forcedSprint = false

    // ---------------------------------------------------------------- 每 tick

    fun tick() {
        try {
            val module = FpsToolsModule.of() ?: return
            val world = mc.theWorld
            if (world !== lastWorld) {
                // 换世界：清掉旧实体引用，避免在新世界按旧坐标放特效
                lastWorld = world
                DamageIndicator.clearState()
                moreParticles.setEnabled(false)
                // 折叠缓存放的是上一局的聊天内容，换世界必须清掉
                BetterChat.reset()
            }
            sync(module)
            // 点击速度：按配置窗口重算（宿主 HUD 直接读 CPSDisplay.leftCps/rightCps）
            CPSDisplay.onTick()
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
        SmoothZoom.zoomBind = m.keyZoom.value
        SmoothZoom.smoothCamera = m.zoomSmoothCamera.get()
        SmoothZoom.wheelZoom = m.zoomWheel.get()
        SmoothZoom.smoothMouse = m.zoomSmoothMouse.get()
        SmoothZoom.speed = m.zoomSpeed.value.toDouble()
        SmoothZoom.baseScale = m.zoomScale.value.coerceIn(1f, 20f)
        if (SmoothZoom.zoomScale <= 0f) SmoothZoom.zoomScale = SmoothZoom.baseScale

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
        FreeLook.bind = m.keyFreeLook.value
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
        // 4 组快捷发言：拼成不可变列表当指纹，只有内容变化时才重建
        val quickEntries = listOf(
            m.keyAutoText.value to m.msgAutoText.get(),
            m.keyQuick2.value to m.msgQuick2.get(),
            m.keyQuick3.value to m.msgQuick3.get(),
            m.keyQuick4.value to m.msgQuick4.get(),
        )
        if (quickEntries != autoTextFingerprint) {
            autoTextFingerprint = quickEntries
            autoText.entries.clear()
            for ((key, message) in quickEntries) {
                if (key != 0 && message.isNotEmpty()) autoText.entries.add(AutoText.Entry(key, message))
            }
        }

        levelTag.setEnabled(on && m.levelTag.get())
        LevelTag.showSelf = m.levelShowSelf.get()
        LevelTag.hideNPC = m.levelHideNPC.get()

        particlesModifier.setEnabled(on && m.particlesModifier.get())
        ParticlesModifier.block = m.pmKeepBlock.get()
        FpsFlags.dropParticles = ParticlesModifier.using && !ParticlesModifier.block

        performance.setEnabled(on && m.performance.get())
        Performance.fpsLimit = m.fpsLimit.value
        Performance.renderDistance = m.perfRenderDistance.value
        Performance.entityDistance = m.perfEntityDistance.value
        Performance.entityShadows = m.perfEntityShadows.get()
        Performance.glassQuality = m.perfGlassQuality.value.coerceIn(0, 2)

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

        betterChat.setEnabled(on && m.betterChat.get())
        BetterChat.foldMessage = m.chatFold.get()
        BetterChat.copyMessage = m.chatCopy.get()
        BetterChat.minCopyLength = m.chatMinLength.value.coerceIn(0, 32)
        BetterChat.foldActionBar = m.chatFoldActionBar.get()

        // 只计数、不画任何东西；数据由 publishHud 送进信息胶囊
        CPSDisplay.windowMs = m.cpsWindow.value.coerceIn(200, 5000)
        cpsDisplay.setEnabled(on && m.cpsDisplay.get())

        // 关键：先把 Sprint 功能本身置为启用，否则 isEnabled() 永远是 false，
        // forceSprint()/onKey() 会被直接短路，强制疾跑完全不生效。
        sprint.setEnabled(on && m.sprint.get())
        Sprint.toggleSprint = m.sprintToggle.get()

        // 模块整体关闭或关掉「强制疾跑」时立刻还原疾跑按键，避免残留按键状态
        if (!on || !m.sprint.get()) releaseSprintKey()
    }

    /**
     * 把护甲 / 点击速度的展示数据写进信息胶囊的静态桥（common 不能反向依赖 legacy）。
     *
     * <p>护甲数组整体替换、只读快照；CPS 用 -1 表示「该侧不显示」。
     */
    private fun publishHud(m: FpsToolsModule) {
        val on = m.enabled
        if (on && m.armorDisplay.get()) {
            ArmorDisplay.update(
                mc.thePlayer,
                m.armorShowSlots.get(),
                m.armorShowEmpty.get(),
                m.armorPercent.value != 0,
                m.armorOnlyDamaged.get(),
                m.armorLowThreshold.value.coerceIn(0, 100),
                m.armorLowWarn.get(),
            )
            HudInfoModule.armorSegments = ArmorDisplay.segments()
            HudInfoModule.armorWarn = ArmorDisplay.warnFlags()
        } else {
            ArmorDisplay.clear()
            HudInfoModule.armorSegments = null
            HudInfoModule.armorWarn = null
        }

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
            if (mc.theWorld == null) return
            damageIndicator.onRender3D()
            BlockOverlay.onRender3D()
            Performance.applyFpsLimit()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music fps render failed: {}", t)
        }
    }

    // ---------------------------------------------------------------- 事件

    fun onFov(event: EntityViewRenderEvent.FOVModifier) {
        try {
            event.fov = SmoothZoom.adjustFov(0f, true, event.fov)
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music zoom fov failed: {}", t)
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
            if (event.dwheel != 0 && SmoothZoom.zoom && SmoothZoom.wheelZoom) {
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
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music attack effects failed: {}", t)
        }
    }

    fun onChat(event: ClientChatReceivedEvent) {
        try {
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

    /** 实体渲染距离：超出 perfEntityDistance 的活体直接取消渲染（0 = 不限） */
    fun onRenderLivingPre(e: RenderLivingEvent.Pre<EntityLivingBase>) {
        try {
            val dist = Performance.entityDistance
            if (dist <= 0) return
            val player = mc.thePlayer ?: return
            val entity = e.entity ?: return
            if (entity === player) return
            val d = entity.getDistanceToEntity(player)
            if (d > dist.toFloat()) e.isCanceled = true
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music entity distance failed: {}", t)
        }
    }

    fun onNametag(event: RenderLivingEvent.Specials.Pre<EntityLivingBase>) {
        try {
            if (!LevelTag.using) return
            val entity = event.entity
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
