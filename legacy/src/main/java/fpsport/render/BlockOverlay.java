package fpsport.render;

import fpsport.core.Feature;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.opengl.GL11;

/**
 * 方块高亮（BlockOverlay）。
 *
 * <p>给准星指向的方块画一层半透明填充 + 描边，可选穿墙显示。
 * 纯世界空间绘制，**不是 HUD**，宿主只要在自己的 3D 渲染阶段调 {@link #onRender3D()}。
 *
 * <p>原实现的顶点工具类（{@code Render3DUtils}）已内联到本类，避免多一个跨模块依赖。
 *
 * <p>状态管理约定：**所有被 GlStateManager 缓存的开关都必须经由 GlStateManager 切换**。
 * 裸 GL 调用不会更新它的缓存，缓存与真实状态脱节后，后续渲染会读到错误状态
 * （典型症状：RenderWorldLastEvent 之后绘制的手部/皮肤颜色被高亮色污染、整体变暗）。
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
        if (!using || (!fill && !outline)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.objectMouseOver == null) {
            return;
        }
        // 只有真的对准方块才高亮：对准实体时 objectMouseOver 也带着方块坐标，
        // 不判类型会把实体脚下的方块亮起来。
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        if (pos == null) {
            return;
        }
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        Material material = block.getMaterial();
        if (material.isReplaceable() || material.isLiquid()) {
            return;
        }

        // 优先用方块在当前状态下的碰撞盒（楼梯/台阶/栅栏等只有一个方块整体 bounds 会画歪）。
        AxisAlignedBB worldBox = block.getCollisionBoundingBox(mc.theWorld, pos, state);
        if (worldBox == null) {
            worldBox = new AxisAlignedBB(
                    pos.getX() + block.getBlockBoundsMinX(),
                    pos.getY() + block.getBlockBoundsMinY(),
                    pos.getZ() + block.getBlockBoundsMinZ(),
                    pos.getX() + block.getBlockBoundsMaxX(),
                    pos.getY() + block.getBlockBoundsMaxY(),
                    pos.getZ() + block.getBlockBoundsMaxZ());
        }

        // 世界坐标 -> 渲染原点（相对 viewer）。注意 AxisAlignedBB.offset 会就地修改，这里只读字段。
        double viewerX = mc.getRenderManager().viewerPosX;
        double viewerY = mc.getRenderManager().viewerPosY;
        double viewerZ = mc.getRenderManager().viewerPosZ;
        double x0 = worldBox.minX - viewerX;
        double y0 = worldBox.minY - viewerY;
        double z0 = worldBox.minZ - viewerZ;
        double x1 = worldBox.maxX - viewerX;
        double y1 = worldBox.maxY - viewerY;
        double z1 = worldBox.maxZ - viewerZ;
        if (x1 - x0 < 1.0E-4 || y1 - y0 < 1.0E-4 || z1 - z0 < 1.0E-4) {
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableTexture2D();
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        if (throughBlock) {
            GlStateManager.disableDepth();
        }
        GlStateManager.depthMask(false);
        try {
            if (fill) {
                setColor(fillColor);
                drawFaces(x0 - 0.002, y0 - 0.002, z0 - 0.002, x1 + 0.002, y1 + 0.002, z1 + 0.002);
            }
            if (outline) {
                setColor(outlineColor);
                GL11.glLineWidth(width);
                drawOutline(x0 - 0.001, y0 - 0.001, z0 - 0.001, x1 + 0.001, y1 + 0.001, z1 + 0.001);
            }
        } finally {
            // 成对恢复：全部经 GlStateManager，保证它的缓存与真实 GL 状态一致。
            GL11.glLineWidth(1.0F);
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GlStateManager.depthMask(true);
            if (throughBlock) {
                GlStateManager.enableDepth();
            }
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
        // 只走 GlStateManager：它会同时写缓存与 GL。裸 glColor4f 会让缓存仍认为颜色是白色，
        // 结尾的复位不再下发，GL 当前色残留为高亮色 -> 之后绘制的手部/皮肤被染色变暗。
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

    /** 六个面，顶点绕序保证外法线朝外（GL_CULL_FACE 默认开启时只画正面）。 */
    private static void drawFaces(double x0, double y0, double z0, double x1, double y1, double z1) {
        WorldRenderer r = begin(GL11.GL_QUADS);
        // -X
        r.pos(x0, y0, z0).endVertex();
        r.pos(x0, y0, z1).endVertex();
        r.pos(x0, y1, z1).endVertex();
        r.pos(x0, y1, z0).endVertex();
        // +X
        r.pos(x1, y0, z0).endVertex();
        r.pos(x1, y1, z0).endVertex();
        r.pos(x1, y1, z1).endVertex();
        r.pos(x1, y0, z1).endVertex();
        // -Z
        r.pos(x0, y0, z0).endVertex();
        r.pos(x0, y1, z0).endVertex();
        r.pos(x1, y1, z0).endVertex();
        r.pos(x1, y0, z0).endVertex();
        // +Z
        r.pos(x0, y0, z1).endVertex();
        r.pos(x1, y0, z1).endVertex();
        r.pos(x1, y1, z1).endVertex();
        r.pos(x0, y1, z1).endVertex();
        // -Y
        r.pos(x0, y0, z1).endVertex();
        r.pos(x0, y0, z0).endVertex();
        r.pos(x1, y0, z0).endVertex();
        r.pos(x1, y0, z1).endVertex();
        // +Y
        r.pos(x0, y1, z0).endVertex();
        r.pos(x0, y1, z1).endVertex();
        r.pos(x1, y1, z1).endVertex();
        r.pos(x1, y1, z0).endVertex();
        end();
    }

    /** 12 条边一次画完（原来拆成 3 次 draw，且中间还夹着退化的 quad）。 */
    private static void drawOutline(double x0, double y0, double z0,
                                    double x1, double y1, double z1) {
        WorldRenderer r = begin(GL11.GL_LINES);
        // 4 条竖直边
        r.pos(x0, y0, z0).endVertex();
        r.pos(x0, y1, z0).endVertex();
        r.pos(x1, y0, z0).endVertex();
        r.pos(x1, y1, z0).endVertex();
        r.pos(x1, y0, z1).endVertex();
        r.pos(x1, y1, z1).endVertex();
        r.pos(x0, y0, z1).endVertex();
        r.pos(x0, y1, z1).endVertex();
        // 底面 4 条
        r.pos(x0, y0, z0).endVertex();
        r.pos(x1, y0, z0).endVertex();
        r.pos(x1, y0, z0).endVertex();
        r.pos(x1, y0, z1).endVertex();
        r.pos(x1, y0, z1).endVertex();
        r.pos(x0, y0, z1).endVertex();
        r.pos(x0, y0, z1).endVertex();
        r.pos(x0, y0, z0).endVertex();
        // 顶面 4 条
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
