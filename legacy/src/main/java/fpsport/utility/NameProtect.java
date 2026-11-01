package fpsport.utility;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 名字保护：把自己名字的所有出现替换成占位文本（录制 / 截图 / 直播用）。
 *
 * <p>没有 Mixin：宿主在自己的文本入口（聊天、Tab、nametag、计分板、输入框）调用
 * {@link #filter(String)} 即可。{@link #onTick()} 负责在名字或替换文本变化时重新编译正则。
 */
public class NameProtect extends Feature {

    private static boolean using = false;
    private static volatile String playerName = "";
    private static volatile String replacement = "";
    /** 预编译一次，避免在每帧文本热路径上反复 compile。 */
    private static volatile Pattern namePattern = null;
    private static volatile String quotedReplacement = "";

    /** 替换文本，支持 {@code &} 颜色码。 */
    public static String name = "Hide";

    public NameProtect() {
        super("NameProtect");
    }

    @Override
    protected void onEnable() {
        using = true;
    }

    @Override
    protected void onDisable() {
        using = false;
    }

    /** 宿主每 tick 调用一次（对应源码的 EventTick）。 */
    public void onTick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }
        String newName = mc.thePlayer.getName();
        String newReplacement = name == null ? "" : name.replace("&", "\u00a7");
        if (!newName.equals(playerName) || !newReplacement.equals(replacement)) {
            playerName = newName;
            replacement = newReplacement;
            quotedReplacement = Matcher.quoteReplacement(newReplacement);
            namePattern = (newName == null || newName.isEmpty())
                    ? null : Pattern.compile(Pattern.quote(newName));
        }
    }

    public static String filter(String s) {
        if (!using || s == null) {
            return s;
        }
        Pattern pattern = namePattern;
        String target = playerName;
        if (pattern == null || target == null || target.isEmpty()) {
            return s;
        }
        // 快速路径：绝大多数被渲染的字符串不含玩家名，直接跳过 Matcher 分配。
        if (s.indexOf(target) < 0) {
            return s;
        }
        return pattern.matcher(s).replaceAll(quotedReplacement);
    }
}
