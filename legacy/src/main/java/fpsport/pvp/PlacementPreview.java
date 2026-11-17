package fpsport.pvp;

import fpsport.core.Feature;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

/**
 * 方块放置辅助：在真正放下之前预览目标位置，用绿色 / 红色表示能否放置。
 *
 * <p>检测内容：目标格是否可替换、方块自身 {@code canPlaceBlockAt}、目标碰撞盒内是否有实体、
 * 以及从眼睛到目标中心的距离是否在 4.5 格内。任何一项失败就画红色。
 *
 * <p>目标格规则与原版 {@code ItemBlock#onItemUse} 一致：命中格可替换就原地替换，
 * 否则沿命中面偏移一格。
 */
public class PlacementPreview extends Feature {

    /** 宿主读取的开关标志。 */
    public static boolean using = false;
    /** 填充透明度，0 ~ 100（百分比）。 */
    public static int fillAlpha = 35;

    private static final double REACH = 4.5D;
    private static final int VALID_RGB = 0x55FF55;
    private static final int INVALID_RGB = 0xFF5555;

    public PlacementPreview() {
        super("PlacementPreview");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
    }

    /** 3D 渲染阶段（画手之前）调用。 */
    public static void onRender3D() {
        if (!using) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }
        BlockPos hitPos = hit.getBlockPos();
        if (hitPos == null) {
            return;
        }
        ItemStack stack = mc.thePlayer.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
            return;
        }
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        if (block == null) {
            return;
        }

        IBlockState hitState = mc.theWorld.getBlockState(hitPos);
        BlockPos target = hitPos;
        if (!hitState.getBlock().isReplaceable(mc.theWorld, hitPos)) {
            if (hit.sideHit == null) {
                return;
            }
            target = hitPos.offset(hit.sideHit);
        }

        IBlockState existing = mc.theWorld.getBlockState(target);
        boolean replaceable = existing.getBlock().isReplaceable(mc.theWorld, target)
                || mc.theWorld.isAirBlock(target);
        boolean canPlace = block.canPlaceBlockAt(mc.theWorld, target);

        IBlockState placeState = block.getStateFromMeta(stack.getMetadata());
        AxisAlignedBB box = block.getCollisionBoundingBox(mc.theWorld, target, placeState);
        if (box == null) {
            box = new AxisAlignedBB(
                    target.getX() + block.getBlockBoundsMinX(),
                    target.getY() + block.getBlockBoundsMinY(),
                    target.getZ() + block.getBlockBoundsMinZ(),
                    target.getX() + block.getBlockBoundsMaxX(),
                    target.getY() + block.getBlockBoundsMaxY(),
                    target.getZ() + block.getBlockBoundsMaxZ());
        }
        boolean entityBlocked = !mc.theWorld.checkNoEntityCollision(box);
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        // 用眼睛到包围盒最近点的距离：贴边放置时中心距离会超过 4.5，但原版仍然允许
        double distance = PvpLogic.distanceToBox(
                eye.xCoord, eye.yCoord, eye.zCoord,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        boolean valid = PvpLogic.placementValid(replaceable, canPlace, entityBlocked, distance, REACH);

        drawBox(mc, box, valid);
    }

    private static void drawBox(Minecraft mc, AxisAlignedBB worldBox, boolean valid) {
        double viewerX = mc.getRenderManager().viewerPosX;
        double viewerY = mc.getRenderManager().viewerPosY;
        double viewerZ = mc.getRenderManager().viewerPosZ;
        double x0 = worldBox.minX - viewerX;
        double y0 = worldBox.minY - viewerY;
        double z0 = worldBox.minZ - viewerZ;
        double x1 = worldBox.maxX - viewerX;
        double y1 = worldBox.maxY - viewerY;
        double z1 = worldBox.maxZ - viewerZ;
        if (x1 - x0 < 1.0E-4D || y1 - y0 < 1.0E-4D || z1 - z0 < 1.0E-4D) {
            return;
        }

        int rgb = valid ? VALID_RGB : INVALID_RGB;
        int alpha = (int) (PvpLogic.clamp01(fillAlpha / 100.0F) * 255.0F);
        int fill = (alpha << 24) | rgb;
        int outline = 0xFF000000 | rgb;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableTexture2D();
        GlStateManager.depthMask(false);
        try {
            if (alpha > 0) {
                setColor(fill);
                drawFaces(x0 - 0.002D, y0 - 0.002D, z0 - 0.002D, x1 + 0.002D, y1 + 0.002D, z1 + 0.002D);
            }
            setColor(outline);
            GL11.glLineWidth(2.0F);
            drawOutline(x0 - 0.001D, y0 - 0.001D, z0 - 0.001D, x1 + 0.001D, y1 + 0.001D, z1 + 0.001D);
        } finally {
            GL11.glLineWidth(1.0F);
            GlStateManager.depthMask(true);
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    private static void setColor(int argb) {
        float a = (argb >>> 24 & 255) / 255.0F;
        float r = (argb >> 16 & 255) / 255.0F;
        float g = (argb >> 8 & 255) / 255.0F;
        float b = (argb & 255) / 255.0F;
        GlStateManager.color(r, g, b, a);
    }

    private static WorldRenderer begin(int mode) {
        WorldRenderer buffer = Tessellator.getInstance().getWorldRenderer();
        buffer.begin(mode, DefaultVertexFormats.POSITION);
        return buffer;
    }

    private static void end() {
        Tessellator.getInstance().draw();
    }

    private static void drawFaces(double x0, double y0, double z0, double x1, double y1, double z1) {
        WorldRenderer r = begin(GL11.GL_QUADS);
        r.pos(x0, y0, z0).endVertex();
        r.pos(x0, y0, z1).endVertex();
        r.pos(x0, y1, z1).endVertex();
        r.pos(x0, y1, z0).endVertex();

        r.pos(x1, y0, z0).endVertex();
        r.pos(x1, y1, z0).endVertex();
        r.pos(x1, y1, z1).endVertex();
        r.pos(x1, y0, z1).endVertex();

        r.pos(x0, y0, z0).endVertex();
        r.pos(x0, y1, z0).endVertex();
        r.pos(x1, y1, z0).endVertex();
        r.pos(x1, y0, z0).endVertex();

        r.pos(x0, y0, z1).endVertex();
        r.pos(x1, y0, z1).endVertex();
        r.pos(x1, y1, z1).endVertex();
        r.pos(x0, y1, z1).endVertex();

        r.pos(x0, y0, z1).endVertex();
        r.pos(x0, y0, z0).endVertex();
        r.pos(x1, y0, z0).endVertex();
        r.pos(x1, y0, z1).endVertex();

        r.pos(x0, y1, z0).endVertex();
        r.pos(x0, y1, z1).endVertex();
        r.pos(x1, y1, z1).endVertex();
        r.pos(x1, y1, z0).endVertex();
        end();
    }

    private static void drawOutline(double x0, double y0, double z0, double x1, double y1, double z1) {
        WorldRenderer r = begin(GL11.GL_LINES);
        r.pos(x0, y0, z0).endVertex();
        r.pos(x0, y1, z0).endVertex();
        r.pos(x1, y0, z0).endVertex();
        r.pos(x1, y1, z0).endVertex();
        r.pos(x1, y0, z1).endVertex();
        r.pos(x1, y1, z1).endVertex();
        r.pos(x0, y0, z1).endVertex();
        r.pos(x0, y1, z1).endVertex();

        r.pos(x0, y0, z0).endVertex();
        r.pos(x1, y0, z0).endVertex();
        r.pos(x1, y0, z0).endVertex();
        r.pos(x1, y0, z1).endVertex();
        r.pos(x1, y0, z1).endVertex();
        r.pos(x0, y0, z1).endVertex();
        r.pos(x0, y0, z1).endVertex();
        r.pos(x0, y0, z0).endVertex();

        r.pos(x0, y1, z0).endVertex();
        r.pos(x1, y1, z0).endVertex();
        r.pos(x1, y1, z0).endVertex();
        r.pos(x1, y1, z1).endVertex();
        r.pos(x1, y1, z1).endVertex();
        r.pos(x0, y1, z1).endVertex();
        r.pos(x0, y1, z1).endVertex();
        r.pos(x0, y1, z0).endVertex();
        end();
    }
}
