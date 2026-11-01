package fpsport.utility;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

import java.lang.reflect.Field;
import java.util.List;

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
