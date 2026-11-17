package me.sorihiko.dango.utils.render

import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13

/**
 * 保存 / 恢复几个常用的 GL 开关（雾、光照、透明测试、剔除、深度、深度写、裁剪、混合、两层贴图）。
 *
 * 直接用 GL 改这些开关时，MC 的 GlStateManager 缓存不知道，事后它以为状态没变就不会再去设，
 * 比如雾被关掉后天空下半截的黑底会露出来。所以直接改 GL 状态的地方，画完都用这个原样恢复。
 *
 * 还原必须**走 GlStateManager**：它是布尔镜像缓存，用原生 GL 还原会让缓存与真实状态再次脱节，
 * 后面的渲染就会「以为已经开/关了」而跳过 —— 深度、剔除、光照错乱都由此而来。
 */
object GlCaps {

    private val caps = intArrayOf(
        GL11.GL_FOG, GL11.GL_LIGHTING, GL11.GL_ALPHA_TEST, GL11.GL_CULL_FACE,
        GL11.GL_DEPTH_TEST, GL11.GL_SCISSOR_TEST, GL11.GL_BLEND,
    )

    class Saved internal constructor(
        val flags: BooleanArray,
        val tex0: Boolean,
        val tex1: Boolean,
        val activeTex: Int,
        val depthMask: Boolean,
    )

    fun save(): Saved {
        val flags = BooleanArray(caps.size) { GL11.glIsEnabled(caps[it]) }
        val active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit)
        val t0 = GL11.glIsEnabled(GL11.GL_TEXTURE_2D)
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit)
        val t1 = GL11.glIsEnabled(GL11.GL_TEXTURE_2D)
        GlStateManager.setActiveTexture(active)
        val mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        return Saved(flags, t0, t1, active, mask)
    }

    fun restore(s: Saved) {
        for (i in caps.indices) setCached(caps[i], s.flags[i])
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit)
        setCached(GL11.GL_TEXTURE_2D, s.tex0)
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit)
        setCached(GL11.GL_TEXTURE_2D, s.tex1)
        GlStateManager.setActiveTexture(s.activeTex)
        GlStateManager.depthMask(s.depthMask)
    }

    /** 走 GlStateManager 设开关，保证它的缓存和真实 GL 状态一致 */
    private fun setCached(cap: Int, on: Boolean) {
        when (cap) {
            GL11.GL_FOG -> if (on) GlStateManager.enableFog() else GlStateManager.disableFog()
            GL11.GL_LIGHTING -> if (on) GlStateManager.enableLighting() else GlStateManager.disableLighting()
            GL11.GL_ALPHA_TEST -> if (on) GlStateManager.enableAlpha() else GlStateManager.disableAlpha()
            GL11.GL_CULL_FACE -> if (on) GlStateManager.enableCull() else GlStateManager.disableCull()
            GL11.GL_DEPTH_TEST -> if (on) GlStateManager.enableDepth() else GlStateManager.disableDepth()
            GL11.GL_BLEND -> if (on) GlStateManager.enableBlend() else GlStateManager.disableBlend()
            GL11.GL_TEXTURE_2D -> if (on) GlStateManager.enableTexture2D() else GlStateManager.disableTexture2D()
            // 1.8.9 的 GlStateManager 没有 scissor 对应的缓存，只能走原生 GL
            else -> if (on) GL11.glEnable(cap) else GL11.glDisable(cap)
        }
    }
}
