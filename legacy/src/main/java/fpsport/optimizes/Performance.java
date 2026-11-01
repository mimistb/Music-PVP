package fpsport.optimizes;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.Display;

/**
 * 性能模块（只保留开关与状态，不含实现）。
 *
 * <p>原始工程把 57 个开关背后的实现分散在 16 个 Mixin 里（区块更新限流、字体批量绘制、实体遮挡
 * 剔除、贴图上传、隐藏方块等）。这些实现会与宿主自己的优化栈（OptiFine / Patcher / 其它核心
 * mod）双重生效并互相打架，因此这里**不附带实现**，只交付：
 * <ul>
 *   <li>与源码一一对应的 public 配置字段，供宿主 UI 绑定；</li>
 *   <li>{@link #using} 总开关与 {@link #hidingBlocks} 派生标志；</li>
 *   <li>{@link #onClientTick()} 与 {@link #applyFpsLimit()} 两个可直接接入的钩子。</li>
 * </ul>
 * 每个开关对应的原版注入点见交付目录内的 {@code PORTING_PROMPT.md}。
 */
public class Performance extends Feature {

    /** 总开关：所有子优化都必须同时满足它和自身开关。 */
    public static boolean using = false;

    /** 六个「隐藏方块」开关中任意一个为真时为真，供区块重建线程读取。 */
    public static volatile boolean hidingBlocks = false;

    // ---- 渲染 / 帧率 ----
    public static boolean ignoreStands = false;
    public static boolean fastLoad = true;
    public static boolean staticParticleColor = false;
    public static boolean limitChunks = true;
    public static boolean batchModelRendering = true;
    public static boolean lowAnimationTick = false;
    public static boolean downscalePackIcons = true;
    public static boolean particleCulling = true;
    public static boolean cacheSkyColor = true;
    public static boolean entityCulling = true;
    public static boolean cacheArmorTextures = true;
    public static boolean signTextCulling = true;
    public static boolean blockEntityCulling = false;
    public static boolean customHudFont = false;
    public static boolean fastRender = false;
    public static boolean cullPlayers = false;
    public static boolean adaptiveChunkBudget = false;
    public static boolean fastCollision = false;
    public static boolean fastTextureUpload = true;
    public static boolean reuseVisibleChunks = true;
    public static boolean composedModelTransform = false;
    public static boolean batchVanillaFont = false;
    public static boolean fastGlyphLookup = true;
    public static boolean slowObfuscation = false;
    public static boolean mergeTextShadow = true;
    public static boolean cacheItemModels = false;

    // ---- 隐藏内容（默认全关，属于「减少画面」而非「同画面更快」）----
    public static boolean hideTallGrass = false;
    public static boolean hideDoubleTallGrass = false;
    public static boolean hideFlowers = false;
    public static boolean hideDoubleTallFlowers = false;
    public static boolean hideFences = false;
    public static boolean hideFenceGates = false;
    public static boolean hideSkulls = false;
    public static boolean hideArmorStands = false;
    public static boolean hideItemFrames = false;
    public static boolean hideMapsInItemFrames = false;
    public static boolean hideStuckArrows = false;
    public static boolean hideGroundArrows = false;
    public static boolean hideLavaParticles = false;
    public static boolean hideSpawnerParticles = false;
    public static boolean hideMobInSpawner = false;
    public static boolean hidePortalParticles = false;

    // ---- 数值 ----
    /** 方块实体渲染距离（格）。 */
    public static int blockEntityDistance = 32;
    /** 每帧允许的区块重建数量。0 会让区块线程空转，源码最小值为 1。 */
    public static int chunkUpdateLimit = 50;
    /** 窗口失焦时的帧率上限；0 = 不限制。 */
    public static int fpsLimit = 0;
    /** 粒子数量上限。 */
    public static int particlesLimit = 400;
    /** 实体可见性判定的复用时间（毫秒）。 */
    public static int entityCullingInterval = 50;
    /** 实体剔除的最低实体数，0 = 交给遮挡率自行判断。 */
    public static int entityCullingMinEntities = 0;
    /** 替换字体点阵高度。 */
    public static int customHudFontSize = 16;
    /** 各类实体的渲染距离系数（1.0 = 原版）。 */
    public static double playerRenderDistance = 1.0;
    public static double passiveRenderDistance = 1.0;
    public static double hostileRenderDistance = 1.0;
    public static double miscRenderDistance = 1.0;

    /** 贴图 mipmap 起点：0 = 原版，1 = Half，2 = Quarter，3 = Eighth，4 = Sixteenth。 */
    public static int textureResolution = 0;
    /** 可见区块列表复用等级：0 = Conservative，1 = Balanced，2 = Aggressive。 */
    public static int reuseVisibleChunksLevel = 0;

    public static final String[] TEXTURE_RESOLUTIONS = {"Default", "Half", "Quarter", "Eighth", "Sixteenth"};
    public static final String[] REUSE_LEVELS = {"Conservative", "Balanced", "Aggressive"};

    /** 设置变化后需要延迟到下一 tick 的世界刷新标记。 */
    private static boolean pendingWorldRefresh;

    // ---- 第二批：由宿主设置界面写入的渲染参数 ----
    /** 渲染距离（区块，0 = 跟随原版）。 */
    public static int renderDistance = 0;
    /** 实体渲染距离（格，0 = 不限），供 RenderLivingEvent 取消远处实体。 */
    public static int entityDistance = 0;
    /** true = 关闭实体阴影。 */
    public static boolean entityShadows = false;
    /** 玻璃质量：0 高 / 1 中 / 2 低。 */
    public static int glassQuality = 1;

    /** 上一次已应用的渲染距离，-1 表示从未应用。 */
    private static int appliedRenderDistance = -1;
    /** 上一次已应用的实体阴影开关。 */
    private static boolean appliedEntityShadows = false;
    /** 首次应用前记录的原版渲染距离，用于切回「跟随原版」时写回。 */
    private static int vanillaRenderDistance = -1;

    public Performance() {
        super("Performance");
    }

    @Override
    protected void onEnable() {
        using = true;
        pendingWorldRefresh = true;
    }

    @Override
    protected void onDisable() {
        using = false;
        pendingWorldRefresh = true;
        hidingBlocks = false;
    }

    /**
     * 由宿主每 tick 调用（源码里由 GlobalListener 驱动，因为模块自身的订阅会随开关停止）。
     *
     * <p>方块的隐藏状态是在区块网格里烘焙的，改了开关必须重载渲染器才能看到变化；而这个操作
     * 不能发生在 GUI 绘制的中途，所以延迟到 tick。
     */
    public static void onClientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        // 渲染距离 / 实体阴影只在模块开启时写回原版设置
        if (mc != null && using) {
            applySettings(mc);
        }
        if (!pendingWorldRefresh) {
            return;
        }
        pendingWorldRefresh = false;
        if (mc != null && hidingBlocks && mc.renderGlobal != null && mc.theWorld != null) {
            mc.renderGlobal.loadRenderers();
        }
    }

    /**
     * 把宿主设置里的渲染距离 / 实体阴影写回原版设置。
     *
     * <p>只在值真正变化时写，并记录首次应用前的原版渲染距离，
     * 这样「跟随原版」（renderDistance = 0）时能还原回去。
     */
    private static void applySettings(Minecraft mc) {
        if (mc.gameSettings == null) {
            return;
        }
        if (vanillaRenderDistance < 0) {
            vanillaRenderDistance = mc.gameSettings.renderDistanceChunks;
        }
        if (renderDistance != appliedRenderDistance) {
            appliedRenderDistance = renderDistance;
            if (renderDistance > 0) {
                mc.gameSettings.renderDistanceChunks = renderDistance;
                if (mc.renderGlobal != null) {
                    mc.renderGlobal.loadRenderers();
                }
            } else if (vanillaRenderDistance > 0) {
                mc.gameSettings.renderDistanceChunks = vanillaRenderDistance;
                if (mc.renderGlobal != null) {
                    mc.renderGlobal.loadRenderers();
                }
            }
        }
        if (entityShadows != appliedEntityShadows) {
            appliedEntityShadows = entityShadows;
            mc.gameSettings.entityShadows = !entityShadows;
        }
    }

    /** 六个隐藏方块开关变化后调用，重新计算 {@link #hidingBlocks} 并请求一次世界刷新。 */
    public static void refreshHidingBlocks() {
        hidingBlocks = hideTallGrass || hideDoubleTallGrass || hideFlowers || hideDoubleTallFlowers
                || hideFences || hideFenceGates;
        pendingWorldRefresh = true;
    }

    /**
     * 帧率上限。原版只在「玩家自己的帧率上限低于 Unlimited」时才调 {@code Display.sync}，
     * 宿主应在主循环里代替原版调用本方法（{@link #fpsLimit} 为 0 时本方法无副作用）。
     */
    public static void applyFpsLimit() {
        if (using && fpsLimit > 0) {
            Display.sync(fpsLimit);
        }
    }
}
