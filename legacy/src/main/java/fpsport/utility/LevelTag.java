package fpsport.utility;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

/**
 * 名字标签（世界内 nametag 渲染）。
 *
 * <p>替换原版 {@code Render.renderLivingLabel}：可选纯色背景、可开关阴影、可选隐藏 NPC。
 *
 * <p>与原版的差异有两处，都是为了「不带 UI 资源」：
 * <ul>
 *   <li>不再使用客户端自带字体 {@code FPSMaster.fontManager.s18}，统一走 {@code mc.fontRendererObj}；</li>
 *   <li>不再绘制 {@code client/textures/mate.png} 图标（那是 UI 资源），
 *       因此原来为图标预留的横向偏移也一并去掉。</li>
 * </ul>
 */
public class LevelTag extends Feature {

    public static boolean showSelf = true;
    /** 保留字段供宿主绑定；本移植版恒用原版字体。 */
    public static boolean betterFont = false;
    public static boolean fontShadow = true;
    public static boolean bg = true;
    public static Color backgroundColor = new Color(0, 0, 0, 0);
    public static boolean hideNPC = true;

    public static boolean using = false;

    public LevelTag() {
        super("LevelTag");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
    }

    /** 由 {@code MixinRender.renderLivingLabel} 在 HEAD 处调用。 */
    public static void renderName(Entity entityIn, String str, double x, double y, double z, int maxDistance) {
        Minecraft mc = Minecraft.getMinecraft();
        if ((!using || !showSelf) && entityIn == mc.thePlayer) {
            return;
        }

        Entity view = mc.getRenderViewEntity();
        if (view == null) {
            return;
        }
        double d = entityIn.getDistanceSqToEntity(view);
        if (d > (double) (maxDistance * maxDistance)) {
            return;
        }

        FontRenderer fontRenderer = mc.fontRendererObj;
        float f = 1.6F;
        float g = 0.016666668F * f;

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x + 0.0F, (float) y + entityIn.height + 0.5F, (float) z);
        GL11.glNormal3f(0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-mc.getRenderManager().playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(mc.getRenderManager().playerViewX, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(-g, -g, g);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();

        int i = 0;
        if ("deadmau5".equals(str)) {
            i = -10;
        }

        int textWidth = fontRenderer.getStringWidth(str);
        int textX = -textWidth / 2;
        float backgroundLeft = textX - 1f;
        float backgroundRight = textX + textWidth + 1f;

        if (bg) {
            GlStateManager.disableTexture2D();
            float red = backgroundColor.getRed() / 255f;
            float green = backgroundColor.getGreen() / 255f;
            float blue = backgroundColor.getBlue() / 255f;
            float alpha = backgroundColor.getAlpha() / 255f;

            worldRenderer.begin(7, DefaultVertexFormats.POSITION_COLOR);
            worldRenderer.pos(backgroundLeft, -1 + i, 0.0F).color(red, green, blue, alpha).endVertex();
            worldRenderer.pos(backgroundLeft, 8 + i, 0.0F).color(red, green, blue, alpha).endVertex();
            worldRenderer.pos(backgroundRight, 8 + i, 0.0F).color(red, green, blue, alpha).endVertex();
            worldRenderer.pos(backgroundRight, -1 + i, 0.0F).color(red, green, blue, alpha).endVertex();
            tessellator.draw();
        }

        GL11.glColor4f(1, 1, 1, 1);
        GlStateManager.enableTexture2D();

        // 第一遍：关深度画暗色底字。
        fontRenderer.drawString(str, textX, i, 553648127);

        // 第二遍：开深度画正常白字。
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        if (fontShadow) {
            fontRenderer.drawStringWithShadow(str, textX, i, -1);
        } else {
            fontRenderer.drawString(str, textX, i, -1);
        }

        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    /** 隐藏 NPC（没有对应玩家信息的实体）。 */
    public static boolean qualify(Entity entityIn) {
        if (!hideNPC) {
            return true;
        }
        if (!(entityIn instanceof EntityPlayer)) {
            return true;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getNetHandler() == null) {
            return true;
        }
        EntityPlayer player = (EntityPlayer) entityIn;
        return mc.getNetHandler().getPlayerInfo(player.getUniqueID()) != null;
    }
}
