package fpsport.adnin.chat;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** 聊天数据模块的全部可调参数；键名对应 AdOverlay 配置里恢复出的键。 */
public final class ChatConfig {

    /** {@code chatOverlay}：模块总开关。 */
    public boolean chatOverlay = true;
    /** {@code ignoreTeammates}：忽略队友（与 {@code chatOutputTagsTeammates} 相反语义）。 */
    public boolean ignoreTeammates = true;
    /** {@code compactBlacklist}：黑名单玩家只输出紧凑行。 */
    public boolean compactBlacklist = true;

    /** {@code chatOverlayMinStars}：低于该星级不输出统计。默认 0 = 不过滤。 */
    public int minStars = 0;
    /** {@code chatOverlayMinFkdr}：低于该 FKDR 不输出床战统计。默认 0 = 不过滤。 */
    public double minFkdr = 0.0D;
    /** {@code chatOverlayMinSwKdr}：低于该 KDR 不输出空战统计。默认 0 = 不过滤。 */
    public double minSwKdr = 0.0D;

    /** 历史环形缓冲容量。INFERRED，默认 200。 */
    public int historyCapacity = 200;
    /** 单条消息最大长度，超出截断（防止恶意超长 chat 包撑爆缓冲）。INFERRED，默认 256。 */
    public int maxMessageLength = 256;
    /** 黑名单保留天数（对应界面上的 "{n} days ago"）。INFERRED，默认 30。 */
    public int blacklistRetainDays = 30;

    /** 是否剥掉消息末尾的计时器（对应恢复出的正则 {@code \s*\u00A7c\d:\d{2}\s*$}）。 */
    public boolean stripTrailingTimer = true;

    /** 脱敏占位符（对应 {@code c[NICK]} 与 {@code [NICK]} 文案里的 NICK）。 */
    public String nickMask = "NICK";

    /** 判定为“队友”的名单来源；由宿主注入，模块自己不做队伍解析。 */
    public TeamLookup teammates = new TeamLookup() {
        @Override
        public boolean isTeammate(String sender) {
            return false;
        }
    };

    public ChatConfig() {
    }

    public void validate() {
        if (historyCapacity < 1) {
            throw new IllegalArgumentException("historyCapacity must be >= 1");
        }
        if (maxMessageLength < 8) {
            throw new IllegalArgumentException("maxMessageLength must be >= 8");
        }
        if (blacklistRetainDays < 1) {
            throw new IllegalArgumentException("blacklistRetainDays must be >= 1");
        }
        if (minStars < 0 || Double.isNaN(minFkdr) || Double.isInfinite(minFkdr) || minFkdr < 0.0D
                || Double.isNaN(minSwKdr) || Double.isInfinite(minSwKdr) || minSwKdr < 0.0D) {
            throw new IllegalArgumentException("min thresholds must be >= 0");
        }
        if (nickMask == null || nickMask.isEmpty()) {
            throw new IllegalArgumentException("nickMask must not be empty");
        }
        if (teammates == null) {
            throw new IllegalArgumentException("teammates lookup must not be null");
        }
    }

    public ChatConfig copy() {
        ChatConfig c = new ChatConfig();
        c.chatOverlay = chatOverlay;
        c.ignoreTeammates = ignoreTeammates;
        c.compactBlacklist = compactBlacklist;
        c.minStars = minStars;
        c.minFkdr = minFkdr;
        c.minSwKdr = minSwKdr;
        c.historyCapacity = historyCapacity;
        c.maxMessageLength = maxMessageLength;
        c.blacklistRetainDays = blacklistRetainDays;
        c.stripTrailingTimer = stripTrailingTimer;
        // 宿主可能把这两个字段置空；拷贝时给安全默认值，validate() 再负责报错
        c.nickMask = nickMask == null ? "NICK" : nickMask;
        c.teammates = teammates;
        return c;
    }

    public Set<String> exportKeys() {
        return new LinkedHashSet<String>(Arrays.asList(
                "chatOverlay", "ignoreTeammates", "compactBlacklist",
                "chatOverlayMinStars", "chatOverlayMinFkdr", "chatOverlayMinSwKdr"));
    }

    /** 队伍查询接口，避免与宿主的队伍系统产生隐式依赖。 */
    public interface TeamLookup {
        boolean isTeammate(String sender);
    }
}
