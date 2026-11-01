package me.sorihiko.dango.core;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * 1.8.9 的 coremod 入口（jar 的 MANIFEST 里用 FMLCorePlugin 指向这里）。
 * 挂上若干 ASM transformer：受伤红闪颜色、三个「头部直接返回」补丁、液态玻璃启动界面，
 * 以及增强鱼竿的线宽注入（原工程的 MixinRenderItem 在这一侧改用 ASM 实现）。
 *
 * <p>SortingIndex 设为 2000：FML 自己的 DeobfuscationTransformer 由 sortIndex=1000 的
 * FMLDeobfTweaker 注册，比默认的 0 晚。这里必须排在它之后，才能拿到 srg 字段/方法名
 * （否则看到的是 a/b/c 这类混淆名，按名字找字段会全部失败）。
 */
@IFMLLoadingPlugin.SortingIndex(2000)
public final class DangoCorePlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[]{
                "me.sorihiko.dango.core.HitColorTransformer",
                "me.sorihiko.dango.core.FpsPortTransformer",
                "me.sorihiko.dango.core.BlockAnimationTransformer",
                "me.sorihiko.dango.core.SplashGlassTransformer",
                "me.sorihiko.dango.core.CleanViewTransformer",
                "me.sorihiko.dango.core.ItemPhysicsTransformer",
                "me.sorihiko.dango.core.BetterChatTransformer",
                "me.sorihiko.dango.core.FishingRodTransformer"
        };
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
