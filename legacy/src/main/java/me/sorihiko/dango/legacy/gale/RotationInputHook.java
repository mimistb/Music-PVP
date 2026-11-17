package me.sorihiko.dango.legacy.gale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovementInputFromOptions;

/**
 * 移动输入修正（移植自 GaleMod-2.0.jar 的 gale.core.InputHook）。
 *
 * 静默转头时，WASD 仍按相机方向移动，而不是按服务端伪装朝向；
 * 具体按 {@link RotationEngine.MoveFix} 的 Legit / Exact / None 计算。
 */
public final class RotationInputHook extends MovementInputFromOptions {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private final MovementInput inner;
    private final EntityPlayerSP owner;

    private RotationInputHook(MovementInput inner, EntityPlayerSP owner) {
        super(mc.gameSettings);
        this.inner = inner;
        this.owner = owner;
    }

    /**
     * 宿主每 tick 在 ClientTickEvent START 与 LivingUpdateEvent（apply 之后）调用，
     * 与原版 Gale 的调用时机一致。引擎不活跃时会把包装层摘掉。
     */
    public static void ensure(EntityPlayerSP player) {
        if (player == null) {
            return;
        }
        MovementInput current = player.movementInput;
        if (current == null) {
            return;
        }
        boolean needed = RotationEngine.isActive();
        if (current instanceof RotationInputHook) {
            if (!needed) {
                player.movementInput = ((RotationInputHook) current).inner;
            }
            return;
        }
        if (!needed) {
            return;
        }
        player.movementInput = new RotationInputHook(current, player);
    }

    public MovementInput original() {
        return this.inner;
    }

    @Override
    public void updatePlayerMoveState() {
        boolean own = this.owner.movementInput == this;
        if (own) {
            this.owner.movementInput = this.inner;
        }
        try {
            this.inner.updatePlayerMoveState();
        } finally {
            if (own && this.owner.movementInput == this.inner) {
                this.owner.movementInput = this;
            }
        }

        float[] fixed = RotationEngine.toServerInput(this.inner.moveForward, this.inner.moveStrafe);
        this.moveForward = fixed[0];
        this.moveStrafe = fixed[1];
        this.jump = this.inner.jump;
        this.sneak = this.inner.sneak;
    }
}
