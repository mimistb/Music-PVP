package me.sorihiko.dango.legacy

import me.sorihiko.dango.gfx.HeadTexture

import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.util.ResourceLocation

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13

/**
 * 玩家皮肤头像：整张皮肤贴图 + 头部的 UV 子区（像素 8..16）。
 * 皮肤可能是 64×64 或 64×32，所以 UV 按贴图实际尺寸算，只查一次。
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

    private var ready = false

    /** 贴图还没下载好时返回 false，下一帧再试 */
    fun refresh(): Boolean {
        if (ready) return true
        try {
            // 查询贴图尺寸必须是在 GL_TEXTURE0 上绑定的那张（玻璃着色器会切纹理单元）
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            // 同步一下原版的状态缓存，免得之后 setActiveTexture 变成空操作
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit)
            bind()
            val w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH)
            val h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)
            if (w > 0 && h > 0) {
                u0 = 8f / w
                v0 = 8f / h
                u1 = 16f / w
                v1 = 16f / h
                ready = true
            }
        } catch (_: Throwable) {
            // 贴图不可用时保持占位
        }
        return ready
    }
}
