package me.sorihiko.dango.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;

import me.sorihiko.dango.DangoLogger;
import me.sorihiko.dango.legacy.LegacyMusicScreen;
import me.sorihiko.dango.ui.HelloIntro;

import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Map;

/**
 * 液态玻璃启动界面。
 *
 * <p>主菜单保持原版视觉（全景图 / logo / 按钮全部不动），只在右上角叠加一个滑杆入口图标，
 * 点击打开播放器设置页。图标与宿主 HUD 同一套材质：深色半透明底 + 白色描边 + #0A84FF 强调色。
 *
 * <p>注入点（见 {@code SplashGlassTransformer}）：
 * <ul>
 *   <li>GuiMainMenu#drawScreen 头部：{@link #beginMainMenuFrame()}（重置本帧图标可见性）；</li>
 *   <li>GuiMainMenu#drawScreen 末尾：{@link #drawMainMenuForeground(int, int)}（只画图标）；</li>
 *   <li>GuiScreen#mouseClicked 头部：{@link #onMainMenuClick(int, int, int)}（命中图标时消费点击）；</li>
 *   <li>GuiDownloadTerrain#drawScreen 末尾：{@link #drawLoadingForeground(int, int)}（带加载动画）。</li>
 * </ul>
 * 只画东西，不改任何原版控件逻辑；任何异常都吞掉，保证游戏能正常启动。
 */
public final class SplashGlassHook {

    // ---------------------------------------------------------------- 主题色（与 Theme.kt 对齐）

    /** 背景顶部：略微偏蓝的深灰。 */
    private static final float TOP_R = 0.042f, TOP_G = 0.048f, TOP_B = 0.066f;
    /** 背景底部：接近纯黑。 */
    private static final float BOT_R = 0.011f, BOT_G = 0.013f, BOT_B = 0.019f;
    /** 强调色 #0A84FF。 */
    private static final float ACC_R = 0.039f, ACC_G = 0.518f, ACC_B = 1.0f;

    private static final int FAN = GL11.GL_TRIANGLE_FAN;
    private static final int QUADS = GL11.GL_QUADS;

    /** 右上角功能入口（滑杆图标）几何，每帧由 drawMainMenuForeground 更新。 */
    private static float gearX = -1f, gearY = -1f, gearW = 26f, gearH = 26f;
    private static float gearHover;
    private static boolean gearVisible;
    /** 载入界面入场动画起点（窗口 / 界面变化时重置）。 */
    private static GuiScreen lastLoadingScreen;
    private static long loadingOpenTime;
    /**
     * 磨砂颗粒坐标缓存：格子只和界面尺寸有关，位置固定不闪烁。
     * 每帧重算几万次哈希没有意义，这里按“区域 + 格子大小”缓存顶点，尺寸变化时重建。
     */
    private static final Map<String, float[]> GRAIN_CACHE = new HashMap<String, float[]>();
    private static final int GRAIN_CACHE_LIMIT = 12;

    /** 帧级钩子的异常也只记一次，避免每帧刷屏但又不至于完全无迹可查。 */
    private static boolean mainMenuErrorLogged;
    private static boolean loadingErrorLogged;

    private SplashGlassHook() {
    }

    // ---------------------------------------------------------------- 主菜单

    /** GuiMainMenu#drawScreen 头部：开始新的一帧（只重置图标可见性，hover 值保留以保持平滑）。 */
    public static void beginMainMenuFrame() {
        gearVisible = false;
        // 开场动画一帧只画一次（drawScreen 可能有多个 RETURN）
        HelloIntro.beginFrame();
    }

    /** 主菜单入口图标绘制失败只记一次日志，绝不向上抛。 */
    private static void logMainMenuError(Throwable t) {
        if (mainMenuErrorLogged) return;
        mainMenuErrorLogged = true;
        try {
            DangoLogger.warn("Main menu entry hook failed: {}", t == null ? "unknown" : t.toString());
        } catch (Throwable ignored) {
            // 日志本身也不能影响渲染
        }
    }

    /** GuiMainMenu#drawScreen 末尾：只叠加右上角的滑杆入口图标，主菜单视觉保持原版。 */
    public static void drawMainMenuForeground(int mouseX, int mouseY) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            GuiScreen screen = mc.currentScreen;
            if (!(screen instanceof GuiMainMenu)) return;

            // 开场 Hello 动画：整屏接管主菜单，播完（或被跳过）之后才露出原版按钮
            HelloIntro.render();
            if (HelloIntro.isActive()) return;

            // 右上角固定位置：滑杆图标靠右；26x26、距顶/右 12
            gearW = 26f;
            gearH = 26f;
            gearX = screen.width - 12f - gearW;
            gearY = 12f;
            gearVisible = true;
            boolean gearHot = mouseX >= gearX && mouseX < gearX + gearW
                    && mouseY >= gearY && mouseY < gearY + gearH;
            gearHover += ((gearHot ? 1f : 0f) - gearHover) * 0.28f;
            drawGear(1f, gearHover);
        } catch (Throwable t) {
            logMainMenuError(t);
            restore();
        }
    }

    // ---------------------------------------------------------------- 载入界面（预加载动画）

    /** GuiDownloadTerrain#drawScreen 末尾：整屏玻璃底 + 带呼吸/流光动画的加载卡片。 */
    public static void drawLoadingForeground(int mouseX, int mouseY) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            GuiScreen screen = mc.currentScreen;
            if (screen == null) return;
            int width = screen.width;
            int height = screen.height;
            long now = Minecraft.getSystemTime();

            // 界面切换时重置入场时间：每次载入都从淡入开始，不会突然闪出一整块
            if (screen != lastLoadingScreen) {
                lastLoadingScreen = screen;
                loadingOpenTime = now;
                loadingStage = -1;
                stageTime = now;
            }
            long elapsed = now - loadingOpenTime;
            float enter = Math.min(1f, elapsed / 420f);
            // easeOutQuad：起步快、收尾稳，避免动画末尾像被“掐断”
            float ease = 1f - (1f - enter) * (1f - enter);

            drawBase(width, height);

            // 自适应卡片：窄窗口收边距，矮窗口压高度，任何分辨率都不溢出
            float cardW = Math.min(400f, width - 40f);
            float cardH = Math.min(140f, Math.max(112f, height * 0.24f));
            float cardX = (width - cardW) * 0.5f;
            float cardY = (height - cardH) * 0.5f - 10f + (1f - ease) * 16f;

            beginGlass();
            // 卡片后面的强调色呼吸光，随入场一起淡入
            float pulse = (0.10f + 0.05f * (float) Math.sin(now / 1400.0)) * ease;
            glow(width * 0.5f, cardY + cardH * 0.5f, Math.max(cardW, cardH) * 0.95f,
                    ACC_R, ACC_G, ACC_B, pulse);
            glassCard(cardX, cardY, cardX + cardW, cardY + cardH, 18f, ease, true);

            FontRenderer fr = mc.fontRendererObj;
            prepareText();
            String title = "DANGO MUSIC";
            centeredScaled(fr, title, width * 0.5f, cardY + cardH * 0.30f, 1.6f, alphaColor(0xFFFFFFFF, ease));
            centered(fr, "LIQUID GLASS", width * 0.5f, cardY + cardH * 0.48f,
                    alphaColor(0xFF7FC3EE, ease * 0.90f));

            // 分阶段状态：让等待过程始终有明确反馈，而不是一条空转的进度条
            int stage = elapsed < 5000L ? 0 : elapsed < 15000L ? 1 : elapsed < 30000L ? 2 : 3;
            if (stage != loadingStage) {
                loadingStage = stage;
                stageTime = now;
            }
            float stageFade = Math.min(1f, (now - stageTime) / 320f);
            String status = LOADING_STATUS[stage];
            if (elapsed >= 12000L) {
                status = status + "  " + (elapsed / 1000L) + " 秒";
            }
            centered(fr, status, width * 0.5f, cardY + cardH * 0.64f,
                    alphaColor(0xFFDCE6F0, ease * stageFade * 0.92f));

            // 不确定进度条：一段强调色光块匀速滑动，首尾都在视野外，不会跳帧
            float barW = cardW - 64f;
            float barX = cardX + 32f;
            float barY = cardY + cardH - 26f;
            beginGlass();
            roundRect(barX, barY, barX + barW, barY + 5f, 2.5f, 1f, 1f, 1f, 0.10f * ease);
            float cycle = (now % 1800L) / 1800f;
            float travel = barW + 90f;
            float segX = barX - 45f + cycle * travel;
            roundRect(segX, barY, Math.min(barX + barW, segX + 90f), barY + 5f, 2.5f,
                    ACC_R, ACC_G, ACC_B, 0.85f * ease);
            glow(Math.min(barX + barW, segX + 45f), barY + 2.5f, 26f, ACC_R, ACC_G, ACC_B, 0.22f * ease);
            // 三个呼吸点（继续在无贴图状态下画）
            for (int i = 0; i < 3; i++) {
                float t = (now % 1200L) / 1200f;
                float d = (t + i * 0.22f) % 1f;
                float a = (0.25f + 0.55f * (float) Math.sin(d * Math.PI)) * 0.8f * ease;
                float dx = width * 0.5f - 10f + i * 10f;
                drawDiamond(dx, cardY + cardH - 10f, 2.4f, 1f, 1f, 1f, a);
            }
            endGlass();
        } catch (Throwable t) {
            if (!loadingErrorLogged) {
                loadingErrorLogged = true;
                DangoLogger.warn("Loading screen hook failed: {}", t.toString());
            }
            restore();
        }
    }

    private static final String[] LOADING_STATUS = {
            "正在初始化客户端…", "正在载入资源…", "正在进入世界…", "网络较慢，仍在加载…",
    };
    private static int loadingStage = -1;
    private static long stageTime;

    // ---------------------------------------------------------------- 背景与卡片

    /** 整屏深色液态玻璃底：竖向渐变 + 三团柔光 + 细颗粒 + 缓慢流光。 */
    private static void drawBase(int width, int height) {
        long now = Minecraft.getSystemTime();
        beginGlass();
        // 不透明底：彻底替换原版全景图
        vGradient(0f, 0f, (float) width, (float) height,
                TOP_R, TOP_G, TOP_B, 1f, BOT_R, BOT_G, BOT_B, 1f);

        // 三团缓慢游动的强调色柔光（用不同周期，避免机械感）
        float x1 = width * 0.20f + (float) Math.sin(now / 5200.0) * width * 0.05f;
        float y1 = height * 0.18f + (float) Math.cos(now / 6100.0) * height * 0.04f;
        glow(x1, y1, Math.max(width, height) * 0.55f, ACC_R, ACC_G, ACC_B, 0.16f);

        float x2 = width * 0.84f + (float) Math.cos(now / 7000.0) * width * 0.04f;
        float y2 = height * 0.82f + (float) Math.sin(now / 8100.0) * height * 0.05f;
        glow(x2, y2, Math.max(width, height) * 0.5f, 0.28f, 0.22f, 0.85f, 0.10f);

        float x3 = width * 0.62f + (float) Math.sin(now / 9200.0) * width * 0.06f;
        float y3 = height * 0.10f;
        glow(x3, y3, Math.max(width, height) * 0.36f, 0.12f, 0.70f, 0.85f, 0.07f);

        // 缓慢斜掠的液态高光（真正的渐变光带，比硬边矩形的玻璃感更自然）
        sheen(width, height, now);

        // 整屏稀疏磨砂颗粒（固定网格，不闪烁）
        grain(0f, 0f, (float) width, (float) height, 13f, 0.020f);
        endGlass();
    }

    /** 斜向液态高光：两端透明、中段最亮，每 15 秒横掠一次，不产生任何硬边。 */
    private static void sheen(int width, int height, long now) {
        float p = (now % 15000L) / 15000f;
        float left = -300f + p * (width + 600f);
        float mid = left + 110f;
        float right = left + 220f;
        float topSkew = height * 0.12f;
        float a = 0.018f;
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(QUADS, DefaultVertexFormats.POSITION_COLOR);
        // 左段：透明 → 峰值
        wr.pos(left + topSkew, 0f, 0.0).color(1f, 1f, 1f, 0f).endVertex();
        wr.pos(left, (float) height, 0.0).color(1f, 1f, 1f, 0f).endVertex();
        wr.pos(mid, (float) height, 0.0).color(1f, 1f, 1f, a).endVertex();
        wr.pos(mid + topSkew, 0f, 0.0).color(1f, 1f, 1f, a).endVertex();
        // 右段：峰值 → 透明
        wr.pos(mid + topSkew, 0f, 0.0).color(1f, 1f, 1f, a).endVertex();
        wr.pos(mid, (float) height, 0.0).color(1f, 1f, 1f, a).endVertex();
        wr.pos(right, (float) height, 0.0).color(1f, 1f, 1f, 0f).endVertex();
        wr.pos(right + topSkew, 0f, 0.0).color(1f, 1f, 1f, 0f).endVertex();
        Tessellator.getInstance().draw();
    }

    /** 玻璃卡片：外柔光 + 白色 6% 玻璃面 + 顶部高光 + 底部内阴影 + 颗粒。 */
    private static void glassCard(float x0, float y0, float x1, float y1, float radius,
                                  float alpha, boolean withGrain) {
        beginGlass();
        // 外柔光（给一点“浮起来”的感觉）
        roundRect(x0 - 2f, y0 - 2f, x1 + 2f, y1 + 2f, radius + 2f, 1f, 1f, 1f, 0.05f * alpha);
        // 主体：白色低透明度（HUD 的 CARD 色）
        roundRect(x0, y0, x1, y1, radius, 1f, 1f, 1f, 0.055f * alpha);
        // 顶部高光带
        roundRect(x0 + 2f, y0 + 1.5f, x1 - 2f, y0 + Math.min(14f, (y1 - y0) * 0.26f), radius - 2f,
                1f, 1f, 1f, 0.055f * alpha);
        // 底部内阴影
        roundRect(x0 + 2f, y1 - 5f, x1 - 2f, y1 - 1f, radius - 2f, 0f, 0f, 0f, 0.10f * alpha);
        // 左右竖高光
        rect(x0 + 1f, y0 + radius, x0 + 1.8f, y1 - radius, 1f, 1f, 1f, 0.05f * alpha);
        rect(x1 - 1.8f, y0 + radius, x1 - 1f, y1 - radius, 1f, 1f, 1f, 0.03f * alpha);
        if (withGrain) {
            grain(x0 + 3f, y0 + 3f, x1 - 3f, y1 - 3f, 7f, 0.045f * alpha);
        }
        endGlass();
    }

    /** 主菜单点击：命中右上角任一入口图标时打开播放器设置页，并阻止原版继续处理。 */
    public static boolean onMainMenuClick(int mouseX, int mouseY, int button) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (!(mc.currentScreen instanceof GuiMainMenu)) return false;
            // 开场动画期间的任何点击都只用于跳过，不穿透到下面看不见的按钮
            if (HelloIntro.isActive()) {
                HelloIntro.skip();
                return true;
            }
            if (button != 0) return false;
            if (!gearVisible) return false;
            if (mouseX < gearX || mouseX >= gearX + gearW
                    || mouseY < gearY || mouseY >= gearY + gearH) return false;
            mc.displayGuiScreen(new LegacyMusicScreen((GuiScreen) null, true));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 右上角滑杆图标的入口：点击打开设置页。 */
    private static void drawGear(float alpha, float hover) {
        if (!gearVisible) return;
        float a = alpha * (0.72f + 0.28f * clamp(hover, 0f, 1f));
        beginGlass();
        // 深色玻璃底 + 悬停强调色描边
        roundRect(gearX, gearY, gearX + gearW, gearY + gearH, 8f, 0f, 0f, 0f, 0.35f * a);
        if (hover > 0.01f) {
            roundRect(gearX - 1f, gearY - 1f, gearX + gearW + 1f, gearY + gearH + 1f, 9f,
                    ACC_R, ACC_G, ACC_B, 0.70f * a * hover);
        } else {
            roundRect(gearX - 1f, gearY - 1f, gearX + gearW + 1f, gearY + gearH + 1f, 9f,
                    1f, 1f, 1f, 0.18f * a);
        }
        // 三条滑杆 + 滑块
        float lx = gearX + 6f;
        float rx = gearX + gearW - 6f;
        for (int i = 0; i < 3; i++) {
            float y = gearY + 8f + i * 4f;
            rect(lx, y, rx, y + 1f, 1f, 1f, 1f, 0.55f * a);
            float kx = lx + (rx - lx) * (i == 0 ? 0.35f : i == 1 ? 0.72f : 0.5f);
            roundRect(kx - 1.5f, y - 1.5f, kx + 1.5f, y + 2.5f, 1.5f, 1f, 1f, 1f, 0.92f * a);
        }
        endGlass();
    }

    // ---------------------------------------------------------------- 绘制工具

    /** 径向柔光（三角扇：中心有色，边缘全透明）。 */
    private static void glow(float cx, float cy, float radius, float r, float g, float bl, float alpha) {
        if (radius <= 0f || alpha <= 0f) return;
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(FAN, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(cx, cy, 0.0).color(r, g, bl, alpha).endVertex();
        int steps = 28;
        for (int i = 0; i <= steps; i++) {
            double angle = i * Math.PI * 2.0 / steps;
            wr.pos(cx + (float) Math.cos(angle) * radius, cy + (float) Math.sin(angle) * radius, 0.0)
                    .color(r, g, bl, 0f).endVertex();
        }
        Tessellator.getInstance().draw();
    }

    /** 竖向渐变矩形。 */
    private static void vGradient(float x0, float y0, float x1, float y1,
                                  float r0, float g0, float b0, float a0,
                                  float r1, float g1, float b1, float a1) {
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(QUADS, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(x0, y1, 0.0).color(r1, g1, b1, a1).endVertex();
        wr.pos(x1, y1, 0.0).color(r1, g1, b1, a1).endVertex();
        wr.pos(x1, y0, 0.0).color(r0, g0, b0, a0).endVertex();
        wr.pos(x0, y0, 0.0).color(r0, g0, b0, a0).endVertex();
        Tessellator.getInstance().draw();
    }

    /** 纯色矩形。 */
    private static void rect(float x0, float y0, float x1, float y1,
                             float r, float g, float b, float a) {
        if (x1 <= x0 || y1 <= y0 || a <= 0f) return;
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(QUADS, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(x0, y1, 0.0).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, 0.0).color(r, g, b, a).endVertex();
        wr.pos(x1, y0, 0.0).color(r, g, b, a).endVertex();
        wr.pos(x0, y0, 0.0).color(r, g, b, a).endVertex();
        Tessellator.getInstance().draw();
    }

    /** 圆角矩形（三角扇：中心 + 四条边 + 四段圆弧）。 */
    private static void roundRect(float x0, float y0, float x1, float y1, float radius,
                                  float r, float g, float b, float a) {
        if (a <= 0f) return;
        if (x1 - x0 < 0.5f || y1 - y0 < 0.5f) return;
        float rr = Math.min(radius, Math.min((x1 - x0) * 0.5f, (y1 - y0) * 0.5f));
        if (rr < 0.5f) {
            rect(x0, y0, x1, y1, r, g, b, a);
            return;
        }
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(FAN, DefaultVertexFormats.POSITION_COLOR);
        wr.pos((x0 + x1) * 0.5f, (y0 + y1) * 0.5f, 0.0).color(r, g, b, a).endVertex();

        int seg = 4;
        // 右上 → 右下 → 左下 → 左上（顺时针）
        corner(wr, x1 - rr, y0 + rr, rr, -90f, 0f, seg, r, g, b, a);
        corner(wr, x1 - rr, y1 - rr, rr, 0f, 90f, seg, r, g, b, a);
        corner(wr, x0 + rr, y1 - rr, rr, 90f, 180f, seg, r, g, b, a);
        corner(wr, x0 + rr, y0 + rr, rr, 180f, 270f, seg, r, g, b, a);
        // 收尾补第一个点，闭合扇形
        wr.pos(x1 - rr, y0, 0.0).color(r, g, b, a).endVertex();
        Tessellator.getInstance().draw();
    }

    private static void corner(WorldRenderer wr, float cx, float cy, float radius,
                               float startDeg, float endDeg, int seg,
                               float r, float g, float b, float a) {
        for (int i = 0; i <= seg; i++) {
            double angle = Math.toRadians(startDeg + (endDeg - startDeg) * i / seg);
            wr.pos(cx + (float) Math.cos(angle) * radius, cy + (float) Math.sin(angle) * radius, 0.0)
                    .color(r, g, b, a).endVertex();
        }
    }

    /** 固定网格磨砂颗粒：哈希决定格子是否画点，位置不随时间变化。 */
    private static void grain(float x0, float y0, float x1, float y1, float cell, float alpha) {
        if (cell < 2f || alpha <= 0f) return;
        float[] pts = grainPoints(x0, y0, x1, y1, cell);
        if (pts.length == 0) return;
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < pts.length; i += 2) {
            float gx = pts[i];
            float gy = pts[i + 1];
            wr.pos(gx, gy + 1f, 0.0).color(1f, 1f, 1f, alpha).endVertex();
            wr.pos(gx + 1f, gy + 1f, 0.0).color(1f, 1f, 1f, alpha).endVertex();
            wr.pos(gx + 1f, gy, 0.0).color(1f, 1f, 1f, alpha).endVertex();
            wr.pos(gx, gy, 0.0).color(1f, 1f, 1f, alpha).endVertex();
        }
        Tessellator.getInstance().draw();
    }

    /**
     * 缓存颗粒顶点：格子的取点只和区域尺寸 / 格子大小有关，逐帧重算几万次哈希纯属浪费。
     * 缓存按“区域 + 格子”字符串键，最多保留 12 组，尺寸变化（窗口拉伸）时自动重建。
     */
    private static float[] grainPoints(float x0, float y0, float x1, float y1, float cell) {
        // 位置按 16px 量化：入场时卡片上浮只会产生 1~2 个键，不会把缓存刷爆
        String key = (int) (x0 / 16f) + ":" + (int) (y0 / 16f) + ":" + (int) ((x1 - x0) / 16f)
                + ":" + (int) ((y1 - y0) / 16f) + ":" + (int) cell;
        float[] cached = GRAIN_CACHE.get(key);
        if (cached != null) return cached;

        int cols = (int) ((x1 - x0) / cell);
        int rows = (int) ((y1 - y0) / cell);
        float[] tmp = new float[Math.max(0, cols * rows) * 2];
        int n = 0;
        for (int iy = 0; iy < rows; iy++) {
            for (int ix = 0; ix < cols; ix++) {
                long seed = ((long) ix * 73856093L) ^ ((long) iy * 19349663L);
                if ((seed & 7L) != 0L) continue;                 // 只画 1/8 的格子
                float gx = x0 + 1f + ix * cell + (float) ((seed >> 5) & 3L);
                float gy = y0 + 1f + iy * cell + (float) ((seed >> 11) & 3L);
                if (gx + 1f >= x1 || gy + 1f >= y1) continue;
                tmp[n++] = gx;
                tmp[n++] = gy;
            }
        }
        float[] out = new float[n];
        System.arraycopy(tmp, 0, out, 0, n);
        if (GRAIN_CACHE.size() >= GRAIN_CACHE_LIMIT) GRAIN_CACHE.clear();
        GRAIN_CACHE.put(key, out);
        return out;
    }

    /** 小菱形（加载呼吸点）。 */
    private static void drawDiamond(float cx, float cy, float size,
                                    float r, float g, float b, float a) {
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(FAN, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(cx, cy + size, 0.0).color(r, g, b, a).endVertex();
        wr.pos(cx + size, cy, 0.0).color(r, g, b, a).endVertex();
        wr.pos(cx, cy - size, 0.0).color(r, g, b, a).endVertex();
        wr.pos(cx - size, cy, 0.0).color(r, g, b, a).endVertex();
        wr.pos(cx, cy + size, 0.0).color(r, g, b, a).endVertex();
        Tessellator.getInstance().draw();
    }

    private static void centered(FontRenderer fr, String text, float cx, float y, int color) {
        fr.drawString(text, (int) (cx - fr.getStringWidth(text) / 2f), (int) y, color);
    }

    /** 缩放后按视觉中线居中（标题文字高度 9px，直接用原点会偏上）。 */
    private static void centeredScaled(FontRenderer fr, String text, float cx, float cy,
                                       float scale, int color) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0f);
        GlStateManager.scale(scale, scale, 1f);
        fr.drawString(text, -fr.getStringWidth(text) / 2, -(fr.FONT_HEIGHT / 2), color);
        GlStateManager.popMatrix();
    }

    /** 把 ARGB 颜色乘上整体透明度，用于入场淡入。 */
    private static int alphaColor(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * clamp(alpha, 0f, 1f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void beginGlass() {
        GlStateManager.enableBlend();
        // 原版主菜单画 splash / 按钮时开着 Alpha 测试（阈值 0.1），我们的玻璃面 alpha 只有 0.05 会被整块丢弃
        GlStateManager.disableAlpha();
        GlStateManager.disableDepth();
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static void endGlass() {
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static void prepareText() {
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static void restore() {
        try {
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
