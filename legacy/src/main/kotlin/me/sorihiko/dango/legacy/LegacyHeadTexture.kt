package me.sorihiko.dango.legacy

import me.sorihiko.dango.gfx.HeadTexture

import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.util.ResourceLocation

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13

/**
 * 玩家皮肤头像：整张皮肤贴图 + 头部的 UV 子区（像素 8..16）。
 *
 * 皮肤可能是 64×64 或 64×32（以及 OptiFine 的 128×128 / 128×64），所以 UV 按贴图实际尺寸换算；
 * 贴图没下载完时原版只给一张空贴图（尺寸 0 或 1×1），此时必须当作「未就绪」继续等，
 * 否则会拿占位尺寸算出错误的 UV，头像就会显示成皮肤上随机的一块。
 */
class LegacyHeadTexture(location: ResourceLocation) : LegacyTexture(location), HeadTexture {

    override var u0 = 0f
        private set
    override var v0 = 0f
        private set
    override var u1 = 1f
        private set
    override var v1 = 1f
        private set

    /** 玩家皮肤必须保持最近邻采样：改成双线性会把世界里整张皮肤糊掉 */
    override val smooth: Boolean get() = false

    private var ready = false
    private var lastW = -1
    private var lastH = -1

    /** 贴图还没下载好 / 尺寸不是皮肤布局时返回 false，下一帧再试 */
    fun refresh(): Boolean {
        try {
            // 查询贴图尺寸必须是在 GL_TEXTURE0 上绑定的那张（玻璃着色器会切纹理单元）
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            // 同步一下原版的状态缓存，免得之后 setActiveTexture 变成空操作
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit)
            bind()
            val w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH)
            val h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)
            if (w != lastW || h != lastH) {
                lastW = w
                lastH = h
                ready = applySize(w, h)
            }
        } catch (_: Throwable) {
            // 贴图不可用时保持占位
            ready = false
        }
        return ready
    }

    /**
     * 只接受原版皮肤布局：宽是 64 的整数倍，且高宽比为 1:1（64×64）或 1:2（64×32）。
     * 占位贴图（0×0、1×1、16×16 之类）一律判为未就绪。
     */
    private fun applySize(w: Int, h: Int): Boolean {
        if (w < 64 || h < 32 || w % 64 != 0) return false
        if (h != w && h != w / 2) return false
        // 脸部贴图固定落在像素 8..16，HD 皮肤按横向倍数一起放大
        val scale = w / 64.0
        val p0 = 8.0 * scale
        val p1 = 16.0 * scale
        u0 = (p0 / w).toFloat()
        v0 = (p0 / h).toFloat()
        u1 = (p1 / w).toFloat()
        v1 = (p1 / h).toFloat()
        return true
    }
}
