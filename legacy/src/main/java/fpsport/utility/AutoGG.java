package fpsport.utility;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.StringUtils;

/**
 * 自动 GG（可选自动开始下一局）。
 *
 * <p>触发来源：聊天栏关键词或标题关键词（可配置只看其中一个）。命中后延迟约 20 tick 发送，
 * 5 秒冷却。Hypixel 模式发送 {@code /ac <msg>}；普通模式直接发送。
 *
 * <p>与原版的差异：没有客户端通知（那是 UI），自动开始下一局的等待改用守护线程。
 */
public class AutoGG extends Feature {

    public static boolean autoPlay = false;
    /** 自动开始下一局前的等待秒数。 */
    public static int delay = 5;
    public static String message = "gg";
    /** hypixel / normal。 */
    public static String server = "hypixel";
    /** chat / title / both。 */
    public static String detection = "both";

    private static final String[] HYPIXEL_CHAT_TRIGGER = {
            "Reward Summary",
            "1st Killer",
            "Damage Dealt",
            "Winners:",
            "You died!",
            "奖励总览",
            "击杀数第一名",
            "造成伤害",
            "你死了！",
            "获胜者："
    };

    private static final String[] NORMAL_CHAT_TRIGGER = {
            "获胜者",
            "第一名杀手",
            "击杀第一名",
            "胜利",
            "恭喜"
    };

    private static final String[] VICTORY_KEYWORDS = {
            "VICTORY",
            "YOU WIN",
            "1ST KILLER",
            "WINS!",
            "WINNER",
            "VICTOR",
            "GAME OVER",
            "YOU DIED",
            "胜利",
            "获胜",
            "恭喜",
            "你赢了"
    };

    private static final long GG_COOLDOWN_MILLIS = 5000L;

    private volatile boolean ggPending = false;
    private volatile int ggTicks = 0;
    private boolean autoPlayPending = false;
    private String autoPlayCommand = null;
    private long lastGgMillis = 0;

    public AutoGG() {
        super("AutoGG");
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    /** 宿主在网络包回调里调用（对应源码的 EventPacket，只需处理收到的包）。 */
    public void onPacket(boolean receive, Object packet) {
        if (!receive) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }
        if (packet instanceof S02PacketChat) {
            handleChatPacket((S02PacketChat) packet);
        } else if (packet instanceof S45PacketTitle) {
            handleTitlePacket((S45PacketTitle) packet);
        }
    }

    private void handleChatPacket(S02PacketChat packet) {
        IChatComponent component = packet.getChatComponent();
        String raw = StringUtils.stripControlCodes(component.getUnformattedText());

        boolean matched = false;
        if ("normal".equals(server)) {
            for (String s : NORMAL_CHAT_TRIGGER) {
                if (raw.contains(s)) {
                    matched = true;
                    break;
                }
            }
        } else {
            for (String s : HYPIXEL_CHAT_TRIGGER) {
                if (raw.contains(s)) {
                    matched = true;
                    break;
                }
            }
        }

        if (matched && isDetectionEnabled("chat")) {
            scheduleGG();
        }

        if (autoPlay && "hypixel".equals(server)) {
            for (IChatComponent sibling : component.getSiblings()) {
                ClickEvent clickEvent = sibling.getChatStyle().getChatClickEvent();
                if (clickEvent != null
                        && clickEvent.getAction() == ClickEvent.Action.RUN_COMMAND
                        && clickEvent.getValue().trim().toLowerCase().startsWith("/play ")) {
                    scheduleAutoPlay(clickEvent.getValue());
                    break;
                }
            }
        }
    }

    private void handleTitlePacket(S45PacketTitle packet) {
        if (packet.getType() != S45PacketTitle.Type.TITLE
                && packet.getType() != S45PacketTitle.Type.SUBTITLE) {
            return;
        }
        IChatComponent titleComponent = packet.getMessage();
        if (titleComponent == null) {
            return;
        }
        String text = StringUtils.stripControlCodes(titleComponent.getUnformattedText()).toUpperCase().trim();
        if (text.isEmpty()) {
            return;
        }
        for (String keyword : VICTORY_KEYWORDS) {
            if (text.contains(keyword)) {
                if (isDetectionEnabled("title")) {
                    scheduleGG();
                }
                break;
            }
        }
    }

    private boolean isDetectionEnabled(String source) {
        if ("chat".equals(detection)) {
            return source.equals("chat");
        }
        if ("title".equals(detection)) {
            return source.equals("title");
        }
        return true;
    }

    private void scheduleGG() {
        long now = System.currentTimeMillis();
        if (now - lastGgMillis < GG_COOLDOWN_MILLIS) {
            return;
        }
        if (ggPending) {
            return;
        }
        lastGgMillis = now;
        ggPending = true;
        ggTicks = 20;
    }

    private void scheduleAutoPlay(String command) {
        if (autoPlayPending) {
            return;
        }
        autoPlayPending = true;
        autoPlayCommand = command;
        int delaySec = Math.max(0, delay);
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delaySec * 1000L);
                String cmd = autoPlayCommand;
                Minecraft mc = Minecraft.getMinecraft();
                if (cmd != null && mc.thePlayer != null) {
                    mc.thePlayer.sendChatMessage(cmd);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "AutoGG-AutoPlay");
        thread.setDaemon(true);
        thread.start();
    }

    /** 宿主每 tick 调用一次（对应源码的 EventTick）。 */
    public void onTick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            resetState();
            return;
        }
        if (ggPending) {
            if (--ggTicks <= 0) {
                String msg = message;
                mc.thePlayer.sendChatMessage("hypixel".equals(server) ? "/ac " + msg : msg);
                ggPending = false;
            }
        }
    }

    private void resetState() {
        ggPending = false;
        ggTicks = 0;
        autoPlayPending = false;
        autoPlayCommand = null;
    }
}
