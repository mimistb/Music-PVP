package fpsport.pvp;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * 连击计数：统计连续命中次数与本次连击持续时间，HUD 实时显示。
 *
 * <p>只在攻击事件里计数，脱战（超过窗口）自动归零；受击是否清零由宿主配置决定。
 * 渲染在宿主 HUD 阶段调用 {@link #render(int, int)}。
 */
public class ComboCounter extends Feature {

    /** 宿主读取的开关标志。 */
    public static boolean using = false;
    /** 连击窗口（毫秒）。 */
    public static long windowMs = 2000L;
    /** 低于该连击数不显示。 */
    public static int minHits = 2;
    /** 显示位置：0 左上 / 1 中上 / 2 右下。 */
    public static int position = 0;
    /** 文字缩放。 */
    public static float scale = 1.0F;
    /** 受击是否清零连击。 */
    public static boolean resetOnHurt = true;

    private static final PvpLogic.ComboTracker TRACKER = new PvpLogic.ComboTracker(windowMs);

    /** 上一次计数的毫秒时间：同一次挥击可能同时派发攻击 / 伤害事件，45ms 内只算一次 */
    private static long lastHitMs;

    public ComboCounter() {
        super("ComboCounter");
    }

    @Override
    protected void onEnable() {
        using = true;
        TRACKER.reset();
    }

    @Override
    protected void onDisable() {
        using = false;
        TRACKER.reset();
    }

    /** 实际造成伤害时调用（宿主已确认为自己打出的伤害）。 */
    public static void onHit() {
        if (!using) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastHitMs < 45L) {
            return;
        }
        lastHitMs = now;
        TRACKER.setWindow(windowMs);
        TRACKER.hit(now);
    }

    /** 自己受击时调用；配置开启时清零。 */
    public static void onHurt() {
        if (using && resetOnHurt) {
            TRACKER.reset();
        }
    }

    /** 切世界 / 关闭模块时清空。 */
    public static void reset() {
        TRACKER.reset();
        lastHitMs = 0L;
    }

    /** HUD 阶段调用，宽高为 GUI 缩放后的像素。 */
    public static void render(int width, int height) {
        if (!using) {
            return;
        }
        // 配置可能在空闲时被改动：每帧同步一次窗口，断连 / 淡出时机立刻跟随新值
        TRACKER.setWindow(windowMs);
        long now = System.currentTimeMillis();
        if (!TRACKER.isActive(now) || TRACKER.count < minHits) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        String main = "Combo x" + TRACKER.count;
        String duration = String.format(Locale.ROOT, "%.2fs", TRACKER.durationMs(now) / 1000.0D);

        float s = Math.max(0.5F, scale);
        float w1 = mc.fontRendererObj.getStringWidth(main) * s;
        float w2 = mc.fontRendererObj.getStringWidth(duration) * s * 0.7F;
        float gap = 6.0F * s;
        float total = w1 + gap + w2;

        float x;
        float y;
        if (position == 1) {
            x = (width - total) * 0.5F;
            y = 6.0F;
        } else if (position == 2) {
            // 「右下」：右缘与左上角保持同样的 6px 边距
            x = width - total - 6.0F;
            y = height - 46.0F;
        } else {
            x = 6.0F;
            y = 6.0F;
        }

        long left = TRACKER.remainingMs(now);
        float fade = left < 300L ? Math.max(0.12F, left / 300.0F) : 1.0F;
        int alpha = (int) (fade * 255.0F) << 24;

        PvpHud.textScaled(main, x, y, s, alpha | 0xFFD24C, true);
        PvpHud.textScaled(duration, x + w1 + gap, y + 4.0F * s, s * 0.7F, alpha | 0xFFFFFF, true);
    }
}
