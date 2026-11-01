package fpsport.render;

import fpsport.core.BindKeys;
import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.EntityViewRenderEvent;

import org.lwjgl.input.Keyboard;

/**
 * 自由视角：按住绑定键时相机脱离人物朝向，松开立刻还原。
 *
 * <p>宿主没有 Mixin，所以这里不用 MixinEntityRenderer，而是：
 * <ol>
 *   <li>{@link #onUpdate()} 每 tick 检测按键，切第三人称并记录激活时的朝向；</li>
 *   <li>{@link #applyCamera(EntityViewRenderEvent.CameraSetup)} 在 Forge 的相机事件里接管
 *       相机偏航 / 俯仰，并把玩家本体的朝向固定回激活时的值（鼠标转的是相机，不是人物）。</li>
 * </ol>
 * 相机事件在 1.8.9 里可写（public yaw / pitch / roll），因此不需要改任何渲染类。
 */
public class FreeLook extends Feature {

    /** 按住式绑定键，默认左 Alt。 */
    public static int bind = Keyboard.KEY_LMENU;

    /** 相机灵敏度倍率，1.0 = 与原版鼠标一致。 */
    public static float sensitivity = 1.0f;

    public static boolean using = false;
    public static boolean perspectiveToggled = false;
    public static float cameraYaw = 0f;
    public static float cameraPitch = 0f;

    /** 激活前的第三人称视角序号，用于松开时精确还原。 */
    private static int previousPerspective = 0;
    /** 激活瞬间玩家本体的朝向，全程固定不变。 */
    private static float frozenYaw = 0f;
    private static float frozenPitch = 0f;

    public FreeLook() {
        super("FreeLook");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
        deactivate(Minecraft.getMinecraft());
    }

    /** 宿主每 tick 调用一次（对应源码的 EventUpdate）。 */
    public void onUpdate() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        if (mc.currentScreen != null || mc.thePlayer == null) {
            deactivate(mc);
            return;
        }
        if (BindKeys.isDown(bind)) {
            if (!perspectiveToggled) activate(mc);
        } else if (perspectiveToggled) {
            deactivate(mc);
        }
    }

    private static void activate(Minecraft mc) {
        EntityPlayer player = mc.thePlayer;
        perspectiveToggled = true;
        previousPerspective = mc.gameSettings.thirdPersonView;
        mc.gameSettings.thirdPersonView = 1;
        frozenYaw = player.rotationYaw;
        frozenPitch = player.rotationPitch;
        cameraYaw = frozenYaw;
        cameraPitch = frozenPitch;
    }

    private static void deactivate(Minecraft mc) {
        if (!perspectiveToggled) return;
        perspectiveToggled = false;
        if (mc == null) return;
        mc.gameSettings.thirdPersonView = previousPerspective;
        EntityPlayer player = mc.thePlayer;
        if (player != null) {
            player.rotationYaw = frozenYaw;
            player.prevRotationYaw = frozenYaw;
            player.rotationPitch = frozenPitch;
            player.prevRotationPitch = frozenPitch;
        }
    }

    /**
     * 由 Forge 的 {@code EntityViewRenderEvent.CameraSetup} 调用（在相机矩阵建立前、每帧一次）。
     *
     * <p>原版已经把本帧的鼠标增量写进玩家朝向，这里把它转移到相机并还原玩家朝向，
     * 于是「鼠标转相机、人物不动」。
     */
    public static void applyCamera(EntityViewRenderEvent.CameraSetup event) {
        if (!using || !perspectiveToggled) return;
        Minecraft mc = Minecraft.getMinecraft();
        // 打开任意界面时立即退出自由视角，避免相机与人物朝向被永久冻结
        if (mc.currentScreen != null) {
            deactivate(mc);
            return;
        }
        EntityPlayer player = mc.thePlayer;
        if (player == null) return;

        float deltaYaw = player.rotationYaw - frozenYaw;
        float deltaPitch = player.rotationPitch - frozenPitch;
        if (deltaYaw != 0f || deltaPitch != 0f) {
            cameraYaw += deltaYaw * sensitivity;
            cameraPitch += deltaPitch * sensitivity;
            if (cameraPitch > 90f) cameraPitch = 90f;
            if (cameraPitch < -90f) cameraPitch = -90f;
        }

        // 人物本体（含身体朝向）固定回激活时的值
        player.rotationYaw = frozenYaw;
        player.prevRotationYaw = frozenYaw;
        player.rotationPitch = frozenPitch;
        player.prevRotationPitch = frozenPitch;
        player.renderYawOffset = frozenYaw;
        player.prevRenderYawOffset = frozenYaw;
        player.rotationYawHead = frozenYaw;
        player.prevRotationYawHead = frozenYaw;

        // 原版 yaw 带 180 度偏移，这里同样加上
        event.yaw = cameraYaw + 180f;
        event.pitch = cameraPitch;
    }

    public static float getCameraYaw() {
        return perspectiveToggled ? cameraYaw : 0f;
    }

    public static float getCameraPitch() {
        return perspectiveToggled ? cameraPitch : 0f;
    }
}
