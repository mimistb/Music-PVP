package fpsport.pvp;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import java.util.List;

/**
 * 队友 / 敌人标记：把玩家位置投影到屏幕上，用不同颜色画标记 + 名字 + 距离。
 *
 * <p>画在 HUD 阶段，因此天然穿墙、远距离可见；渲染成本只与在线玩家数相关。
 * 队友判定交给 {@link Entity#isOnSameTeam(Entity)}（计分板队伍）。
 */
public class EntityMarkers extends Feature {

    /** 宿主读取的开关标志。 */
    public static boolean using = false;
    /** 队友颜色（ARGB）。 */
    public static int teammateColor = 0xFF55FF55;
    /** 敌人颜色（ARGB）。 */
    public static int enemyColor = 0xFFFF5555;
    /** 最大标记距离（格）。 */
    public static int maxDistance = 128;
    /** 是否显示距离文字。 */
    public static boolean showDistance = true;
    /** 标记半径（像素）。 */
    public static int size = 10;
    /** 当前帧的实际纵向 FOV，由宿主在 FOVModifier 事件里写入。 */
    public static float viewFov = 70.0F;

    public EntityMarkers() {
        super("EntityMarkers");
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
    public static void render(int width, int height, float partialTicks) {
        if (!using) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer self = mc.thePlayer;
        World world = mc.theWorld;
        if (self == null || world == null) {
            return;
        }
        List<EntityPlayer> players = world.playerEntities;
        if (players == null || players.isEmpty()) {
            return;
        }
        Entity view = mc.getRenderViewEntity();
        if (view == null) {
            view = self;
        }
        RenderManager rm = mc.getRenderManager();
        float yaw = view.rotationYaw;
        float pitch = view.rotationPitch;
        double pt = Math.max(0.0D, Math.min(1.0D, partialTicks));
        int limit = Math.max(8, maxDistance);
        float radius = Math.max(4.0F, size);

        for (EntityPlayer target : players) {
            if (target == null || target == self || target.isDead) {
                continue;
            }
            double dist = target.getDistanceToEntity(self);
            if (dist > limit) {
                continue;
            }
            double tx = target.lastTickPosX + (target.posX - target.lastTickPosX) * pt;
            double ty = target.lastTickPosY + (target.posY - target.lastTickPosY) * pt + target.height * 0.9D;
            double tz = target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * pt;

            float[] screen = PvpLogic.project(
                    tx - rm.viewerPosX, ty - rm.viewerPosY, tz - rm.viewerPosZ,
                    yaw, pitch, viewFov, width, height);
            if (screen == null) {
                continue;
            }
            float sx = screen[0];
            float sy = screen[1];
            if (sx < -radius || sx > width + radius || sy < -radius || sy > height + radius) {
                continue;
            }

            int color = self.isOnSameTeam(target) ? teammateColor : enemyColor;
            drawMarker(sx, sy, radius, color);

            String name = target.getName();
            if (name != null && !name.isEmpty()) {
                float nameWidth = mc.fontRendererObj.getStringWidth(name);
                PvpHud.text(name, sx - nameWidth * 0.5F, sy - radius - 13.0F, color, true);
            }
            if (showDistance) {
                String label = Math.round(dist) + "m";
                float labelWidth = mc.fontRendererObj.getStringWidth(label);
                PvpHud.text(label, sx - labelWidth * 0.5F, sy + radius + 4.0F, 0xFFFFFFFF, true);
            }
        }
    }

    private static void drawMarker(float x, float y, float radius, int color) {
        float inner = radius * 0.55F;
        PvpHud.line(x, y - radius, x + radius, y, color, 2.0F);
        PvpHud.line(x + radius, y, x, y + radius, color, 2.0F);
        PvpHud.line(x, y + radius, x - radius, y, color, 2.0F);
        PvpHud.line(x - radius, y, x, y - radius, color, 2.0F);
        PvpHud.line(x, y - inner, x + inner, y, color, 1.0F);
        PvpHud.line(x + inner, y, x, y + inner, color, 1.0F);
        PvpHud.line(x, y + inner, x - inner, y, color, 1.0F);
        PvpHud.line(x - inner, y, x, y - inner, color, 1.0F);
    }
}
