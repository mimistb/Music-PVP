package me.sorihiko.dango.legacy.gale;

import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;

/**
 * 静默旋转引擎（移植自 GaleMod-2.0.jar 的 gale.core.RotationManager）。
 *
 * 原版逻辑完整保留：请求队列（优先级）、GCD 修正、回正速度、移动修正、
 * S08 传送包拦截（隐藏服务器强制转头）、tick 前后相机/服务端朝向互换。
 *
 * 与原版的唯一差异：移除 Gale 事件总线，改为公开钩子
 * {@link #interceptReceive(Packet)}，由宿主在收包处显式调用。
 */
public final class RotationEngine {

    private static final Minecraft mc = Minecraft.getMinecraft();

    /* ---- 由 GaleAntiAimModule 每 tick 写入的可配置项（默认值与 Gale 一致） ---- */
    public static volatile boolean gcdFix = true;
    public static volatile float releaseSpeed = 45.0f;
    public static volatile MoveFix defaultFix = MoveFix.LEGIT;

    /* ---- 内部状态（与原版一致） ---- */
    private static Object owner;
    private static float wantYaw;
    private static float wantPitch;
    private static float wantTurn;
    private static int wantPriority = Integer.MIN_VALUE;
    private static MoveFix wantFix;
    private static volatile boolean active;
    private static boolean swapped;
    private static float serverYaw;
    private static float serverPitch;
    private static float cameraYaw;
    private static float cameraPitch;
    private static EntityPlayerSP swappedPlayer;
    private static MoveFix fix = MoveFix.LEGIT;
    private static Object activeOwner;

    private RotationEngine() {
    }

    /* ------------------------------------------------------------------ */
    /* 请求接口                                                            */
    /* ------------------------------------------------------------------ */

    public static void request(Object owner, float yaw, float pitch, int priority) {
        request(owner, yaw, pitch, priority, defaultFix);
    }

    public static void request(Object owner, float[] rotation, int priority) {
        request(owner, rotation[0], rotation[1], priority, defaultFix);
    }

    public static void request(Object owner, float yaw, float pitch, int priority, MoveFix fix) {
        request(owner, yaw, pitch, priority, fix, 0.0f);
    }

    public static void request(Object owner, float yaw, float pitch, int priority, MoveFix fix, float maxTurn) {
        if (priority <= wantPriority) {
            return;
        }
        RotationEngine.owner = owner;
        wantYaw = yaw;
        wantPitch = clamp(pitch, -90.0f, 90.0f);
        wantPriority = priority;
        wantFix = fix == null ? defaultFix : fix;
        wantTurn = maxTurn;
    }

    public static void requestSmooth(Object owner, float[] rotation, int priority, float maxTurn) {
        request(owner, rotation[0], rotation[1], priority, defaultFix, maxTurn);
    }

    public static float[] current() {
        return new float[]{yaw(), pitch()};
    }

    public static boolean isActive() {
        return active;
    }

    public static Object owner() {
        return activeOwner;
    }

    public static float yaw() {
        return active ? serverYaw : (mc.thePlayer != null ? mc.thePlayer.rotationYaw : 0.0f);
    }

    public static float pitch() {
        return active ? serverPitch : (mc.thePlayer != null ? mc.thePlayer.rotationPitch : 0.0f);
    }

    public static float cameraYaw() {
        return swapped ? cameraYaw : (mc.thePlayer != null ? mc.thePlayer.rotationYaw : 0.0f);
    }

    public static float cameraPitch() {
        return swapped ? cameraPitch : (mc.thePlayer != null ? mc.thePlayer.rotationPitch : 0.0f);
    }

    /** 鼠标灵敏度决定的单格转动量（GCD fix 用）。 */
    public static float gcd() {
        float s = mc.gameSettings.mouseSensitivity * 0.6f + 0.2f;
        return s * s * s * 8.0f * 0.15f;
    }

    /* ------------------------------------------------------------------ */
    /* tick 生命周期（宿主按 Gale 的时序调用）                              */
    /* ------------------------------------------------------------------ */

    /** 每 tick 开始时清空请求（Gale: LivingUpdateEvent 内、UpdateEvent 之前）。 */
    public static void beginTick() {
        owner = null;
        wantPriority = Integer.MIN_VALUE;
        wantFix = null;
        wantTurn = 0.0f;
    }

    /** 计算并写入本 tick 的服务端朝向（Gale: UpdateEvent 之后立即调用）。 */
    public static void apply() {
        EntityPlayerSP p = mc.thePlayer;
        if (p == null) {
            active = false;
            return;
        }
        float camYaw = p.rotationYaw;
        float camPitch = p.rotationPitch;
        float targetYaw;
        float targetPitch;
        if (wantFix != null) {
            targetYaw = wantYaw;
            targetPitch = wantPitch;
            fix = wantFix;
            activeOwner = owner;
        } else if (active) {
            float dy = wrap180(camYaw - serverYaw);
            float dp = camPitch - serverPitch;
            if (Math.abs(dy) <= releaseSpeed && Math.abs(dp) <= releaseSpeed) {
                active = false;
                activeOwner = null;
                return;
            }
            targetYaw = serverYaw + clamp(dy, -releaseSpeed, releaseSpeed);
            targetPitch = serverPitch + clamp(dp, -releaseSpeed, releaseSpeed);
            activeOwner = null;
        } else {
            return;
        }
        float baseYaw = active ? serverYaw : camYaw;
        float basePitch = active ? serverPitch : camPitch;
        float dYaw = wrap180(targetYaw - baseYaw);
        float dPitch = clamp(targetPitch, -90.0f, 90.0f) - basePitch;
        if (wantFix != null && wantTurn > 0.0f) {
            dYaw = clamp(dYaw, -wantTurn, wantTurn);
            dPitch = clamp(dPitch, -wantTurn, wantTurn);
        }
        if (gcdFix) {
            float g = gcd();
            dYaw = (float) Math.round(dYaw / g) * g;
            dPitch = (float) Math.round(dPitch / g) * g;
        }
        serverYaw = baseYaw + dYaw;
        serverPitch = clamp(basePitch + dPitch, -90.0f, 90.0f);
        active = true;
        cameraYaw = camYaw;
        cameraPitch = camPitch;
        swappedPlayer = p;
        p.rotationYaw = serverYaw;
        p.rotationPitch = serverPitch;
        swapped = true;
    }

    /** 恢复相机朝向（Gale: ClientTickEvent END）。 */
    public static void endTick() {
        if (!swapped) {
            return;
        }
        swapped = false;
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || p != swappedPlayer) {
            return;
        }
        float dYaw = p.rotationYaw - serverYaw;
        float dPitch = p.rotationPitch - serverPitch;
        p.rotationYaw = cameraYaw + dYaw;
        p.rotationPitch = clamp(cameraPitch + dPitch, -90.0f, 90.0f);
        p.prevRotationYaw = cameraYaw;
        p.prevRotationPitch = cameraPitch;
    }

    /**
     * 关闭模块 / 退出世界时彻底复位：先恢复相机，再清掉所有请求与状态。
     * 与 endTick 的区别是即使 swapped 标志异常也强制恢复。
     */
    public static void reset() {
        endTick();
        swapped = false;
        active = false;
        owner = null;
        activeOwner = null;
        wantPriority = Integer.MIN_VALUE;
        wantFix = null;
        wantTurn = 0.0f;
    }

    /* ------------------------------------------------------------------ */
    /* 移动修正                                                            */
    /* ------------------------------------------------------------------ */

    public static float[] fixInput(float forward, float strafe) {
        if (!swapped || fix == MoveFix.NONE || forward == 0.0f && strafe == 0.0f) {
            return new float[]{forward, strafe};
        }
        double d = Math.toRadians(wrap180(cameraYaw - serverYaw));
        double f = (double) forward * Math.cos(d) + (double) strafe * Math.sin(d);
        double s = (double) strafe * Math.cos(d) - (double) forward * Math.sin(d);
        if (fix == MoveFix.LEGIT) {
            float magnitude = Math.max(Math.abs(forward), Math.abs(strafe));
            double angle = (double) Math.round(Math.atan2(s, f) / 0.7853981633974483) * 0.7853981633974483;
            f = (float) Math.round(Math.cos(angle)) * magnitude;
            s = (float) Math.round(Math.sin(angle)) * magnitude;
        }
        return new float[]{(float) f, (float) s};
    }

    public static float[] toServerInput(float forward, float strafe) {
        return fixInput(forward, strafe);
    }

    /* ------------------------------------------------------------------ */
    /* 收包钩子：S08 强制转头隐藏                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 宿主在收到服务端包、交给原版处理之前调用。
     *
     * @return 需要替换的包（S08 包装为 Teleport）；否则原包返回。
     */
    public static Packet<?> interceptReceive(Packet<?> packet) {
        if (packet instanceof S08PacketPlayerPosLook && active && !(packet instanceof Teleport)) {
            S08PacketPlayerPosLook p = (S08PacketPlayerPosLook) packet;
            return new Teleport(p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(), p.func_179834_f());
        }
        return packet;
    }

    static final class Teleport extends S08PacketPlayerPosLook {
        Teleport(double x, double y, double z, float yaw, float pitch, Set<S08PacketPlayerPosLook.EnumFlags> flags) {
            super(x, y, z, yaw, pitch, flags);
        }

        @Override
        public void processPacket(INetHandlerPlayClient handler) {
            EntityPlayerSP p = mc.thePlayer;
            boolean apply = mc.isCallingFromMinecraftThread() && p != null && active && !swapped;
            float camYaw = apply ? p.rotationYaw : 0.0f;
            float camPitch = apply ? p.rotationPitch : 0.0f;
            super.processPacket(handler);
            if (!apply || p != mc.thePlayer) {
                return;
            }
            serverYaw = p.rotationYaw;
            serverPitch = p.rotationPitch;
            p.rotationYaw = p.prevRotationYaw = camYaw;
            p.rotationPitch = p.prevRotationPitch = camPitch;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 数学（与 gale.util.MathUtil 等价）                                   */
    /* ------------------------------------------------------------------ */

    static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    static float wrap180(float a) {
        if ((a %= 360.0f) >= 180.0f) {
            a -= 360.0f;
        }
        if (a < -180.0f) {
            a += 360.0f;
        }
        return a;
    }

    public enum MoveFix {
        NONE,
        EXACT,
        LEGIT
    }
}
