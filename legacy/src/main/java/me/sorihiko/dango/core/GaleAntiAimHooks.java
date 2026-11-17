package me.sorihiko.dango.core;

import me.sorihiko.dango.legacy.GaleAntiAimRuntime;
import net.minecraft.network.Packet;

/**
 * GaleAntiAim ASM 回调：NetworkManager#channelRead0 在交给原版处理之前替换包。
 *
 * <p>RotationEngine 只会把 S08 强制转头包装成内部 Teleport（且仅在静默旋转 active 时），
 * 其它包原样返回；任何异常都退回原包，保证网络层不受影响。
 */
public final class GaleAntiAimHooks {

    private GaleAntiAimHooks() {
    }

    public static Packet<?> interceptReceive(Packet<?> packet) {
        try {
            return GaleAntiAimRuntime.interceptPacket(packet);
        } catch (Throwable t) {
            return packet;
        }
    }
}
