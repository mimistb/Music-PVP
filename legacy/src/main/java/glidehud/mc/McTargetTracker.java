package glidehud.mc;

import glidehud.api.TargetData;
import glidehud.api.TargetInfoProvider;
import me.sorihiko.dango.hud.TargetScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovingObjectPosition;

/**
 * 1.8.9 port of Glide's TargetUtils: tracks the player under the crosshair.
 *
 * <p>Rules (identical to Glide):</p>
 * <ul>
 *     <li>a crosshair player that is present in the tab list becomes the target;</li>
 *     <li>after 2500ms without a crosshair entity and no mouse-over at all, the
 *     target is cleared;</li>
 *     <li>the target is cleared when it or the local player dies, or when it moves
 *     further than 12 blocks away.</li>
 * </ul>
 */
public final class McTargetTracker implements TargetInfoProvider, TargetScreen {

    private static final long CLEAR_DELAY_MS = 2500L;
    private static final float MAX_DISTANCE = 12.0F;

    private AbstractClientPlayer target;
    private long timerStart = System.currentTimeMillis();

    /** 复用同一份快照：数值没变就不新建对象（避免每帧分配） */
    private TargetData cached;
    private String cachedName;
    private float cachedHealth = Float.NaN;
    private float cachedArmor = Float.NaN;
    private Object cachedHead;

    /** Call once per client tick (optional: {@link #getTarget()} also refreshes). */
    @Override
    public void update() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        MovingObjectPosition mouseOver = mc.objectMouseOver;
        Entity hit = mouseOver != null ? mouseOver.entityHit : null;

        if (hit != null && hit != this.target) {
            if (hit instanceof AbstractClientPlayer && isInTablist(mc, hit)) {
                this.target = (AbstractClientPlayer) hit;
                this.timerStart = System.currentTimeMillis();
            }
        } else if (System.currentTimeMillis() - this.timerStart >= CLEAR_DELAY_MS && mouseOver == null) {
            this.target = null;
            this.timerStart = System.currentTimeMillis();
        }

        if (this.target != null) {
            if (this.target.isDead || (player != null && player.isDead)) {
                this.target = null;
            } else if (player != null && this.target.getDistanceToEntity(player) > MAX_DISTANCE) {
                this.target = null;
            }
        }
    }

    @Override
    public TargetData getTarget() {
        update();
        AbstractClientPlayer current = this.target;
        if (current == null) {
            this.cached = null;
            this.cachedName = null;
            this.cachedHealth = Float.NaN;
            this.cachedArmor = Float.NaN;
            this.cachedHead = null;
            return null;
        }
        String name = current.getName();
        float health = current.getHealth();
        float armor = current.getTotalArmorValue();
        Object head = current.getLocationSkin();
        TargetData data = this.cached;
        if (data == null || !name.equals(this.cachedName) || health != this.cachedHealth
                || armor != this.cachedArmor || head != this.cachedHead) {
            data = new TargetData(name, health, armor, head);
            this.cached = data;
            this.cachedName = name;
            this.cachedHealth = health;
            this.cachedArmor = armor;
            this.cachedHead = head;
        }
        return data;
    }

    @Override
    public boolean headPos(double[] out) {
        return copyPos(out, this.target == null ? 0.0 : this.target.posY + this.target.height);
    }

    @Override
    public boolean chinPos(double[] out) {
        return copyPos(out, this.target == null ? 0.0 : this.target.posY + this.target.height - 0.45);
    }

    /** 目标世界坐标（不插值：和摄像机位置同源，面板不会抖） */
    private boolean copyPos(double[] out, double y) {
        AbstractClientPlayer current = this.target;
        if (current == null || out == null || out.length < 3) {
            return false;
        }
        out[0] = current.posX;
        out[1] = y;
        out[2] = current.posZ;
        return true;
    }

    private static boolean isInTablist(Minecraft mc, Entity entity) {
        // 单人游戏里没有 Tab 列表，但准星下的玩家一样应该能作为目标显示
        if (mc.isSingleplayer()) {
            return true;
        }
        if (mc.getCurrentServerData() == null) {
            return false;
        }
        NetHandlerPlayClient handler = mc.getNetHandler();
        if (handler == null) {
            return false;
        }
        for (NetworkPlayerInfo info : handler.getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) {
                continue;
            }
            if (info.getGameProfile().getName().contains(entity.getName())) {
                return true;
            }
        }
        return false;
    }
}
