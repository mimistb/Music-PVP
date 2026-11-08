package me.sorihiko.dango.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;

import me.sorihiko.dango.DangoLogger;

import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * 主标题界面（GuiMainMenu）的自定义背景图。
 *
 * <p>图片在后台线程用 ImageIO 读取，下一次主菜单帧在渲染线程上传成动态纹理；
 * 支持拉伸铺满 / 居中原始比例 / 平铺重复三种填充，并可在图片上叠一层黑色遮罩。
 * 任何失败都只打一次日志，主菜单继续使用原来的液态玻璃渐变底。
 */
public final class MenuBackground {

    private static final ResourceLocation LOCATION =
            new ResourceLocation("dangomusic", "menu_background");

    /** 后台线程读好、等待渲染线程上传的图 */
    private static volatile BufferedImage pendingImage;
    private static volatile String pendingPath = "";
    /** 已经上传成功的路径（用于避免每帧重复读盘） */
    private static String loadedPath = "";
    /** 每次换图自增。后台线程只允许把"最新一次请求"的结果写回，避免旧图覆盖新路径。 */
    private static long requestSeq;
    /** 读盘失败冷却：同一路径隔一段时间才重试，既不放弃也不每帧起线程 */
    private static String failedPath = "";
    private static long failedAt;
    private static final long RETRY_MS = 3000L;
    private static int textureId = -1;
    private static int imageW;
    private static int imageH;

    private MenuBackground() {
    }

    /** 路径变化时启动一次异步读取；空路径表示关闭背景图。 */
    public static void ensure(String path) {
        String target = path == null ? "" : path.trim();
        if (target.equals(loadedPath) || target.equals(pendingPath)) return;
        // 同一张图读盘失败后短暂冷却，避免每帧重开线程
        if (target.equals(failedPath) && System.currentTimeMillis() - failedAt < RETRY_MS) return;

        final long request = ++requestSeq;
        pendingPath = target;
        pendingImage = null;
        failedPath = "";
        loadedPath = "";
        releaseTexture();
        if (target.isEmpty()) return;

        Thread thread = new Thread(() -> {
            BufferedImage image = null;
            Throwable error = null;
            try {
                image = ImageIO.read(new File(target));
                if (image == null) throw new IllegalArgumentException("unsupported image format");
            } catch (Throwable t) {
                error = t;
            }
            // 只有最新一次请求才能写回；否则用户在读盘期间又换了图，旧图会覆盖新路径
            if (request != requestSeq) return;
            if (error != null) {
                pendingPath = "";
                failedPath = target;
                failedAt = System.currentTimeMillis();
                DangoLogger.warn("Menu background load failed: {}", error.toString());
                return;
            }
            pendingImage = image;
        }, "DangoMenuBackground");
        thread.setDaemon(true);
        thread.start();
    }

    /** 渲染线程：把后台读好的图上传成纹理。 */
    public static void tick() {
        BufferedImage image = pendingImage;
        if (image == null) return;
        pendingImage = null;
        try {
            DynamicTexture texture = new DynamicTexture(image);
            Minecraft.getMinecraft().getTextureManager().loadTexture(LOCATION, texture);
            textureId = texture.getGlTextureId();
            imageW = image.getWidth();
            imageH = image.getHeight();
            loadedPath = pendingPath;
        } catch (Throwable t) {
            textureId = -1;
            loadedPath = "";
            DangoLogger.warn("Menu background upload failed: {}", t.toString());
        }
    }

    public static boolean ready() {
        return textureId > 0 && imageW > 0 && imageH > 0;
    }

    /**
     * 画整屏背景图。
     *
     * @param mode    0 = 拉伸铺满，1 = 居中原始比例，2 = 平铺重复
     * @param opacity 0~1
     * @param dim     0~1，图片上叠加的黑色遮罩
     */
    public static void draw(int width, int height, int mode, float opacity, float dim) {
        if (!ready() || opacity <= 0.002f) return;
        float alpha = Math.max(0f, Math.min(1f, opacity));
        Minecraft mc = Minecraft.getMinecraft();
        try {
            // 背景图只借用一帧的 GL 状态：结束后完整还原，避免影响后面的标题卡片与其它界面
            GlStateManager.pushAttrib();
            GlStateManager.enableBlend();
            GlStateManager.disableAlpha();
            GlStateManager.disableDepth();
            GlStateManager.enableTexture2D();
            GlStateManager.tryBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.color(1f, 1f, 1f, alpha);
            mc.getTextureManager().bindTexture(LOCATION);

            int scale = mc.gameSettings.guiScale;
            if (scale <= 0) scale = Math.max(1, mc.displayWidth / Math.max(1, width));

            if (mode == 1) {
                // 居中：图片按物理像素 1:1 显示，超出窗口时等比缩小
                float w = imageW / (float) scale;
                float h = imageH / (float) scale;
                float k = Math.min(1f, Math.min(width / w, height / h));
                w *= k;
                h *= k;
                quad((width - w) / 2f, (height - h) / 2f, w, h, 0f, 0f, 1f, 1f);
            } else if (mode == 2) {
                // 平铺：保持原图比例，单块至少 96 逻辑像素，避免小图产生过多顶点
                float tw = imageW / (float) scale;
                float th = imageH / (float) scale;
                if (tw < 96f) {
                    th *= 96f / tw;
                    tw = 96f;
                }
                if (th < 96f) {
                    tw *= 96f / th;
                    th = 96f;
                }
                for (float y = 0f; y < height; y += th) {
                    for (float x = 0f; x < width; x += tw) {
                        float w = Math.min(tw, width - x);
                        float h = Math.min(th, height - y);
                        quad(x, y, w, h, 0f, 0f, w / tw, h / th);
                    }
                }
            } else {
                quad(0f, 0f, width, height, 0f, 0f, 1f, 1f);
            }

            if (dim > 0.002f) {
                GlStateManager.disableTexture2D();
                GlStateManager.color(1f, 1f, 1f, 1f);
                float d = Math.min(1f, dim);
                WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
                wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
                wr.pos(0, height, 0).color(0f, 0f, 0f, d).endVertex();
                wr.pos(width, height, 0).color(0f, 0f, 0f, d).endVertex();
                wr.pos(width, 0, 0).color(0f, 0f, 0f, d).endVertex();
                wr.pos(0, 0, 0).color(0f, 0f, 0f, d).endVertex();
                Tessellator.getInstance().draw();
            }
        } catch (Throwable t) {
            // 背景失败不影响主菜单其余部分
        } finally {
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.popAttrib();
        }
    }

    private static void quad(float x, float y, float w, float h,
                             float u0, float v0, float u1, float v1) {
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(x, y + h, 0.0).tex(u0, v1).endVertex();
        wr.pos(x + w, y + h, 0.0).tex(u1, v1).endVertex();
        wr.pos(x + w, y, 0.0).tex(u1, v0).endVertex();
        wr.pos(x, y, 0.0).tex(u0, v0).endVertex();
        Tessellator.getInstance().draw();
    }

    private static void releaseTexture() {
        if (textureId <= 0) return;
        try {
            Minecraft.getMinecraft().getTextureManager().deleteTexture(LOCATION);
        } catch (Throwable ignored) {
            // 忽略
        }
        textureId = -1;
        imageW = 0;
        imageH = 0;
    }
}
