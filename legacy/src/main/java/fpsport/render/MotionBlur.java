package fpsport.render;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.client.shader.ShaderUniform;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 动态模糊（MotionBlur）——两种模式，参数与原模块一致，**无 UI**。
 *
 * <ul>
 *   <li>{@link #MODE_OLD}：帧缓冲乒乓叠加（不依赖 post shader），screen 打开时自动跳过；</li>
 *   <li>{@link #MODE_NEW}：加载 {@code shaders/post/motionblur.json} 并用 {@code Phosphor} 调强度。</li>
 * </ul>
 *
 * <p>宿主在 HUD 渲染结束后调用 {@link #onRenderOverlay()}（原客户端是 {@code GuiIngame#renderGameOverlay}
 * 的 RETURN 处派发 {@code EventMotionBlur}）。
 *
 * <p>资源：需要的 3 个文件已放在 {@code resources/assets/minecraft/shaders/} 下。
 */
public class MotionBlur extends Feature {

    public static final int MODE_OLD = 0;
    public static final int MODE_NEW = 1;

    public static final String[] MODES = {"Old", "New"};

    public static final float STRENGTH_MIN = 0.0F;
    public static final float STRENGTH_MAX = 10.0F;
    public static final float STRENGTH_STEP = 0.5F;
    public static final float STRENGTH_DEFAULT = 2.0F;

    // GLSL 120：1.8.9 是兼容型 GL 上下文，#version 150 的片元着色器不保证能编译（macOS 必挂）。
    private static final String SHADER_PATH = "shaders/post/motionblur.json";
    private static final String SHADER_GROUP_NAME = "minecraft:" + SHADER_PATH;

    /** 模式：0 Old / 1 New，默认 New。 */
    public static int mode = MODE_NEW;
    /** 强度：默认 2.0；范围 [0, 10]，步长 0.5。 */
    public static float strength = STRENGTH_DEFAULT;

    /** 宿主可选注入：判断宿主是否处于 FastRender（开启动态模糊要临时关掉它）。 */
    public static BooleanSupplier fastRenderActive = () -> false;
    /** 宿主可选注入：设置宿主自己的 FastRender 开关。 */
    public static Consumer<Boolean> fastRenderSetter = disabled -> {
    };
    /** 宿主可选注入：给玩家的提示文案（默认丢弃，绝不弹自定义 UI）。 */
    public static Consumer<String> notifier = message -> {
    };

    private static Framebuffer blurBufferMain;
    private static Framebuffer blurBufferInto;
    private static Field listShadersField;
    private static boolean listShadersUnavailable;

    private boolean shaderLoadFailed;

    public MotionBlur() {
        super("MotionBlur");
    }

    @Override
    protected void onEnable() {
        shaderLoadFailed = false;
        if (fastRenderActive.getAsBoolean()) {
            fastRenderSetter.accept(false);
            notifier.accept("Motion blur disabled FastRender to work properly");
        }
    }

    @Override
    protected void onDisable() {
        Minecraft.getMinecraft().entityRenderer.stopUseShader();
        // Old 模式的乒乓 FBO 是静态的，不显式删除会留到进程退出。
        deleteBlurBuffers();
    }

    /** 在 HUD 画完之后调用。 */
    public void onRenderOverlay() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return;
        }
        if (mode == MODE_OLD) {
            if (mc.currentScreen == null) {
                if (isUsingShader()) {
                    mc.entityRenderer.stopUseShader();
                }
                blur(strength);
            }
        } else if (mode == MODE_NEW) {
            if (mc.currentScreen != null || shaderLoadFailed) {
                return;
            }
            if (!isUsingShader()) {
                // 原版 loadShader 会直接替换 theShaderGroup 而不释放旧的，必须先停掉否则 FBO 泄漏。
                mc.entityRenderer.stopUseShader();
                mc.entityRenderer.loadShader(new ResourceLocation(SHADER_PATH));
                // loadShader 结束时绑的是 GL framebuffer 0，帧中途必须绑回主缓冲。
                mc.getFramebuffer().bindFramebuffer(true);
                if (!isUsingShader()) {
                    // 不重试：每帧重载会每帧泄漏一个 ShaderGroup。
                    shaderLoadFailed = true;
                    return;
                }
            }
            float value = 0.7F + strength / 100.0F * 3.0F - 0.01F;
            ShaderGroup shaderGroup = mc.entityRenderer.getShaderGroup();
            if (shaderGroup == null) {
                return;
            }
            List<Shader> listShaders = getListShaders(shaderGroup);
            if (listShaders == null) {
                return;
            }
            for (Shader shader : listShaders) {
                ShaderUniform phosphor = shader.getShaderManager().getShaderUniform("Phosphor");
                if (phosphor != null) {
                    phosphor.set(value, 0, 0);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Shader> getListShaders(ShaderGroup shaderGroup) {
        if (listShadersUnavailable) {
            return null;
        }
        if (listShadersField == null) {
            try {
                listShadersField = ShaderGroup.class.getDeclaredField("listShaders");
            } catch (NoSuchFieldException e) {
                try {
                    // 生产环境 remap 后的 searge 名
                    listShadersField = ShaderGroup.class.getDeclaredField("field_148031_d");
                } catch (NoSuchFieldException e2) {
                    listShadersUnavailable = true;
                    return null;
                }
            }
            listShadersField.setAccessible(true);
        }
        try {
            return (List<Shader>) listShadersField.get(shaderGroup);
        } catch (IllegalAccessException e) {
            listShadersUnavailable = true;
            return null;
        }
    }

    private boolean isUsingShader() {
        EntityRenderer entityRenderer = Minecraft.getMinecraft().entityRenderer;
        return entityRenderer.isShaderActive()
                && entityRenderer.getShaderGroup() != null
                && entityRenderer.getShaderGroup().getShaderGroupName().equalsIgnoreCase(SHADER_GROUP_NAME);
    }

    /** 宿主关服时也调用一次。 */
    public static void deleteBlurBuffers() {
        if (blurBufferMain != null) {
            blurBufferMain.deleteFramebuffer();
            blurBufferMain = null;
        }
        if (blurBufferInto != null) {
            blurBufferInto.deleteFramebuffer();
            blurBufferInto = null;
        }
    }

    private static Framebuffer checkFramebufferSizes(Framebuffer framebuffer, int width, int height) {
        if (framebuffer == null || framebuffer.framebufferWidth != width || framebuffer.framebufferHeight != height) {
            if (framebuffer == null) {
                framebuffer = new Framebuffer(width, height, true);
            } else {
                framebuffer.createBindFramebuffer(width, height);
            }
            framebuffer.setFramebufferFilter(9728); // GL_NEAREST
        }
        return framebuffer;
    }

    private static void drawTexturedRectNoBlend(float x, float y, float width, float height,
                                                float uMin, float uMax, float vMin, float vMax, int filter) {
        GlStateManager.enableTexture2D();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldrenderer = tessellator.getWorldRenderer();
        worldrenderer.begin(7, DefaultVertexFormats.POSITION_TEX);
        worldrenderer.pos(x, y + height, 0.0).tex(uMin, vMax).endVertex();
        worldrenderer.pos(x + width, y + height, 0.0).tex(uMax, vMax).endVertex();
        worldrenderer.pos(x + width, y, 0.0).tex(uMax, vMin).endVertex();
        worldrenderer.pos(x, y, 0.0).tex(uMin, vMin).endVertex();
        tessellator.draw();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, 9728);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, 9728);
    }

    /** Old 模式：上一帧与当前帧做加权混合。 */
    public static void blur(float multiplier) {
        if (!OpenGlHelper.isFramebufferEnabled()) {
            return;
        }
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        try {
            Minecraft mc = Minecraft.getMinecraft();
            ScaledResolution sr = new ScaledResolution(mc);
            int width = mc.getFramebuffer().framebufferWidth;
            int height = mc.getFramebuffer().framebufferHeight;
            // 浮点除法：整数除法会少掉余数像素，右下角留一条没处理的边。
            float scaledWidth = (float) width / sr.getScaleFactor();
            float scaledHeight = (float) height / sr.getScaleFactor();

            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.loadIdentity();
            GlStateManager.ortho(0.0, scaledWidth, scaledHeight, 0.0, 2000.0, 4000.0);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.loadIdentity();
            GlStateManager.translate(0F, 0F, -2000F);

            blurBufferMain = checkFramebufferSizes(blurBufferMain, width, height);
            blurBufferInto = checkFramebufferSizes(blurBufferInto, width, height);

            blurBufferInto.framebufferClear();
            blurBufferInto.bindFramebuffer(true);

            OpenGlHelper.glBlendFunc(770, 771, 0, 1);
            GlStateManager.disableLighting();
            GlStateManager.disableFog();
            GlStateManager.disableBlend();

            mc.getFramebuffer().bindFramebufferTexture();
            GlStateManager.color(1F, 1F, 1F, 1F);
            drawTexturedRectNoBlend(0F, 0F, scaledWidth, scaledHeight, 0F, 1F, 0F, 1F, 9728);

            GlStateManager.enableBlend();
            blurBufferMain.bindFramebufferTexture();
            GlStateManager.color(1F, 1F, 1F, multiplier / 10 - 0.1F);
            drawTexturedRectNoBlend(0F, 0F, scaledWidth, scaledHeight, 0F, 1F, 1F, 0F, 9728);

            mc.getFramebuffer().bindFramebuffer(true);
            blurBufferInto.bindFramebufferTexture();
            GlStateManager.color(1F, 1F, 1F, 1F);
            GlStateManager.enableBlend();
            OpenGlHelper.glBlendFunc(770, 771, 1, 771);

            drawTexturedRectNoBlend(0F, 0F, scaledWidth, scaledHeight, 0F, 1F, 0F, 1F, 9728);

            Framebuffer temp = blurBufferMain;
            blurBufferMain = blurBufferInto;
            blurBufferInto = temp;
        } finally {
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();

            // 上面的 raw GL 调用绕过了 GlStateManager 的缓存，必须通过它把等价状态写回去，
            // 否则后续的 enableBlend()/color() 会变成空操作。
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.enableBlend();
            GlStateManager.color(1F, 1F, 1F, 1F);
            GlStateManager.bindTexture(0);
        }
    }

    /** 宿主 UI 滑条用：按 0.5 吸附并夹到 [0, 10]。 */
    public static float clampStrength(float value) {
        float snapped = Math.round(value / STRENGTH_STEP) * STRENGTH_STEP;
        if (!Float.isNaN(snapped)) {
            strength = Math.max(STRENGTH_MIN, Math.min(STRENGTH_MAX, snapped));
        }
        return strength;
    }
}
