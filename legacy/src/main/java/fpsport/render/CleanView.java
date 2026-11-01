package fpsport.render;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.DataWatcher;

/**
 * 清洁视角（CleanView）。
 *
 * <p>原版 {@code EntityLivingBase#updatePotionEffects} 末尾会读取 DataWatcher 里的药水颜色，
 * 颜色大于 0 时围绕实体生成粒子。药水计时逻辑在读取之前已全部跑完，因此对本地玩家把这个
 * 颜色值替换成 0，就只会跳过粒子生成，不会缩短药效。
 *
 * <p>本类无 UI；注入在 coremod 的 {@code CleanViewTransformer}。
 */
public class CleanView extends Feature {

    /** 注入读取的开关标志。 */
    public static boolean using = false;

    public CleanView() {
        super("CleanView");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
    }

    /**
     * 由 ASM 注入替换 {@code DataWatcher#getWatchableObjectInt(I)I} 调用。
     *
     * <p>栈形与原调用完全一致（DataWatcher + int 进、int 出），所以不需要插入分支/栈帧。
     * 只有本地玩家返回 0（跳过粒子），其它实体与关闭开关时原样返回真实值。
     * 读取失败时返回 0（只跳过粒子，不吞掉药效）。
     */
    public static int readPotionColor(DataWatcher watcher, int id, Object self) {
        int value;
        try {
            value = watcher.getWatchableObjectInt(id);
        } catch (Throwable t) {
            // 读不到颜色时按 0 处理：只跳过粒子，药效计时在调用点之前已经跑完
            return 0;
        }
        try {
            if (using && self == Minecraft.getMinecraft().thePlayer) {
                return 0;
            }
        } catch (Throwable ignored) {
            // 客户端还没准备好时照常返回真实值
        }
        return value;
    }
}
