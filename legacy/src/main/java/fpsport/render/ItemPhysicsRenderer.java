package fpsport.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Method;
import java.util.Random;

/**
 * 物品物理的渲染体（{@link ItemPhysics} 的实现）。
 *
 * <p>由 {@code ItemPhysicsTransformer} 注入到 {@code RenderEntityItem#doRender} 的 HEAD：
 * 返回值 true 表示已接管绘制，宿主 Transformer 会让原方法直接返回。
 *
 * <p>数量取自原版私有的 {@code func_177078_a(ItemStack)I}（按描述符反射查找，不依赖方法名）。
 */
public final class ItemPhysicsRenderer {

    private static final Random RANDOM = new Random();
    private static Method countMethod;
    private static boolean countResolved;

    private ItemPhysicsRenderer() {
    }

    /**
     * @return true 表示已经画完并接管，原版 {@code doRender} 应直接返回；false 则原版继续。
     */
    public static boolean render(RenderEntityItem renderer, EntityItem entity,
                                 double x, double y, double z,
                                 float entityYaw, float partialTicks) {
        if (!ItemPhysics.using || renderer == null || entity == null) {
            return false;
        }
        ItemStack stack = entity.getEntityItem();
        if (stack == null || stack.getItem() == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getRenderItem() == null) {
            return false;
        }

        int seed = Item.getIdFromItem(stack.getItem()) + stack.getMetadata();
        RANDOM.setSeed(seed);

        mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        mc.getTextureManager().getTexture(TextureMap.locationBlocksTexture).setBlurMipmap(false, false);

        GlStateManager.enableRescaleNormal();
        GlStateManager.alphaFunc(516, 0.1F);
        GlStateManager.enableBlend();
        RenderHelper.enableStandardItemLighting();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.pushMatrix();

        IBakedModel model = mc.getRenderItem().getItemModelMesher().getItemModel(stack);
        boolean is3D = model.isGui3d();
        int count = ItemPhysics.respectCount ? count(renderer, stack) : 1;

        GlStateManager.translate((float) x, (float) y, (float) z);
        if (is3D) {
            float s = ItemPhysics.scale;
            GlStateManager.scale(s, s, s);
        }

        GL11.glRotatef(ItemPhysics.rotateAngle, 1.0F, 0.0F, 0.0F);
        GL11.glRotatef(entity.rotationYaw, 0.0F, 0.0F, 1.0F);
        GlStateManager.translate(0.0D, 0.0D, is3D ? -0.08D : -0.04D);

        if (is3D) {
            if (!entity.onGround) {
                // 空中缓慢翻滚，落地后停在最后一次角度。
                entity.rotationPitch += ItemPhysics.rollSpeed;
            }
        } else if (!Double.isNaN(entity.posX) && !Double.isNaN(entity.posY) && !Double.isNaN(entity.posZ)
                && entity.worldObj != null) {
            if (entity.onGround) {
                entity.rotationPitch = 0.0F;
            } else {
                entity.rotationPitch += ItemPhysics.rollSpeed;
            }
        }
        GlStateManager.rotate(entity.rotationPitch, 1.0F, 0.0F, 0.0F);

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        for (int i = 0; i < count; ++i) {
            GlStateManager.pushMatrix();
            if (is3D) {
                if (i > 0) {
                    float d = ItemPhysics.spread;
                    float dx = (RANDOM.nextFloat() * 2.0F - 1.0F) * d;
                    float dy = (RANDOM.nextFloat() * 2.0F - 1.0F) * d;
                    float dz = (RANDOM.nextFloat() * 2.0F - 1.0F) * d;
                    GlStateManager.translate(dx, dy, dz);
                }
                mc.getRenderItem().renderItem(stack, model);
                GlStateManager.popMatrix();
            } else {
                mc.getRenderItem().renderItem(stack, model);
                GlStateManager.popMatrix();
                GlStateManager.translate(0.0F, 0.0F, ItemPhysics.stackGap);
            }
        }

        GlStateManager.popMatrix();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        RenderHelper.disableStandardItemLighting();
        mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        mc.getTextureManager().getTexture(TextureMap.locationBlocksTexture).restoreLastBlurMipmap();
        return true;
    }

    /** 原版私有数量方法：栈中数量 >48 → 5、>32 → 4、>16 → 3、>1 → 2，否则 1。 */
    private static int count(RenderEntityItem renderer, ItemStack stack) {
        Method method = resolveCount();
        if (method == null) {
            return 1;
        }
        try {
            return (Integer) method.invoke(renderer, stack);
        } catch (Throwable t) {
            return 1;
        }
    }

    private static Method resolveCount() {
        if (countResolved) {
            return countMethod;
        }
        countResolved = true;
        try {
            for (Method method : RenderEntityItem.class.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && params[0] == ItemStack.class && method.getReturnType() == int.class) {
                    method.setAccessible(true);
                    countMethod = method;
                    break;
                }
            }
        } catch (Throwable ignored) {
            countMethod = null;
        }
        return countMethod;
    }
}
