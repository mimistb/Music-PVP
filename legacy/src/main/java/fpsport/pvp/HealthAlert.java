package fpsport.pvp;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

/**
 * 低血量警报：血量低于阈值时在屏幕四周画红色脉动边框。
 *
 * <p>边框由三层递减透明度的矩形组成，越靠近边缘越亮；脉动频率与血量比例共同决定强度，
 * 血量越接近 0 越急促醒目。
 */
public class HealthAlert extends Feature {

    /** 宿主读取的开关标志。 */
    public static boolean using = false;
    /** 触发阈值（血量点，1 ~ 20）。 */
    public static float threshold = 6.0F;
    /** 基础边框粗细（GUI 像素）。 */
    public static int border = 12;
    /** 脉动速度（Hz 左右）。 */
    public static float pulseSpeed = 1.5F;
    /** 边框颜色（ARGB，alpha 会被脉动覆盖）。 */
    public static int color = 0xFFFF3030;

    public HealthAlert() {
        super("HealthAlert");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
    }

    /** HUD 阶段调用。 */
    public static void render(int width, int height) {
        if (!using) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (player == null || width <= 0 || height <= 0) {
            return;
        }
        float max = Math.max(0.5F, threshold);
        float hp = Math.max(0.0F, player.getHealth());
        if (hp > max) {
            return;
        }

        float ratio = PvpLogic.clamp01(hp / max);
        long now = System.currentTimeMillis();
        float pulse = PvpLogic.pulse(now, pulseSpeed);
        float base = 0.26F + 0.38F * pulse + 0.22F * (1.0F - ratio);
        base = Math.min(1.0F, base);

        int thickness = Math.max(2, border);
        int band = Math.max(1, thickness / 3);
        int rgb = color & 0xFFFFFF;

        for (int i = 0; i < 3; i++) {
            int inset = i * band;
            float alpha = base * (1.0F - i * 0.38F);
            if (alpha <= 0.01F) {
                continue;
            }
            int argb = ((int) (alpha * 255.0F) << 24) | rgb;
            int left = inset;
            int top = inset;
            int right = width - inset;
            int bottom = height - inset;

            PvpHud.rect(left, top, right, top + band, argb);
            PvpHud.rect(left, bottom - band, right, bottom, argb);
            PvpHud.rect(left, top + band, left + band, bottom - band, argb);
            PvpHud.rect(right - band, top + band, right, bottom - band, argb);
        }
    }
}
