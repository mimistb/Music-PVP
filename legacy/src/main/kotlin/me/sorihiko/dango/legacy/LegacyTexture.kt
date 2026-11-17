package me.sorihiko.dango.legacy

import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.gfx.Textures

import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ResourceLocation

import org.lwjgl.opengl.GL11

import java.awt.image.BufferedImage

/** 1.8.9 的贴图：交给原版 TextureManager 管理 */
open class LegacyTexture(val location: ResourceLocation) : Texture {

    /** 是否允许改成双线性过滤。玩家皮肤必须保持最近邻，否则世界里的皮肤整张糊掉 */
    protected open val smooth: Boolean get() = true

    private var filtered = false

    fun bind() {
        GlStateManager.bindTexture(0)
        mc.textureManager.bindTexture(location)
        // DynamicTexture 默认是最近邻采样，背景图 / 封面在缩放时会锯齿；
        // 这里改成双线性过滤，放大缩小都更平滑（每张贴图只设置一次，不会每帧重复改状态）。
        // 玩家皮肤反过来：必须显式锁回最近邻，否则被前面的 UI 绘制改成 LINEAR 之后，
        // 世界里同一个 texture 对象（原版共用）整张都会糊掉。
        if (!filtered) {
            filtered = true
            val filter = if (smooth) GL11.GL_LINEAR else GL11.GL_NEAREST
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter)
        }
    }
}

object LegacyTextures : Textures {
    override fun upload(name: String, image: BufferedImage): Texture =
        LegacyTexture(mc.textureManager.getDynamicTextureLocation(name, DynamicTexture(image)))

    override fun delete(texture: Texture) {
        mc.textureManager.deleteTexture((texture as LegacyTexture).location)
    }
}
