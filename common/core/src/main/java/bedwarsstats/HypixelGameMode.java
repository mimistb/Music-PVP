package bedwarsstats;

/**
 * 由计分板侧栏标题推断当前所在的 Hypixel 游戏（纯数据）。
 *
 * <p>HyInfo 用 Hypixel Mod API 的服务器类型判断，本项目没有对应通道，
 * 改用原版计分板 displaySlot 1 的 objective 标题：Bed Wars 的标题是
 * {@code BED WARS}，SkyWars 是 {@code SKYWARS}，大厅是 {@code HYPIXEL}。
 */
public final class HypixelGameMode {

    public static final int UNKNOWN = 0;
    public static final int BEDWARS = 1;
    public static final int SKYWARS = 2;

    private HypixelGameMode() {
    }

    /** 输入可以是带 § 颜色码的原始标题；未知一律返回 {@link #UNKNOWN}。 */
    public static int fromScoreboard(String objectiveDisplayName) {
        final String title = normalize(objectiveDisplayName);
        if (title.isEmpty()) return UNKNOWN;
        if (title.contains("BED WARS") || title.contains("BEDWARS")) return BEDWARS;
        if (title.contains("SKY WARS") || title.contains("SKYWARS")) return SKYWARS;
        return UNKNOWN;
    }

    /** 去掉 § 颜色码并转大写，只保留可比较的纯文本。 */
    public static String normalize(String value) {
        if (value == null || value.isEmpty()) return "";
        final StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\u00a7') {
                i++;
                continue;
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }
}
