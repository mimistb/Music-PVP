package me.sorihiko.dango.utils.render

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL30

import java.nio.ByteBuffer

class RenderTarget(
    private val color: Boolean,
    private val depth: Boolean
) {
    var fbo = -1
        private set
    var depthTexture = -1
        private set
    var framebufferTexture = -1
        private set

    var width = 0
        private set
    var height = 0
        private set

    fun create(width: Int, height: Int) {
        delete()
        this.width = width
        this.height = height

        // 记录进来时的绑定：调用方可能正渲染到别的 FBO / 绑着别的纹理，
        // 不能无条件 bind(0)，否则外面那一层渲染会被打断。
        val previousFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
        val previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        try {
            fbo = GL30.glGenFramebuffers()
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)

            if (color) {
                framebufferTexture = GL11.glGenTextures()
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, framebufferTexture)

                GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGBA8,
                    width,
                    height,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    null as ByteBuffer?
                )

                GL11.glTexParameteri(
                    GL11.GL_TEXTURE_2D,
                    GL11.GL_TEXTURE_MIN_FILTER,
                    GL11.GL_NEAREST
                )
                GL11.glTexParameteri(
                    GL11.GL_TEXTURE_2D,
                    GL11.GL_TEXTURE_MAG_FILTER,
                    GL11.GL_NEAREST
                )

                GL30.glFramebufferTexture2D(
                    GL30.GL_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D,
                    framebufferTexture,
                    0
                )
            }

            if (depth) {
                depthTexture = GL11.glGenTextures()
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture)

                GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL14.GL_DEPTH_COMPONENT24,
                    width,
                    height,
                    0,
                    GL11.GL_DEPTH_COMPONENT,
                    GL11.GL_FLOAT,
                    null as ByteBuffer?
                )

                GL11.glTexParameteri(
                    GL11.GL_TEXTURE_2D,
                    GL11.GL_TEXTURE_MIN_FILTER,
                    GL11.GL_NEAREST
                )

                GL11.glTexParameteri(
                    GL11.GL_TEXTURE_2D,
                    GL11.GL_TEXTURE_MAG_FILTER,
                    GL11.GL_NEAREST
                )

                GL30.glFramebufferTexture2D(
                    GL30.GL_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D,
                    depthTexture,
                    0
                )
            }

            val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                // 先把这次生成的 FBO / 纹理删掉再抛，否则每次重建失败都会泄漏一组 GL 对象
                cleanupObjects()
                this.width = 0
                this.height = 0
                throw RuntimeException("Framebuffer create failed: $status")
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo)
        }
    }

    fun delete() {
        cleanupObjects()
    }

    private fun cleanupObjects() {
        if (fbo != -1) {
            GL30.glDeleteFramebuffers(fbo)
            fbo = -1
        }

        if (depthTexture != -1) {
            GL11.glDeleteTextures(depthTexture)
            depthTexture = -1
        }

        if (framebufferTexture != -1) {
            GL11.glDeleteTextures(framebufferTexture)
            framebufferTexture = -1
        }
    }

    fun bind(review: Boolean = false) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)

        if (review) {
            GL11.glViewport(0, 0, width, height)
        }
    }

    fun bindTexture() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, framebufferTexture)
    }

    fun bindDepthTexture() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture)
    }
}