package fpsport.render;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

import java.awt.Color;
import java.text.DecimalFormat;
import java.util.ArrayList;

/**
 * 伤害指示器：世界空间里向上飘并淡出的伤害数字。
 *
 * <p>这不是 HUD：数字贴在受击点上方，随相机旋转面向玩家。宿主在世界渲染阶段调用
 * {@link #onRender3D()} 即可；不需要 Mixin，也不需要任何 UI 组件。
 */
public class DamageIndicator extends Feature {

    private static final int MAX_INDICATORS = 64;
    private static final DecimalFormat DAMAGE_FORMAT = new DecimalFormat("0.00");

    private static final ArrayList<Damage> INDICATORS = new ArrayList<>();

    private static EntityLivingBase lastAttack;
    private static float health = 0;
    /** 数字大小（设置项，0.03 ~ 0.15）。 */
    public static float scale = 0.065f;

    private long lastStepMillis = System.currentTimeMillis();

    public DamageIndicator() {
        super("DamageIndicator");
    }

    @Override
    protected void onDisable() {
        clearState();
    }

    /** 上限保护：模块中途被关时监听器停止、淘汰也停止，避免列表无限增长。 */
    public static void addIndicator(float x, float y, float z, float damage) {
        while (INDICATORS.size() >= MAX_INDICATORS) {
            INDICATORS.remove(0);
        }
        INDICATORS.add(new Damage(damage, x, y, z, 0f));
    }

    /** 切世界 / 关闭模块时清空残留。 */
    public static void clearState() {
        lastAttack = null;
        INDICATORS.clear();
    }

    /** 宿主在攻击命中时调用（对应源码的 EventAttack）。 */
    public void onAttack(Entity attacked) {
        if (attacked instanceof EntityLivingBase) {
            lastAttack = (EntityLivingBase) attacked;
            health = lastAttack.getHealth();
        }
    }

    /** 宿主每帧调用一次（对应源码的 EventUpdate）。 */
    public void onUpdate() {
        if (lastAttack == null) {
            return;
        }
        if (health - lastAttack.getHealth() != 0) {
            addIndicator((float) lastAttack.posX, (float) (lastAttack.posY + 0.2), (float) lastAttack.posZ,
                    health - lastAttack.getHealth());
            health = lastAttack.getHealth();
        }
    }

    /** 宿主在世界渲染阶段调用（对应源码的 EventRender3D）。 */
    public void onRender3D() {
        ArrayList<Damage> remove = new ArrayList<>();
        for (Damage indicator : INDICATORS) {
            doRender(indicator);
        }
        long now = System.currentTimeMillis();
        if (now - lastStepMillis > 20) {
            lastStepMillis = now;
            for (Damage indicator : INDICATORS) {
                indicator.animation += 0.05f;
                if (indicator.animation > 1) {
                    remove.add(indicator);
                }
            }
            if (!remove.isEmpty()) {
                INDICATORS.removeAll(remove);
            }
        }
    }

    private static void doRender(Damage indicator) {
        Minecraft mc = Minecraft.getMinecraft();
        String damage = DAMAGE_FORMAT.format(-indicator.damage);

        double x = indicator.x - mc.getRenderManager().viewerPosX;
        double y = indicator.y - mc.getRenderManager().viewerPosY;
        double z = indicator.z - mc.getRenderManager().viewerPosZ;

        float scale = DamageIndicator.scale;

        int alpha = (int) (255 - indicator.animation * 255);
        alpha = Math.max(0, Math.min(255, alpha));

        Color color = new Color(50, 255, 50, alpha);
        if (indicator.damage > 0) {
            color = new Color(224, 41, 41, alpha);
        }

        // 全程关闭深度测试，保证数字始终画在实体上方可见；结束时统一恢复
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.translate(x, y + 0.5, z);
        // Billboard：先绕 Y 转，再绕 X 转，正面对准玩家
        GlStateManager.rotate(-mc.getRenderManager().playerViewY, 0.0f, 1.0f, 0.0f);
        GlStateManager.rotate(mc.getRenderManager().playerViewX, 1.0f, 0.0f, 0.0f);
        GlStateManager.scale(-scale, -scale, -scale);

        GlStateManager.enableBlend();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();

        float width = mc.fontRendererObj.getStringWidth(damage) / 2.0f;
        mc.fontRendererObj.drawStringWithShadow(damage, -width, indicator.animation * 10, color.getRGB());

        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    private static final class Damage {
        final float damage;
        final float x;
        final float y;
        final float z;
        float animation;

        Damage(float damage, float x, float y, float z, float animation) {
            this.damage = damage;
            this.x = x;
            this.y = y;
            this.z = z;
            this.animation = animation;
        }
    }
}
