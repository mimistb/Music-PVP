package me.sorihiko.dango.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;

import me.sorihiko.dango.DangoLogger;
import me.sorihiko.dango.legacy.LegacyMusicScreen;
import me.sorihiko.dango.module.impls.music.MenuBackgroundModule;

import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 液态玻璃启动界面（整套，不叠在原版背景上）。
 *
 * <p>与宿主 HUD 同一套材质：深色近黑底 + 白色 5% 玻璃面 + #0A84FF 强调色 + 磨砂颗粒。
 * 主菜单在 drawScreen 结束时整屏重绘：先盖掉原版全景图 / logo / splash 文字 / 原版灰按钮，
 * 再画自己的标题玻璃窗和玻璃按钮；按钮位置和点击判定仍然用原版 GuiButton，只是视觉完全替换。
 *
 * <p>注入点（见 {@code SplashGlassTransformer}）：
 * <ul>
 *   <li>GuiMainMenu#drawScreen 头部：{@link #beginMainMenuFrame()}（清空本帧按钮记录）；</li>
 *   <li>GuiMainMenu#drawScreen 末尾：{@link #drawMainMenuForeground(int, int)}；</li>
 *   <li>GuiButton#drawButton 末尾：{@link #recordButton}（只记录位置与文字，不画东西）；</li>
 *   <li>GuiDownloadTerrain#drawScreen 末尾：{@link #drawLoadingForeground(int, int)}（带加载动画）。</li>
 * </ul>
 * 主菜单的绘制拆成背景 / 标题 / 按钮三段，任一段失败只记一次日志、其余段照画；
 * 本帧一条按钮都没记录到时，用反射读 GuiScreen.buttonList 兜底补一份快照，避免只剩深色底。
 * 只画东西，不改任何控件逻辑；任何异常都吞掉，保证游戏能正常启动。
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

    /** 本帧记录下来的主菜单按钮（由 GuiButton 钩子写入）。 */
    private static final List<Btn> BUTTONS = new ArrayList<Btn>(8);
    /** 按钮 hover 的平滑进度（逐个插值，避免鼠标扫过时亮度硬切）。 */
    private static final float[] BUTTON_HOVER = new float[24];
    /** 标题卡片右上角的功能入口（齿轮 / 滑杆图标）几何，每帧由 drawMainMenuForeground 更新。 */
    private static float gearX = -1f, gearY = -1f, gearW = 26f, gearH = 26f;
    private static float gearHover;
    private static boolean gearVisible;
    /** 标题窗入场动画起点。 */
    private static GuiScreen lastScreen;
    private static long openTime;
    /** 载入界面入场动画起点（窗口 / 界面变化时重置）。 */
    private static GuiScreen lastLoadingScreen;
    private static long loadingOpenTime;
    /**
     * 磨砂颗粒坐标缓存：格子只和界面尺寸有关，位置固定不闪烁。
     * 每帧重算几万次哈希没有意义，这里按“区域 + 格子大小”缓存顶点，尺寸变化时重建。
     */
    private static final Map<String, float[]> GRAIN_CACHE = new HashMap<String, float[]>();
    private static final int GRAIN_CACHE_LIMIT = 12;

    /** 三段绘制（背景 / 标题 / 按钮）各自只记一次失败日志，避免每帧刷屏。 */
    private static final boolean[] STAGE_LOGGED = new boolean[3];
    /** 帧级钩子的异常也只记一次，避免每帧刷屏但又不至于完全无迹可查。 */
    private static boolean beginErrorLogged;
    private static boolean loadingErrorLogged;
    private static boolean buttonErrorLogged;
    private static final String[] STAGE_NAMES = {"background", "title", "buttons"};
    private static final int STAGE_BACKGROUND = 0;
    private static final int STAGE_TITLE = 1;
    private static final int STAGE_BUTTONS = 2;

    private SplashGlassHook() {
    }

    // ---------------------------------------------------------------- 主菜单

    /** GuiMainMenu#drawScreen 头部：开始新的一帧。 */
    public static void beginMainMenuFrame() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            GuiScreen screen = mc.currentScreen;
            if (screen != lastScreen) {
                lastScreen = screen;
                openTime = Minecraft.getSystemTime();
                STAGE_LOGGED[STAGE_BACKGROUND] = false;
                STAGE_LOGGED[STAGE_TITLE] = false;
                STAGE_LOGGED[STAGE_BUTTONS] = false;
                java.util.Arrays.fill(BUTTON_HOVER, 0f);
            }
            BUTTONS.clear();
            gearVisible = false;
            gearHover = 0f;

            // 主界面背景图：路径变化时异步读盘，读好后在本帧上传纹理。
            // 模块关闭时传空路径，顺手释放已占用的纹理。
            MenuBackgroundModule bg = MenuBackgroundModule.of();
            if (bg != null) {
                MenuBackground.ensure(bg.getEnabled() ? bg.getPath().get() : "");
            }
            MenuBackground.tick();
        } catch (Throwable t) {
            if (!beginErrorLogged) {
                beginErrorLogged = true;
                DangoLogger.warn("Main menu frame hook failed: {}", t.toString());
            }
        }
    }

    /** GuiButton#drawButton 末尾：记录位置、文字、状态（不在原版按钮上叠加任何东西）。 */
    public static void recordButton(int x, int y, int w, int h, String label, boolean enabled,
                                    boolean visible, int mouseX, int mouseY) {
        try {
            if (!(Minecraft.getMinecraft().currentScreen instanceof GuiMainMenu)) return;
            if (!visible || w <= 0 || h <= 0) return;
            addButton(x, y, w, h, label == null ? "" : label, enabled);
        } catch (Throwable t) {
            if (!buttonErrorLogged) {
                buttonErrorLogged = true;
                DangoLogger.warn("Main menu button hook failed: {}", t.toString());
            }
        }
    }

    /**
     * 兜底：ASM 钩子没记录到按钮（例如 GuiButton#drawButton 补丁没打上）时，
     * 直接反射读 GuiScreen 的按钮列表（MCP 名 buttonList / srg 名 field_146292_n），
     * 把可见按钮的位置、文字与可用状态补进 BUTTONS，避免只剩深色底。
     */
    private static void collectButtonsFromScreen(GuiScreen screen) {
        try {
            final Object listValue = readField(screen, "buttonList", "field_146292_n");
            if (!(listValue instanceof List)) return;
            final List<?> list = (List<?>) listValue;
            for (Object entry : list) {
                if (!(entry instanceof GuiButton)) continue;
                final GuiButton button = (GuiButton) entry;
                if (!readBoolean(button, true, "visible", "field_146125_m")) continue;
                final int x = readInt(button, Integer.MIN_VALUE, "xPosition", "field_146128_h");
                final int y = readInt(button, Integer.MIN_VALUE, "yPosition", "field_146129_i");
                final int w = readInt(button, 0, "width", "field_146120_f");
                final int h = readInt(button, 0, "height", "field_146121_g");
                if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || w <= 0 || h <= 0) continue;
                final Object label = readField(button, "displayString", "field_146126_j");
                addButton(x, y, w, h, label instanceof String ? (String) label : "",
                        readBoolean(button, true, "enabled", "field_146124_l"));
            }
        } catch (Throwable t) {
            logStage(STAGE_BUTTONS, t);
        }
    }

    /** 记录一个按钮快照（两路来源共用去重与数量上限）。 */
    private static void addButton(int x, int y, int w, int h, String label, boolean enabled) {
        if (w <= 0 || h <= 0) return;
        if (BUTTONS.size() >= 10) return;
        for (Btn b : BUTTONS) {
            if (b.x == x && b.y == y && b.w == w && b.h == h && b.label.equals(label)) return;
        }
        BUTTONS.add(new Btn(x, y, w, h, label == null ? "" : label, enabled));
    }

    private static Object readField(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            try {
                Field field = findField(target.getClass(), name);
                if (field == null) continue;
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                // 换下一个候选名
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    private static int readInt(Object target, int fallback, String... names) {
        final Object value = readField(target, names);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static boolean readBoolean(Object target, boolean fallback, String... names) {
        final Object value = readField(target, names);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    /** 绘制失败只记一次日志，绝不向上抛。 */
    private static void logStage(int stage, Throwable t) {
        try {
            if (STAGE_LOGGED[stage]) return;
            STAGE_LOGGED[stage] = true;
            DangoLogger.warn("Splash glass {} draw failed: {}", STAGE_NAMES[stage],
                    t == null ? "unknown" : t.toString());
        } catch (Throwable ignored) {
            // 日志本身也不能影响渲染
        }
    }

    /** GuiMainMenu#drawScreen 末尾：整屏盖掉原版画面，画自己的背景、标题与按钮。 */
    public static void drawMainMenuForeground(int mouseX, int mouseY) {
        Minecraft mc;
        GuiScreen screen;
        try {
            mc = Minecraft.getMinecraft();
            if (!(mc.currentScreen instanceof GuiMainMenu)) return;
            screen = mc.currentScreen;
        } catch (Throwable t) {
            logStage(STAGE_BACKGROUND, t);
            restore();
            return;
        }
        final int width = screen.width;
        final int height = screen.height;
        final long now = Minecraft.getSystemTime();

        // 1) 整屏液态玻璃底：完全盖住原版全景图、logo、splash 文字与原版按钮
        try {
            drawBase(width, height);
        } catch (Throwable t) {
            logStage(STAGE_BACKGROUND, t);
            restore();
        }

        // 2) 标题玻璃窗（带轻微上浮与缩放入场）；失败只影响标题，按钮照画
        float alpha = 1f;
        float ease = 0f;
        try {
            float appear = clamp((now - openTime) / 420f, 0f, 1f);
            ease = 1f - (1f - appear) * (1f - appear);
            float subEase = 1f - (1f - clamp((now - openTime - 140f) / 380f, 0f, 1f))
                    * (1f - clamp((now - openTime - 140f) / 380f, 0f, 1f));
            float cardW = Math.min(430f, width - 36f);
            float cardH = Math.min(112f, Math.max(54f, height * 0.185f));
            float cardX = (width - cardW) * 0.5f;
            float cardY = 10f + (1f - ease) * 8f;
            float titleScale = height < 300 ? 1.5f : 1.9f;

            // 首帧也保留 45% 不透明度，避免整块标题窗在入场时“看不见”
            alpha = 0.45f + 0.55f * ease;
            glassCard(cardX, cardY, cardX + cardW, cardY + cardH, 18f, alpha, true);

            // 右上角功能入口：与按钮同一套玻璃语言，点击打开播放器设置页
            gearX = cardX + cardW - 38f;
            gearY = cardY + 12f;
            gearW = 26f;
            gearH = 26f;
            gearVisible = true;
            boolean gearHot = mouseX >= gearX && mouseX < gearX + gearW
                    && mouseY >= gearY && mouseY < gearY + gearH;
            gearHover += ((gearHot ? 1f : 0f) - gearHover) * 0.28f;
            drawGear(alpha, gearHover);

            FontRenderer fr = mc.fontRendererObj;
            prepareText();
            // 标题从 0.94 缓到 1.0：悬浮感入场，而不是硬邦邦地出现
            float pop = 0.94f + 0.06f * ease;
            GlStateManager.pushMatrix();
            GlStateManager.translate(width * 0.5f, cardY + cardH * 0.32f - titleScale * 4f, 0f);
            GlStateManager.scale(titleScale * pop, titleScale * pop, 1f);
            String title = "DANGO MUSIC";
            fr.drawString(title, -fr.getStringWidth(title) / 2, 0, alphaColor(0xFFFFFFFF, ease));
            GlStateManager.popMatrix();

            if (cardH >= 60f) {
                centered(fr, "L I Q U I D   G L A S S", width * 0.5f, cardY + cardH * 0.72f,
                        alphaColor(0xFF6FB4E8, subEase * 0.95f));
                // 副标题下的强调色细线从中间向两边展开
                float lineW = 26f * subEase;
                beginGlass();
                rect(width * 0.5f - lineW, cardY + cardH * 0.86f, width * 0.5f + lineW,
                        cardY + cardH * 0.86f + 1f, ACC_R, ACC_G, ACC_B, 0.55f * subEase);
                endGlass();
            }
        } catch (Throwable t) {
            logStage(STAGE_TITLE, t);
            restore();
        }

        // 3) 玻璃按钮（位置来自原版 / 反射兜底，视觉完全自绘）
        try {
            if (BUTTONS.isEmpty()) collectButtonsFromScreen(screen);
            FontRenderer fr = mc.fontRendererObj;

            // 主按钮外再套一层玻璃底板：和播放器界面一样，按钮组属于同一块玻璃面板
            drawButtonBackdrop(alpha, ease);

            for (int i = 0; i < BUTTONS.size(); i++) {
                Btn b = BUTTONS.get(i);
                boolean hover = b.enabled && mouseX >= b.x && mouseX < b.x + b.w
                        && mouseY >= b.y && mouseY < b.y + b.h;
                int slot = Math.min(i, BUTTON_HOVER.length - 1);
                float target = hover ? 1f : 0f;
                // 每帧向目标收敛 22%，鼠标快速扫过时也是渐变而不是闪一下
                BUTTON_HOVER[slot] += (target - BUTTON_HOVER[slot]) * 0.22f;
                // 按钮逐个错峰淡入（每项 55ms），形成自然的瀑布节奏
                float in = clamp((now - openTime - 150f - i * 55f) / 280f, 0f, 1f);
                float inEase = 1f - (1f - in) * (1f - in);
                drawGlassButton(fr, b, BUTTON_HOVER[slot], alpha * inEase);
            }
        } catch (Throwable t) {
            logStage(STAGE_BUTTONS, t);
            restore();
        }
    }

    /** 只围住主按钮（跳过左下角语言等小图标），画一块和播放器卡片同材质的玻璃底板。 */
    private static void drawButtonBackdrop(float alpha, float ease) {
        float x0 = Float.MAX_VALUE;
        float y0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE;
        float y1 = -Float.MAX_VALUE;
        int count = 0;
        for (Btn b : BUTTONS) {
            if (b.w < 120) continue;
            x0 = Math.min(x0, (float) b.x);
            y0 = Math.min(y0, (float) b.y);
            x1 = Math.max(x1, (float) (b.x + b.w));
            y1 = Math.max(y1, (float) (b.y + b.h));
            count++;
        }
        if (count == 0) return;
        x0 -= 16f;
        y0 -= 14f;
        x1 += 16f;
        y1 += 14f;
        glassCard(x0, y0, x1, y1, 24f, alpha * ease * 0.92f, true);
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

        // 自定义背景图叠在渐变底之上；关闭或路径为空时保持原样
        MenuBackgroundModule bg = MenuBackgroundModule.of();
        if (bg != null && bg.getEnabled() && MenuBackground.ready()) {
            MenuBackground.draw(width, height, bg.getFillMode().getValue(),
                    bg.getOpacity().getValue() / 100f, bg.getDim().getValue() / 100f);
        }
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

    /** 主菜单点击：命中右上角功能入口时打开播放器设置页，并阻止原版继续处理。 */
    public static boolean onMainMenuClick(int mouseX, int mouseY, int button) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (!(mc.currentScreen instanceof GuiMainMenu)) return false;
            if (button != 0 || !gearVisible) return false;
            if (mouseX < gearX || mouseX >= gearX + gearW
                    || mouseY < gearY || mouseY >= gearY + gearH) return false;
            mc.displayGuiScreen(new LegacyMusicScreen((GuiScreen) null, true));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 标题卡片右上角功能入口：三条滑杆组成的小图标，和玻璃按钮同一套材质。 */
    private static void drawGear(float alpha, float hover) {
        if (!gearVisible) return;
        float a = alpha * (0.72f + 0.28f * clamp(hover, 0f, 1f));
        beginGlass();
        // 玻璃底 + 悬停强调色描边
        roundRect(gearX, gearY, gearX + gearW, gearY + gearH, 8f, 1f, 1f, 1f, 0.055f * a);
        if (hover > 0.01f) {
            roundRect(gearX - 1f, gearY - 1f, gearX + gearW + 1f, gearY + gearH + 1f, 9f,
                    ACC_R, ACC_G, ACC_B, 0.35f * a * hover);
        } else {
            roundRect(gearX - 1f, gearY - 1f, gearX + gearW + 1f, gearY + gearH + 1f, 9f,
                    1f, 1f, 1f, 0.07f * a);
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

    /** 单个玻璃按钮（画在自绘背景之上）。hover 为 0~1 的平滑值。 */
    private static void drawGlassButton(FontRenderer fr, Btn b, float hover, float alpha) {
        float a = alpha * (b.enabled ? 1f : 0.72f);
        float h = clamp(hover, 0f, 1f);
        float r = b.h / 2f;
        beginGlass();
        // 落影
        roundRect(b.x + 1f, b.y + 1.5f, b.x + b.w + 1f, b.y + b.h + 1.5f, r, 0f, 0f, 0f, 0.14f * a);
        if (h > 0.01f) {
            // 强调色外发光
            roundRect(b.x - 2f, b.y - 2f, b.x + b.w + 2f, b.y + b.h + 2f, r + 2f,
                    ACC_R, ACC_G, ACC_B, 0.20f * a * h);
        }
        // 1px 描边：静止是白色细线，悬停时逐渐换成强调色，和 GUI 卡片同一套语言
        if (h > 0.01f) {
            roundRect(b.x - 1f, b.y - 1f, b.x + b.w + 1f, b.y + b.h + 1f, r + 1f,
                    ACC_R, ACC_G, ACC_B, 0.50f * a * h);
        } else {
            roundRect(b.x - 1f, b.y - 1f, b.x + b.w + 1f, b.y + b.h + 1f, r + 1f,
                    1f, 1f, 1f, 0.07f * a);
        }
        // 玻璃面
        roundRect(b.x, b.y, b.x + b.w, b.y + b.h, r, 1f, 1f, 1f, (0.055f + 0.045f * h) * a);
        // 顶部高光（胶囊形，左右留出圆角，不会顶到边缘）
        roundRect(b.x + Math.min(14f, b.w * 0.2f), b.y + 1f,
                b.x + b.w - Math.min(14f, b.w * 0.2f), b.y + Math.min(6f, b.h * 0.32f),
                Math.max(2f, r - 3f), 1f, 1f, 1f, (0.045f + 0.055f * h) * a);
        // 底部内阴影
        rect(b.x + Math.min(14f, b.w * 0.2f), b.y + b.h - 1.5f,
                b.x + b.w - Math.min(14f, b.w * 0.2f), b.y + b.h - 0.5f, 0f, 0f, 0f, 0.10f * a);
        endGlass();

        prepareText();
        int tw = fr.getStringWidth(b.label);
        int color = !b.enabled ? 0x66FFFFFF : mix(0xF0FFFFFF, 0xFFFFFFFF, h);
        fr.drawString(b.label, (b.x + b.w / 2) - tw / 2,
                (b.y + b.h / 2) - 4, alphaColor(color, a));
    }

    /** 两个 ARGB 颜色按 t 混合（逐通道插值，hover 文字亮度也能平滑过渡）。 */
    private static int mix(int from, int to, float t) {
        float k = clamp(t, 0f, 1f);
        int a = (int) ((from >>> 24) + (((to >>> 24) - (from >>> 24)) * k));
        int r = (int) (((from >> 16) & 0xFF) + ((((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k));
        int g = (int) (((from >> 8) & 0xFF) + ((((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k));
        int bl = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
        return (a << 24) | (r << 16) | (g << 8) | bl;
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

    /** 一帧里记录下来的原版按钮。 */
    private static final class Btn {
        final int x;
        final int y;
        final int w;
        final int h;
        final String label;
        final boolean enabled;

        Btn(int x, int y, int w, int h, String label, boolean enabled) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.label = label;
            this.enabled = enabled;
        }
    }
}
