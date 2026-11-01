package fpsport.render;

import fpsport.core.Feature;
import net.minecraft.block.Block;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import org.lwjgl.opengl.GL11;

/**
 * 方块高亮（BlockOverlay）。
 *
 * <p>给准星指向的方块画一层半透明填充 + 描边，可选穿墙显示。
 * 纯世界空间绘制，**不是 HUD**，宿主只要在自己的 3D 渲染阶段调 {@link #onRender3D()}。
 *
 * <p>原实现的顶点工具类（{@code Render3DUtils}）已内联到本类，避免多一个跨模块依赖。
 */
public class BlockOverlay extends Feature {

    /** 是否画半透明填充面。 */
    public static boolean fill = true;
    /** 是否画描边线。 */
    public static boolean outline = true;
    /** 是否穿墙显示（关闭深度测试）。 */
    public static boolean throughBlock = false;
    /** 描边线宽（像素）。 */
    public static float width = 1.0F;
    /** 填充色，ARGB（默认白 50/255 不透明）。 */
    public static int fillColor = 0x32FFFFFF;
    /** 描边色，ARGB（默认不透明白）。 */
    public static int outlineColor = 0xFFFFFFFF;

    /** 宿主读取的开关标志。 */
    public static boolean using = false;

    public BlockOverlay() {
        super("BlockOverlay");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
    }

    /** 在 3D 渲染阶段（画手之前）调用。 */
    public static void onRender3D() {
        if (!using) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.objectMouseOver == null || mc.theWorld == null) {
            return;
        }
        BlockPos pos = mc.objectMouseOver.getBlockPos();
        if (pos == null) {
            return;
        }
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        if (block.getMaterial().isReplaceable()) {
            return;
        }

        double x = pos.getX() - mc.getRenderManager().viewerPosX;
        double y = pos.getY() - mc.getRenderManager().viewerPosY;
        double z = pos.getZ() - mc.getRenderManager().viewerPosZ;

        AxisAlignedBB bb = new AxisAlignedBB(
                block.getBlockBoundsMinX(),
                block.getBlockBoundsMinY(),
                block.getBlockBoundsMinZ(),
                block.getBlockBoundsMaxX(),
                block.getBlockBoundsMaxY(),
                block.getBlockBoundsMaxZ());
        // 楼梯的下半部分盒子从 0 开始，否则会悬空。
        double minX = block instanceof BlockStairs ? 0.0 : bb.minX;
        double minY = block instanceof BlockStairs ? 0.0 : bb.minY;
        double minZ = block instanceof BlockStairs ? 0.0 : bb.minZ;

        GL11.glPushMatrix();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        if (throughBlock) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        GL11.glDepthMask(false);

        if (fill) {
            GL11.glPushMatrix();
            setColor(fillColor);
            drawFaces(new AxisAlignedBB(
                    x + minX - 0.01,
                    y + minY - 0.01,
                    z + minZ - 0.01,
                    x + bb.maxX + 0.01,
                    y + bb.maxY + 0.01,
                    z + bb.maxZ + 0.01));
            GL11.glPopMatrix();
        }
        if (outline) {
            GL11.glPushMatrix();
            setColor(outlineColor);
            GL11.glLineWidth(width);
            drawOutline(new AxisAlignedBB(
                    x + minX - 0.005,
                    y + minY - 0.005,
                    z + minZ - 0.005,
                    x + bb.maxX + 0.005,
                    y + bb.maxY + 0.005,
                    z + bb.maxZ + 0.005));
            GL11.glPopMatrix();
        }

        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        if (throughBlock) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
        GL11.glDepthMask(true);
        GL11.glLineWidth(1.0F);
        // 成对恢复：不开混合/Alpha 的后续渲染（原版 GUI、实体）依赖默认状态
        GlStateManager.disableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    private static void setColor(int argb) {
        float a = (argb >>> 24 & 255) / 255.0F;
        float r = (argb >> 16 & 255) / 255.0F;
        float g = (argb >> 8 & 255) / 255.0F;
        float b = (argb & 255) / 255.0F;
        GlStateManager.color(r, g, b, a);
        // 同时直接调 GL：Tessellator 取的是 GL 当前颜色，只改 GlStateManager 可能命中缓存而不生效
        GL11.glColor4f(r, g, b, a);
    }

    private static WorldRenderer begin(int mode) {
        WorldRenderer buffer = Tessellator.getInstance().getWorldRenderer();
        buffer.begin(mode, DefaultVertexFormats.POSITION);
        return buffer;
    }

    private static void end() {
        Tessellator.getInstance().draw();
    }

    private static void bottomLoop(AxisAlignedBB b, WorldRenderer r) {
        r.pos(b.minX, b.minY, b.minZ).endVertex();
        r.pos(b.maxX, b.minY, b.minZ).endVertex();
        r.pos(b.maxX, b.minY, b.maxZ).endVertex();
        r.pos(b.minX, b.minY, b.maxZ).endVertex();
        r.pos(b.minX, b.minY, b.minZ).endVertex();
    }

    private static void topLoop(AxisAlignedBB b, WorldRenderer r) {
        r.pos(b.minX, b.maxY, b.minZ).endVertex();
        r.pos(b.maxX, b.maxY, b.minZ).endVertex();
        r.pos(b.maxX, b.maxY, b.maxZ).endVertex();
        r.pos(b.minX, b.maxY, b.maxZ).endVertex();
        r.pos(b.minX, b.maxY, b.minZ).endVertex();
    }

    private static void sideEdges(AxisAlignedBB b, WorldRenderer r) {
        r.pos(b.minX, b.minY, b.minZ).endVertex();
        r.pos(b.minX, b.maxY, b.minZ).endVertex();
        r.pos(b.maxX, b.minY, b.minZ).endVertex();
        r.pos(b.maxX, b.maxY, b.minZ).endVertex();
        r.pos(b.maxX, b.minY, b.maxZ).endVertex();
        r.pos(b.maxX, b.maxY, b.maxZ).endVertex();
        r.pos(b.minX, b.minY, b.maxZ).endVertex();
        r.pos(b.minX, b.maxY, b.maxZ).endVertex();
    }

    private static void drawOutline(AxisAlignedBB b) {
        WorldRenderer r = begin(GL11.GL_LINE_STRIP);
        bottomLoop(b, r);
        end();

        r = begin(GL11.GL_LINE_STRIP);
        topLoop(b, r);
        end();

        r = begin(GL11.GL_LINES);
        sideEdges(b, r);
        end();
    }

    /** 六个面：顶点顺序与原始实现保持一致，避免出现缺面。 */
    private static void drawFaces(AxisAlignedBB b) {
        WorldRenderer r = begin(GL11.GL_QUADS);
        sideEdges(b, r);
        end();

        r = begin(GL11.GL_QUADS);
        r.pos(b.maxX, b.maxY, b.minZ).endVertex();
        r.pos(b.maxX, b.minY, b.minZ).endVertex();
        r.pos(b.minX, b.maxY, b.minZ).endVertex();
        r.pos(b.minX, b.minY, b.minZ).endVertex();
        r.pos(b.minX, b.maxY, b.maxZ).endVertex();
        r.pos(b.minX, b.minY, b.maxZ).endVertex();
        r.pos(b.maxX, b.maxY, b.maxZ).endVertex();
        r.pos(b.maxX, b.minY, b.maxZ).endVertex();
        end();

        r = begin(GL11.GL_QUADS);
        topLoop(b, r);
        r.pos(b.minX, b.maxY, b.maxZ).endVertex();
        r.pos(b.maxX, b.maxY, b.maxZ).endVertex();
        r.pos(b.maxX, b.maxY, b.minZ).endVertex();
        end();

        r = begin(GL11.GL_QUADS);
        bottomLoop(b, r);
        r.pos(b.minX, b.minY, b.maxZ).endVertex();
        r.pos(b.maxX, b.minY, b.maxZ).endVertex();
        r.pos(b.maxX, b.minY, b.minZ).endVertex();
        end();

        r = begin(GL11.GL_QUADS);
        r.pos(b.minX, b.minY, b.minZ).endVertex();
        r.pos(b.minX, b.maxY, b.minZ).endVertex();
        r.pos(b.minX, b.minY, b.maxZ).endVertex();
        r.pos(b.minX, b.maxY, b.maxZ).endVertex();
        r.pos(b.maxX, b.minY, b.maxZ).endVertex();
        r.pos(b.maxX, b.maxY, b.maxZ).endVertex();
        r.pos(b.maxX, b.minY, b.minZ).endVertex();
        r.pos(b.maxX, b.maxY, b.minZ).endVertex();
        end();

        r = begin(GL11.GL_QUADS);
        r.pos(b.minX, b.maxY, b.maxZ).endVertex();
        r.pos(b.minX, b.minY, b.maxZ).endVertex();
        r.pos(b.minX, b.maxY, b.minZ).endVertex();
        r.pos(b.minX, b.minY, b.minZ).endVertex();
        r.pos(b.maxX, b.maxY, b.minZ).endVertex();
        r.pos(b.maxX, b.minY, b.minZ).endVertex();
        r.pos(b.maxX, b.maxY, b.maxZ).endVertex();
        r.pos(b.maxX, b.minY, b.maxZ).endVertex();
        end();
    }
}
