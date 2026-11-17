package fpsport.render;

import fpsport.core.BindKeys;
import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.EntityViewRenderEvent;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

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
    /** 组合键的修饰键（Shift / Ctrl / Alt 的键码），为空表示单键。 */
    public static int[] bindMods = new int[0];

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
        // 窗口失焦（Alt+Tab）时按键状态可能卡住，必须立即退出，否则会一直停在第三人称看着自己的皮肤
        if (mc.currentScreen != null || mc.thePlayer == null || !Display.isActive()) {
            deactivate(mc);
            return;
        }
        if (BindKeys.isDown(bind, bindMods)) {
            if (!perspectiveToggled) activate(mc);
        } else if (perspectiveToggled) {
            deactivate(mc);
        }
    }

    private static void activate(Minecraft mc) {
        EntityPlayer player = mc.thePlayer;
        if (player == null) return;
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
        // 只还原「仍然是我们强制的那一档」；自由视角期间玩家自己按过 F5 时以他的选择为准，
        // 避免退出后把视角强行改回去（或反过来把人留在第三人称里）。
        if (mc.gameSettings.thirdPersonView == 1) {
            mc.gameSettings.thirdPersonView = previousPerspective;
        }
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
        if (!perspectiveToggled) return;
        Minecraft mc = Minecraft.getMinecraft();
        // 模块被关掉 / 打开任意界面：立刻还原视角与朝向，避免相机和人物朝向被永久冻结
        if (!using || mc.currentScreen != null) {
            deactivate(mc);
            return;
        }
        EntityPlayer player = mc.thePlayer;
        if (player == null) {
            deactivate(mc);
            return;
        }

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
