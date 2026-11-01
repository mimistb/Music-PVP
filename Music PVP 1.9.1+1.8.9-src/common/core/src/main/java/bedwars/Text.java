package bedwars;

import java.util.List;

/**
 * Bed Wars 文本解析：Sidebar 队伍行、YOU 判定、TAB 名单队伍标记。
 * 逐行等价移植自 Arcveil 的 BedWarsState.cpp（含 Lunar 等客户端插入的额外格式码兼容）。
 *
 * 注意：C++ 源码按 UTF-8 字节扫描，一个 "§c" 占 3 个字节，所以源码里前进 3、
 * 读取 offset+2；Java 的 String 是 UTF-16，一个 "§c" 只占 2 个 char，
 * 因此这里前进 2、读取 offset+1，行为完全等价。
 *
 * 输入字符串可直接使用 Minecraft 的 § 格式文本（Java 中 § 为 '\u00a7'）。
 * 纯逻辑类，无任何 Minecraft / UI 依赖。
 */
public final class Text {

    private Text() {
    }

    public static boolean isColourCode(char code) {
        final char lower = Character.toLowerCase(code);
        return (lower >= '0' && lower <= '9') || (lower >= 'a' && lower <= 'f');
    }

    /** 判断 offset 处是否为一个完整的 §x 格式码（其后至少还有一个字符）。 */
    private static boolean isSection(String text, int offset) {
        return offset + 2 < text.length() && text.charAt(offset) == '\u00a7';
    }

    private static Team markerTeam(char colour, char marker) {
        final char lowerColour = Character.toLowerCase(colour);
        final char upperMarker = Character.toUpperCase(marker);
        if (lowerColour == 'c' && upperMarker == 'R') return Team.RED;
        if (lowerColour == '9' && upperMarker == 'B') return Team.BLUE;
        if (lowerColour == 'a' && upperMarker == 'G') return Team.GREEN;
        if (lowerColour == 'e' && upperMarker == 'Y') return Team.YELLOW;
        if (lowerColour == 'b' && upperMarker == 'A') return Team.AQUA;
        if (lowerColour == 'f' && upperMarker == 'W') return Team.WHITE;
        if (lowerColour == 'd' && upperMarker == 'P') return Team.PINK;
        if (lowerColour == '7' && (upperMarker == 'S' || upperMarker == 'G')) return Team.GRAY;
        return Team.UNKNOWN;
    }

    private static Team rosterTagTeam(char colour, String tag) {
        if (tag == null || tag.isEmpty()) return Team.UNKNOWN;
        if (tag.length() == 1) {
            switch (Character.toUpperCase(tag.charAt(0))) {
                case 'R': return Team.RED;
                case 'B': return Team.BLUE;
                case 'G':
                    // 灰色队伍历史上用 S 表示，也接受“灰色前缀的 G”。
                    return Team.fromFormatCode(colour) == Team.GRAY ? Team.GRAY : Team.GREEN;
                case 'Y': return Team.YELLOW;
                case 'A': return Team.AQUA;
                case 'W': return Team.WHITE;
                case 'P': return Team.PINK;
                case 'S': return Team.GRAY;
                default: return Team.UNKNOWN;
            }
        }
        // 源码只接受长度 < 7 的队名缩写。
        if (tag.length() >= 7) return Team.UNKNOWN;
        final String upper = tag.toUpperCase();
        if (upper.equals("RED")) return Team.RED;
        if (upper.equals("BLUE")) return Team.BLUE;
        if (upper.equals("GREEN")) return Team.GREEN;
        if (upper.equals("YELLOW")) return Team.YELLOW;
        if (upper.equals("AQUA")) return Team.AQUA;
        if (upper.equals("WHITE")) return Team.WHITE;
        if (upper.equals("PINK")) return Team.PINK;
        if (upper.equals("GRAY") || upper.equals("GREY")) return Team.GRAY;
        return Team.UNKNOWN;
    }

    /** 去掉格式码后按单词边界匹配 YOU。 */
    public static boolean containsYouWord(String line) {
        if (line == null || line.isEmpty()) return false;
        final StringBuilder visible = new StringBuilder(line.length());
        for (int offset = 0; offset < line.length(); ) {
            if (isSection(line, offset)) {
                offset += 2;
                continue;
            }
            visible.append(Character.toUpperCase(line.charAt(offset)));
            offset++;
        }
        final String plain = visible.toString();
        int offset = plain.indexOf("YOU");
        while (offset >= 0) {
            final boolean leftBoundary = offset == 0 || !Character.isLetterOrDigit(plain.charAt(offset - 1));
            final int right = offset + 3;
            final boolean rightBoundary = right == plain.length() || !Character.isLetterOrDigit(plain.charAt(right));
            if (leftBoundary && rightBoundary) return true;
            offset = plain.indexOf("YOU", offset + 1);
        }
        return false;
    }

    /**
     * 解析 Sidebar 的一行：只有“前导格式码之后的第一个可见字符”才可能是队伍标记，
     * 避免把翻译文本或状态说明里的字母误判成队伍行。
     */
    public static Team parseSidebarRow(String line) {
        if (line == null || line.isEmpty()) return Team.UNKNOWN;
        char activeColour = '\0';
        for (int offset = 0; offset < line.length(); ) {
            if (isSection(line, offset)) {
                final char code = Character.toLowerCase(line.charAt(offset + 1));
                if (isColourCode(code)) activeColour = code;
                offset += 2;
                continue;
            }
            final char current = line.charAt(offset);
            if (Character.isWhitespace(current)) {
                offset++;
                continue;
            }
            return markerTeam(activeColour, current);
        }
        return Team.UNKNOWN;
    }

    /**
     * 解析整个 Sidebar。valid 条件：至少 2 个不同队伍，且“YOU”行恰好 1 行。
     */
    public static SidebarSnapshot parseSidebar(List<String> formattedLines) {
        final SidebarSnapshot result = new SidebarSnapshot();
        Team youTeam = Team.UNKNOWN;
        if (formattedLines != null) {
            for (String line : formattedLines) {
                final Team team = parseSidebarRow(line);
                if (team == Team.UNKNOWN) continue;
                final int index = team.index();
                if (index >= 0 && index < 8) result.teamsMask |= 1 << index;
                if (containsYouWord(line)) {
                    result.youRows++;
                    youTeam = team;
                }
            }
        }
        for (int mask = result.teamsMask; mask != 0; mask >>>= 1) {
            result.distinctTeams += mask & 1;
        }
        result.valid = result.distinctTeams >= 2 && result.youRows == 1;
        result.ownTeam = result.valid ? youTeam : Team.UNKNOWN;
        return result;
    }

    /** 只接受白名单内的显式队伍标签，如 §c[R] 或 [RED]。 */
    public static Team parseRosterTeamTag(String formattedName) {
        if (formattedName == null) return Team.UNKNOWN;
        char activeColour = '\0';
        for (int offset = 0; offset < formattedName.length(); offset++) {
            if (isSection(formattedName, offset)) {
                final char code = Character.toLowerCase(formattedName.charAt(offset + 1));
                if (isColourCode(code)) activeColour = code;
                offset += 1;
                continue;
            }
            if (formattedName.charAt(offset) != '[' || offset + 2 >= formattedName.length()) continue;
            final int close = formattedName.indexOf(']', offset + 1);
            if (close < 0 || close - offset < 2 || close - offset > 7) continue;
            final Team team = rosterTagTeam(activeColour, formattedName.substring(offset + 1, close));
            if (team.known()) return team;
        }
        return Team.UNKNOWN;
    }

    /** 先看显式标签，再看名字的第一个队伍颜色。 */
    public static Team parseRosterTeam(String formattedName) {
        final Team tagged = parseRosterTeamTag(formattedName);
        if (tagged.known()) return tagged;
        if (formattedName == null) return Team.UNKNOWN;
        for (int offset = 0; offset < formattedName.length(); offset++) {
            if (!isSection(formattedName, offset)) continue;
            final char code = Character.toLowerCase(formattedName.charAt(offset + 1));
            if (isColourCode(code)) return Team.fromFormatCode(code);
            offset += 1;
        }
        return Team.UNKNOWN;
    }

    /** 去掉所有 §x 格式码，供日志或宿主显示使用。 */
    public static String stripFormatting(String text) {
        if (text == null) return "";
        final StringBuilder out = new StringBuilder(text.length());
        for (int offset = 0; offset < text.length(); ) {
            if (isSection(text, offset)) {
                offset += 2;
                continue;
            }
            out.append(text.charAt(offset));
            offset++;
        }
        return out.toString();
    }
}
