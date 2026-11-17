package fpsport.utility;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 聊天框增强（BetterChat）。
 *
 * <p>两个独立能力：
 * <ul>
 *   <li>折叠重复消息：相同文本合并成一条并追加 {@code [x2]}；</li>
 *   <li>复制按钮：在消息尾部追加 {@code [C]}，点击时把原文写进剪贴板。</li>
 * </ul>
 *
 * <p>**没有任何 UI 代码**：不画聊天框、不换字体、不改背景。宿主 coremod 在
 * {@code NetHandlerPlayClient#handleChat} 与 {@code GuiScreen#handleComponentClick}
 * 头部调用本类。
 */
public class BetterChat extends Feature {

    /** 复制标记前缀，与原客户端保持一致（\u0000 保证玩家无法手输伪造）。 */
    public static final String COPY_PREFIX = "\u0000#COPY";

    /** 注入读取的开关标志。 */
    public static boolean using = false;
    /** 折叠重复消息。 */
    public static boolean foldMessage = false;
    /** 显示复制按钮。 */
    public static boolean copyMessage = false;
    /** 追加 [C] 的最小字符数（去空格后的长度，默认 3）。 */
    public static int minCopyLength = 3;
    /** 动作栏消息（packet type 2）是否也参与折叠。 */
    public static boolean foldActionBar = false;
    /** 聊天整体不透明度（0 ~ 100），由宿主设置页写入；100 = 与原版完全一致。 */
    public static int opacity = 100;

    /** 平滑聊天：消息逐条淡入 + 滚轮按帧吸附（独立开关，不跟随 using）。 */
    public static boolean smoothChat = false;
    /** 聊天头像：每条玩家消息左侧画 8×8 头像（独立开关，不跟随 using）。 */
    public static boolean chatAvatars = false;

    /** 单条消息淡入时长（毫秒） */
    private static final int FADE_MS = 180;
    /** 同一 tick 内多条消息之间的错峰间隔（毫秒） */
    private static final int STAGGER_MS = 45;
    /** 滚轮分帧：每一步之间的最小间隔 */
    private static final int SCROLL_STEP_MS = 22;
    /** 头像尺寸与文字间距（都是聊天坐标系里的像素，随聊天缩放一起缩放） */
    public static final int AVATAR_SIZE = 8;
    public static final int AVATAR_GAP = 2;
    /** 缓存上限：聊天只保留 100 行，留够冗余后整体清空，避免无限增长 */
    private static final int CACHE_LIMIT = 512;
    /** 自定义头像文件体积上限（2 MB）与像素数上限（4096×4096），防止异常图片拖垮渲染线程 */
    private static final long MAX_AVATAR_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_AVATAR_PIXELS = 4096L * 4096L;
    /** 头像停在默认皮肤占位时，多久重新解析一次真实皮肤 */
    private static final long PROVISIONAL_RETRY_MS = 500L;

    /** ChatLine → 淡入开始时间（0 表示这条不做动画）；按对象身份区分 */
    private static final IdentityHashMap<ChatLine, Long> fadeStart = new IdentityHashMap<ChatLine, Long>();
    /** ChatLine → 头像解析结果（连名字都解析不到时 name 为 null，代表这条不是玩家发言） */
    private static final IdentityHashMap<ChatLine, Avatar> lineAvatars = new IdentityHashMap<ChatLine, Avatar>();
    /** 小写玩家名 → 自定义头像贴图 */
    private static final Map<String, ResourceLocation> customAvatars = new HashMap<String, ResourceLocation>();
    /** 已经尝试加载过的自定义头像名（加载失败不再反复读盘） */
    private static final Set<String> customAvatarTried = new HashSet<String>();

    private static ChatLine currentLine;
    private static int frameCounter;
    private static int lastFadeCounter = Integer.MIN_VALUE;
    private static int fadeBatch;
    private static int pendingScroll;
    private static long lastScrollStepMs;

    private static Field scrollPosField;
    private static Field isScrolledField;

    /** 静态：transformer 以 INVOKESTATIC 调用 {@link #onChatPacket}，状态必须跟着静态化。 */
    private static IChatComponent lastMessage;
    private static int counter = 1;

    private static Field chatLinesField;
    private static Field drawnChatLinesField;

    public BetterChat() {
        super("BetterChat");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
        reset();
    }

    public static void reset() {
        lastMessage = null;
        counter = 1;
        clearLineCaches();
        currentLine = null;
        pendingScroll = 0;
        lastScrollStepMs = 0L;
        lastFadeCounter = Integer.MIN_VALUE;
        fadeBatch = 0;
    }

    /**
     * 平滑聊天开关：关闭时把还没走完的滚轮步进一次性走完，
     * 避免用户刚滚完就关开关导致聊天停在两个位置之间。
     */
    public static void setSmoothChat(boolean value) {
        if (smoothChat == value) {
            return;
        }
        smoothChat = value;
        if (!value) {
            flushScroll();
        }
    }

    /**
     * 头像开关。返回 true 表示状态真的变了，宿主应刷新聊天（重新按新宽度折行）。
     */
    public static boolean setChatAvatars(boolean value) {
        if (chatAvatars == value) {
            return false;
        }
        chatAvatars = value;
        clearLineCaches();
        return true;
    }

    /** 聊天行被重建（刷新 / 换世界）后，旧的 ChatLine 已经不再绘制，缓存整体丢掉。 */
    public static void clearLineCaches() {
        fadeStart.clear();
        lineAvatars.clear();
    }

    /**
     * 聊天透明度：把原版 {@code GuiNewChat#drawChat} 里背景与文字的颜色 alpha 按百分比缩放。
     *
     * <p>由 coremod 在每次绘制前以 {@code INVOKESTATIC} 调用；100% 或功能关闭时原样返回，
     * 保证默认设置下与原版像素级一致。RGB 三通道完全不动。
     */
    public static int alpha(int color) {
        int result = color;
        if (using && opacity < 100) {
            int percent = opacity;
            if (percent <= 0) {
                result = result & 0xFFFFFF;
            } else {
                int a = (result >>> 24) & 0xFF;
                if (a != 0) {
                    result = (((a * percent) / 100) << 24) | (result & 0xFFFFFF);
                }
            }
        }
        // 平滑聊天的逐条淡入：透明度按当前行的淡入因子再缩一次
        float fade = fadeOf(currentLine);
        if (fade < 1f) {
            int a = (result >>> 24) & 0xFF;
            a = (int) (a * fade);
            result = a <= 0 ? (result & 0xFFFFFF) : ((a << 24) | (result & 0xFFFFFF));
        }
        return result;
    }

    // ---------------------------------------------------------------- 平滑聊天

    /** drawChat 入口：记录本帧的 updateCounter，并推进滚轮分帧。 */
    public static void beginFrame(int updateCounter) {
        frameCounter = updateCounter;
        currentLine = null;
        if (smoothChat) {
            stepScroll(System.currentTimeMillis());
        }
    }

    /**
     * 每条聊天行开始绘制前调用：记录当前行，并在第一次见到它时分配淡入时间。
     *
     * <p>同一 tick 里到达的多条消息按 45ms 错峰，形成「逐条平滑出现」的效果；
     * 早于 2 tick 的旧消息（例如刚打开开关时的历史记录）不做动画，避免整屏一起闪。
     */
    public static void beginLine(ChatLine line) {
        currentLine = line;
        if (!smoothChat || line == null || fadeStart.containsKey(line)) {
            return;
        }
        int age = frameCounter - line.getUpdatedCounter();
        long start = 0L;
        if (age <= 2) {
            if (frameCounter != lastFadeCounter) {
                lastFadeCounter = frameCounter;
                fadeBatch = 0;
            }
            start = System.currentTimeMillis() + ChatSmoothLogic.staggerDelay(fadeBatch, STAGGER_MS);
            fadeBatch++;
        }
        if (fadeStart.size() >= CACHE_LIMIT) {
            fadeStart.clear();
        }
        fadeStart.put(line, Long.valueOf(start));
    }

    /** 当前行的淡入系数（1 = 完全不透明）。 */
    private static float fadeOf(ChatLine line) {
        if (!smoothChat || line == null) {
            return 1f;
        }
        Long start = fadeStart.get(line);
        if (start == null || start.longValue() == 0L) {
            return 1f;
        }
        return ChatSmoothLogic.fadeFactor(System.currentTimeMillis() - start.longValue(), FADE_MS);
    }

    /**
     * 文字起始 x：只有这条消息真的画得出头像时才右移。
     *
     * <p>以前是无条件按 {@code chatAvatars} 右移，而 {@link #drawAvatar} 在解析不到
     * 发言玩家 / 皮肤时直接返回 —— 于是「占位留了、头像却是空的」。现在缩进与绘制
     * 共用同一份解析结果，解析不到头像就完全不占位。
     */
    public static float textX(float x, ChatLine line) {
        if (!chatAvatars || line == null) {
            return x;
        }
        Avatar avatar = avatarOf(line);
        return avatar == null || avatar.texture == null ? x : x + AVATAR_SIZE + AVATAR_GAP;
    }

    /** 折行宽度：开启头像时先扣掉头像占位，长消息不会顶出聊天框。 */
    public static int wrapWidth(int width) {
        if (!chatAvatars) {
            return width;
        }
        return Math.max(AVATAR_SIZE * 2, width - AVATAR_SIZE - AVATAR_GAP);
    }

    /**
     * 滚轮：接管原版 {@code GuiNewChat#scroll}。
     *
     * <p>返回 true 表示本次已经被接管，原版方法直接 return；累计的行数会在
     * {@link #beginFrame} 里按 22ms 一步慢慢走完，形成平滑吸附而不是一次跳 7 行。
     */
    public static boolean onScroll(int amount) {
        if (!smoothChat || amount == 0) {
            return false;
        }
        pendingScroll += amount;
        // 0 表示「下一步立刻执行」
        lastScrollStepMs = 0L;
        return true;
    }

    /** 重置滚动时清掉还没走完的步进（原版会直接把 scrollPos 归零）。 */
    public static void onResetScroll() {
        pendingScroll = 0;
        lastScrollStepMs = 0L;
    }

    private static void stepScroll(long now) {
        int step = ChatSmoothLogic.scrollStep(pendingScroll, now, lastScrollStepMs, SCROLL_STEP_MS);
        if (step == 0) {
            return;
        }
        pendingScroll -= step;
        lastScrollStepMs = now;
        Minecraft mc = Minecraft.getMinecraft();
        GuiNewChat chat = mc.ingameGUI == null ? null : mc.ingameGUI.getChatGUI();
        if (chat != null) {
            applyScroll(chat, step);
        }
    }

    private static void flushScroll() {
        if (pendingScroll == 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        GuiNewChat chat = mc.ingameGUI == null ? null : mc.ingameGUI.getChatGUI();
        if (chat != null) {
            applyScroll(chat, pendingScroll);
        }
        pendingScroll = 0;
        lastScrollStepMs = 0L;
    }

    /** 复刻原版 scroll(int) 的边界处理，只是不一次跳完。 */
    private static void applyScroll(GuiNewChat chat, int delta) {
        try {
            Field field = scrollPosFieldOf();
            if (field == null) {
                return;
            }
            List<ChatLine> drawn = drawnChatLines(chat);
            int size = drawn == null ? 0 : drawn.size();
            int pos = field.getInt(chat) + delta;
            int max = size - chat.getLineCount();
            if (pos > max) {
                pos = max;
            }
            if (pos <= 0) {
                pos = 0;
                Field scrolled = isScrolledField();
                if (scrolled != null) {
                    scrolled.setBoolean(chat, false);
                }
            }
            field.setInt(chat, pos);
        } catch (Throwable ignored) {
            // 反射失败就放弃这一步，聊天本身仍然可用
        }
    }

    // ---------------------------------------------------------------- 聊天头像

    /**
     * 绘制一条聊天行左侧的头像。由 coremod 在聊天文字绘制前调用。
     *
     * @param line  当前行
     * @param lineY 当前行的 y 坐标（原版局部坐标，文字画在 lineY-8）
     */
    public static void drawAvatar(ChatLine line, int lineY) {
        if (!chatAvatars || line == null) {
            return;
        }
        Avatar avatar = avatarOf(line);
        if (avatar == null || avatar.texture == null) {
            return;
        }
        float alpha = fadeOf(line);
        if (using && opacity < 100) {
            alpha *= Math.max(0, opacity) / 100f;
        }
        if (alpha <= 0.02f) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int top = lineY - AVATAR_SIZE;
        try {
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.color(1f, 1f, 1f, alpha);
            mc.getTextureManager().bindTexture(avatar.texture);
            if (avatar.fullImage) {
                Gui.drawModalRectWithCustomSizedTexture(0, top, 0f, 0f, AVATAR_SIZE, AVATAR_SIZE, AVATAR_SIZE, AVATAR_SIZE);
            } else {
                // 与原版 TAB 列表画头像的参数完全一致：64×64 皮肤图里取 (8,8) 起的 8×8 脸部
                Gui.drawScaledCustomSizeModalRect(0, top, 8f, 8f, 8, 8, AVATAR_SIZE, AVATAR_SIZE, 64f, 64f);
            }
        } catch (Throwable t) {
            // 降级兜底：贴图绑定/绘制抛异常时画一个半透明灰块，绝不留下空洞占位
            try {
                GlStateManager.color(1f, 1f, 1f, 1f);
                Gui.drawRect(0, top, AVATAR_SIZE, top + AVATAR_SIZE, 0x80202020);
            } catch (Throwable ignored) {
                // 连底色都画不了就彻底放弃这一条，不影响整段聊天
            }
        } finally {
            // 回到注入点时的状态：Gui.drawRect 结束时贴图是开着的、混合是关着的。
            // 这里绝对不能 disableTexture2D —— 紧随其后的 drawStringWithShadow 不会自己
            // 重新开启贴图，关掉的话整行文字会变成没有字形、只有色块的矩形。
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
        }
    }

    /**
     * 一条消息的头像解析结果。
     *
     * <p>{@code texture} 有三种来源，越靠后越保底：
     * 自定义头像文件 → 玩家真实皮肤 → 原版 DefaultPlayerSkin（Steve/Alex，作为加载失败占位）。
     * 拿到默认皮肤时 {@link #provisional} 为 true，后续帧继续尝试换成真实皮肤。
     */
    private static final class Avatar {
        /** null 表示这条消息不是玩家发言（系统消息等），永远不会有头像 */
        final String name;
        ResourceLocation texture;
        /** true = 自定义头像（整张图铺满），false = 玩家皮肤（只取脸部 8×8） */
        boolean fullImage;
        /** true = 现在这张只是默认皮肤占位，真实皮肤到了要替换 */
        boolean provisional;
        /** 占位状态下下次重试的时间戳，避免每帧都去扫一遍玩家列表 */
        long nextRetryMs;

        Avatar(String name) {
            this.name = name;
        }
    }

    /**
     * 解析这条消息左侧该画谁的头像，结果按行缓存。
     *
     * <p>缩进判定（{@link #textX}）和实际绘制（{@link #drawAvatar}）都必须走这一个入口，
     * 否则会出现「留了占位没画头像」或「画了头像但文字压在头像上」。
     */
    private static Avatar avatarOf(ChatLine line) {
        if (!chatAvatars || line == null) {
            return null;
        }
        Avatar avatar = lineAvatars.get(line);
        if (avatar != null) {
            // name 为空（非玩家消息）或已经是终态时直接返回；只有「名字有了但贴图还是占位 /
            // 还没解析出来」才重试，避免进场初期到达的消息永久停在默认皮肤上。
            if (avatar.name == null || (avatar.texture != null && !avatar.provisional)) {
                return avatar;
            }
            resolveTexture(avatar);
            return avatar;
        }
        avatar = new Avatar(avatarOwner(line));
        if (avatar.name != null) {
            resolveTexture(avatar);
        }
        if (lineAvatars.size() >= CACHE_LIMIT) {
            lineAvatars.clear();
        }
        lineAvatars.put(line, avatar);
        return avatar;
    }

    /**
     * 给已知名字的行走一遍贴图解析：自定义头像 → 在线玩家皮肤 → TAB 名单皮肤。
     *
     * <p>1.8.9 的 {@code getLocationSkin()} 不会返回 null：真实皮肤没下载完时它返回
     * {@link net.minecraft.client.resources.DefaultPlayerSkin}（Steve/Alex）。
     * 正好用它当兜底占位，同时标记 provisional 继续等真实皮肤。
     */
    private static void resolveTexture(Avatar avatar) {
        if (avatar == null || avatar.name == null) {
            return;
        }
        if (avatar.texture != null && !avatar.provisional) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < avatar.nextRetryMs) {
            // 刚解析过：有占位就继续显示占位，别再每帧扫玩家列表
            return;
        }
        avatar.nextRetryMs = now + PROVISIONAL_RETRY_MS;
        try {
            ResourceLocation custom = customAvatar(avatar.name);
            if (custom != null) {
                avatar.texture = custom;
                avatar.fullImage = true;
                avatar.provisional = false;
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            NetworkPlayerInfo info = playerInfo(mc, avatar.name);
            AbstractClientPlayer player = findPlayer(mc, avatar.name);
            ResourceLocation skin = player == null ? null : player.getLocationSkin();
            if (skin == null && info != null) {
                // 大厅/群组服里发言者常常不在渲染距离内，world.playerEntities 里根本找不到；
                // TAB 名单里有他的档案（含未下载完时的默认皮肤）。
                skin = info.getLocationSkin();
            }
            if (skin != null) {
                avatar.texture = skin;
                avatar.fullImage = false;
                avatar.provisional = info == null || !info.hasLocationSkin();
                if (avatar.provisional) {
                    avatar.nextRetryMs = now + PROVISIONAL_RETRY_MS;
                }
            }
        } catch (Throwable ignored) {
            // 解析失败维持现状：有占位就继续显示占位，下一帧再试
        }
    }

    /** 从 TAB（玩家列表）里取档案；拿不到就返回 null。 */
    private static NetworkPlayerInfo playerInfo(Minecraft mc, String name) {
        try {
            NetHandlerPlayClient handler = mc.getNetHandler();
            return handler == null ? null : handler.getPlayerInfo(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 解析这条消息的发言玩家名；解析不到返回 null。结果由 {@link #avatarOf} 整体缓存。 */
    private static String avatarOwner(ChatLine line) {
        try {
            IChatComponent component = line.getChatComponent();
            Minecraft mc = Minecraft.getMinecraft();
            World world = mc.theWorld;
            if (component == null || world == null) {
                return null;
            }
            String text = component.getUnformattedText();
            List<String> names = new ArrayList<String>();
            for (EntityPlayer player : world.playerEntities) {
                if (player == null) {
                    continue;
                }
                String playerName = player.getName();
                if (playerName != null && !playerName.isEmpty()) {
                    names.add(playerName);
                }
            }
            if (mc.thePlayer != null) {
                String self = mc.thePlayer.getName();
                if (self != null && !self.isEmpty()) {
                    names.add(self);
                }
            }
            // TAB 名单也参与匹配：这样即使发言者不在渲染距离内，也能认出名字并走皮肤兜底
            NetHandlerPlayClient handler = mc.getNetHandler();
            if (handler != null) {
                for (NetworkPlayerInfo info : handler.getPlayerInfoMap()) {
                    if (info == null || info.getGameProfile() == null) {
                        continue;
                    }
                    String tabName = info.getGameProfile().getName();
                    if (tabName != null && !tabName.isEmpty()) {
                        names.add(tabName);
                    }
                }
            }
            return ChatSmoothLogic.matchPlayerName(text, names);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 按名字找在线玩家（含自己）；找不到返回 null。 */
    private static AbstractClientPlayer findPlayer(Minecraft mc, String name) {
        World world = mc.theWorld;
        if (world != null) {
            for (EntityPlayer player : world.playerEntities) {
                if (player instanceof AbstractClientPlayer && player.getName() != null
                        && player.getName().equalsIgnoreCase(name)) {
                    return (AbstractClientPlayer) player;
                }
            }
        }
        if (mc.thePlayer != null && mc.thePlayer.getName() != null
                && mc.thePlayer.getName().equalsIgnoreCase(name)) {
            return mc.thePlayer;
        }
        return null;
    }

    /**
     * 自定义头像：{@code config/Music PVP/avatars/<玩家名>.png}，整张图缩放到 8×8。
     * 没有文件时返回 null，调用方回落到玩家皮肤。
     */
    private static ResourceLocation customAvatar(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (customAvatars.containsKey(key)) {
            return customAvatars.get(key);
        }
        if (!customAvatarTried.add(key)) {
            return null;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            File dir = new File(new File(new File(mc.mcDataDir, "config"), "Music PVP"), "avatars");
            File file = new File(dir, safeFileName(name) + ".png");
            // 资源合法性校验：必须是普通文件，且体积/像素数在可安全解码的范围内，
            // 避免超大 PNG（解压炸弹）直接把渲染线程的内存打爆。
            if (!file.isFile() || file.length() <= 0L || file.length() > MAX_AVATAR_BYTES) {
                return null;
            }
            BufferedImage source = ImageIO.read(file);
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0
                    || (long) source.getWidth() * (long) source.getHeight() > MAX_AVATAR_PIXELS) {
                return null;
            }
            BufferedImage scaled = new BufferedImage(AVATAR_SIZE, AVATAR_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, AVATAR_SIZE, AVATAR_SIZE, null);
            graphics.dispose();
            ResourceLocation location = mc.getTextureManager().getDynamicTextureLocation(
                    "dangomusic/chat_avatar/" + key, new DynamicTexture(scaled));
            customAvatars.put(key, location);
            return location;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safeFileName(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            out.append(ok ? c : '_');
        }
        return out.toString();
    }

    private static Field scrollPosFieldOf() {
        try {
            if (scrollPosField == null) {
                scrollPosField = findField("field_146250_j", "scrollPos");
            }
            return scrollPosField;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field isScrolledField() {
        try {
            if (isScrolledField == null) {
                isScrolledField = findField("field_146251_k", "isScrolled");
            }
            return isScrolledField;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 在「收到聊天包」时调用（只在主线程、包即将进入聊天栏之前）。
     *
     * <p>必须是静态方法：coremod 在 {@code NetHandlerPlayClient#handleChat} 里以
     * {@code INVOKESTATIC} 调用它。
     *
     * @return {@code true} 表示这条消息已合并进上一条，宿主应当取消这个包，否则照常处理。
     */
    public static boolean onChatPacket(S02PacketChat packet) {
        if (!using || packet == null || (packet.getType() == 2 && !foldActionBar)) {
            return false;
        }
        IChatComponent component = packet.getChatComponent();
        if (component == null) {
            return false;
        }

        if (foldMessage) {
            GuiNewChat chat = Minecraft.getMinecraft().ingameGUI.getChatGUI();
            List<ChatLine> drawn = drawnChatLines(chat);
            List<ChatLine> lines = chatLines(chat);
            // 反射失败（宿主换了自己的聊天栏）时直接放行，绝不吞消息。
            if (drawn != null && lines != null && !drawn.isEmpty() && !lines.isEmpty()) {
                if (lastMessage != null && lastMessage.equals(component)) {
                    ChatLine base = drawn.get(0);
                    IChatComponent merged = lastMessage.createCopy()
                            .appendSibling(new ChatComponentText("\247r\247f [x" + (++counter) + "]"));
                    if (copyMessage) {
                        merged.appendSibling(copyBadge(component));
                    }
                    ChatLine replaced = new ChatLine(base.getUpdatedCounter(), merged, base.getChatLineID());
                    lines.set(0, replaced);
                    drawn.set(0, replaced);
                    return true;
                }
                lastMessage = component.createCopy();
                counter = 1;
            }
        }

        if (copyMessage && component.getUnformattedText().trim().length() >= minCopyLength) {
            component.appendSibling(copyBadge(component));
        }
        return false;
    }

    /**
     * 聊天点击回调：命中 {@code \u0000#COPY} 标记时把原文写进剪贴板。
     *
     * <p>由 coremod 在 {@code GuiScreen#handleComponentClick} 头部调用；返回 true 表示已处理，
     * 原方法应直接返回 true（**不会把标记当成命令发给服务器**）。
     */
    public static boolean handleClick(IChatComponent component) {
        if (!using || component == null) {
            return false;
        }
        try {
            ChatStyle style = component.getChatStyle();
            if (style == null) {
                return false;
            }
            ClickEvent event = style.getChatClickEvent();
            if (event == null || event.getAction() != ClickEvent.Action.RUN_COMMAND) {
                return false;
            }
            String target = copyTarget(event.getValue());
            if (target == null) {
                return false;
            }
            GuiScreen.setClipboardString(target);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 命中复制标记时返回要写入剪贴板的文本，否则返回 {@code null}。
     */
    public static String copyTarget(String command) {
        if (command == null || !command.startsWith(COPY_PREFIX)) {
            return null;
        }
        return command.substring(COPY_PREFIX.length());
    }

    private static IChatComponent copyBadge(IChatComponent source) {
        ChatComponentText badge = new ChatComponentText(" \247f[C]");
        badge.getChatStyle()
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, COPY_PREFIX + source.getUnformattedText()))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("点击复制这条消息")));
        return badge;
    }

    private static List<ChatLine> chatLines(GuiNewChat chat) {
        try {
            if (chatLinesField == null) {
                // 运行时是 srg 名，开发环境（去混淆）下才是 MCP 名
                chatLinesField = findField("field_146252_h", "chatLines");
            }
            return chatLinesField == null ? null : (List<ChatLine>) chatLinesField.get(chat);
        } catch (Throwable ignored) {
            chatLinesField = null;
            return null;
        }
    }

    private static List<ChatLine> drawnChatLines(GuiNewChat chat) {
        try {
            if (drawnChatLinesField == null) {
                drawnChatLinesField = findField("field_146253_i", "drawnChatLines");
            }
            return drawnChatLinesField == null ? null : (List<ChatLine>) drawnChatLinesField.get(chat);
        } catch (Throwable ignored) {
            drawnChatLinesField = null;
            return null;
        }
    }

    private static Field findField(String... names) {
        for (String name : names) {
            try {
                Field field = GuiNewChat.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                // 试下一个候选名
            }
        }
        return null;
    }
}
