package bedwars;

/**
 * Bed Wars 队伍（8 色）。与 Arcveil 源码 bedwars::Team 的取值顺序一一对应，
 * index() 与 Sidebar 的位掩码顺序必须保持一致。
 *
 * 纯逻辑类，无任何 Minecraft / UI 依赖。
 */
public enum Team {
    UNKNOWN('u'),
    RED('c'),
    BLUE('9'),
    GREEN('a'),
    YELLOW('e'),
    AQUA('b'),
    WHITE('f'),
    PINK('d'),
    GRAY('7');

    private final char code;

    Team(char code) {
        this.code = code;
    }

    /** § 格式码（Unknown 返回 'u'）。 */
    public char code() {
        return code;
    }

    /** 0..7；UNKNOWN 返回 -1。 */
    public int index() {
        return this == UNKNOWN ? -1 : ordinal() - 1;
    }

    public boolean known() {
        return this != UNKNOWN;
    }

    public static Team fromIndex(int index) {
        return index < 0 || index > 7 ? UNKNOWN : values()[index + 1];
    }

    public static Team fromFormatCode(char code) {
        switch (Character.toLowerCase(code)) {
            case 'c': return RED;
            case '9': return BLUE;
            case 'a': return GREEN;
            case 'e': return YELLOW;
            case 'b': return AQUA;
            case 'f': return WHITE;
            case 'd': return PINK;
            case '7': return GRAY;
            default: return UNKNOWN;
        }
    }

    /** 羊毛方块 metadata -> 队伍。 */
    public static Team fromWoolMetadata(int metadata) {
        switch (metadata & 0x0F) {
            case 14: return RED;
            case 11: return BLUE;
            case 13: return GREEN;
            case 4: return YELLOW;
            case 9: return AQUA;
            case 0: return WHITE;
            case 6: return PINK;
            case 7: return GRAY;
            default: return UNKNOWN;
        }
    }

    /** 皮革胸甲 RGB（0xRRGGBB）-> 队伍，规则与源码 fromLeatherRgb 完全一致。 */
    public static Team fromLeatherRgb(int rgb) {
        final int red = (rgb >> 16) & 0xFF;
        final int green = (rgb >> 8) & 0xFF;
        final int blue = rgb & 0xFF;
        if (red > green * 2 && red > blue * 2) return RED;
        if (blue * 2 > red * 3 && blue * 2 > green * 3) return BLUE;
        if (green * 2 > red * 3 && green * 2 > blue * 3) return GREEN;
        if (red > blue * 2 && green > blue * 2 && red > 150 && green > 150) return YELLOW;
        if (green * 2 > red * 3 && blue * 2 > red * 3 && green > 100 && blue > 100) return AQUA;
        if (red > 200 && green > 200 && blue > 200) return WHITE;
        if (red > 150 && blue > 150 && green < 150) return PINK;
        if (red < 100 && green < 100 && blue < 100) return GRAY;
        return UNKNOWN;
    }
}
