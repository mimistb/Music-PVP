package bedwarsstats;

/**
 * Hypixel 星级文本（纯数据）。
 *
 * <p>从 HyInfo 的 {@code HypixelStatsFetcher.getBedwarsStars / getSkywarsStars}
 * 移植：每个档位一种配色，逐字符上色，首尾字符用混淆档的保留 {@code §k}。
 * 输出是带 § 颜色码的普通字符串，交给原版 FontRenderer 绘制。
 */
public final class StarText {

    private static final class Row {
        final int minimum;
        final char glyph;
        final boolean obfuscated;
        final String codes;

        Row(int minimum, char glyph, boolean obfuscated, String codes) {
            this.minimum = minimum;
            this.glyph = glyph;
            this.obfuscated = obfuscated;
            this.codes = codes;
        }
    }

    private static final Row[] BEDWARS_ROWS = {
            new Row(10000, '✥', false, "9bffffc4"),
            new Row(9900, '✥', false, "87fffef"),
            new Row(9800, '✥', false, "0888880"),
            new Row(9700, '✥', false, "ddeebe"),
            new Row(9600, '✥', false, "eee00e0"),
            new Row(9500, '✥', false, "008877f"),
            new Row(9400, '✥', false, "e648888"),
            new Row(9300, '✥', false, "f8888ff"),
            new Row(9200, '✥', false, "2dddda2"),
            new Row(9100, '✥', false, "0c66cc4"),
            new Row(9000, '✥', false, "ddddd58"),
            new Row(8900, '✥', false, "9bbb339"),
            new Row(8800, '✥', false, "444ccff"),
            new Row(8700, '✥', false, "8666668"),
            new Row(8600, '✥', false, "dffffed"),
            new Row(8500, '✥', false, "36666e3"),
            new Row(8400, '✥', false, "fdddaaf"),
            new Row(8300, '✥', false, "8844cc8"),
            new Row(8200, '✥', false, "fffffaf"),
            new Row(8100, '✥', false, "87fb391"),
            new Row(8000, '✥', false, "2aaac42"),
            new Row(7900, '✥', false, "6f262f6"),
            new Row(7800, '✥', false, "87fffe8"),
            new Row(7700, '✥', false, "dcccc6d"),
            new Row(7600, '✥', false, "fff77c8"),
            new Row(7500, '✥', false, "6622fff"),
            new Row(7400, '✥', false, "88888d8"),
            new Row(7300, '✥', false, "233bba2"),
            new Row(7200, '✥', false, "2af2af8"),
            new Row(7100, '✥', false, "4c6ec6e"),
            new Row(7000, '✥', false, "3bbbbf3"),
            new Row(6900, '✥', false, "aaaa228"),
            new Row(6800, '✥', false, "066eeff"),
            new Row(6700, '✥', false, "5ddddf5"),
            new Row(6600, '✥', false, "9ddddb9"),
            new Row(6500, '✥', false, "33aafa3"),
            new Row(6400, '✥', false, "bbcccaa"),
            new Row(6300, '✥', false, "aeeeea2"),
            new Row(6200, '✥', false, "efe66fe"),
            new Row(6100, '✥', false, "6efffb3"),
            new Row(6000, '✥', false, "cffffcf"),
            new Row(5900, '✥', false, "7087ff7"),
            new Row(5800, '✥', false, "5c6fb39"),
            new Row(5700, '✥', false, "4623958"),
            new Row(5600, '✥', false, "4cefec4"),
            new Row(5500, '✥', false, "2aefbd5"),
            new Row(5400, '✥', false, "3a282a3"),
            new Row(5300, '✥', false, "5defed5"),
            new Row(5200, '✥', false, "193bfe1"),
            new Row(5100, '✥', false, "4cc6ef4"),
            new Row(5000, '✥', false, "4459910"),
            new Row(4900, '✥', false, "2afffa2"),
            new Row(4800, '✥', false, "55c66b3"),
            new Row(4700, '✥', false, "f4cc919"),
            new Row(4600, '✥', false, "3bee6d5"),
            new Row(4500, '✥', false, "ffbb333"),
            new Row(4400, '✥', false, "22ae65d"),
            new Row(4300, '✥', false, "0588550"),
            new Row(4200, '✥', false, "193bf77"),
            new Row(4100, '✥', false, "ee6cdd5"),
            new Row(4000, '✥', false, "55cc66e"),
            new Row(3900, '✥', false, "ccaa399"),
            new Row(3800, '✥', false, "11955d1"),
            new Row(3700, '✥', false, "44ccb33"),
            new Row(3600, '✥', false, "aaab991"),
            new Row(3500, '✥', false, "cc442aa"),
            new Row(3400, '✥', false, "2add552"),
            new Row(3300, '✥', false, "999dcc4"),
            new Row(3200, '✥', false, "c4774cc"),
            new Row(3100, '✥', false, "993366e"),
            new Row(3000, '⚝', false, "ee66cc4"),
            new Row(2900, '⚝', false, "bb33991"),
            new Row(2800, '⚝', false, "aa2266e"),
            new Row(2700, '⚝', false, "eeff888"),
            new Row(2600, '⚝', false, "44ccdd5"),
            new Row(2500, '⚝', false, "77aa222"),
            new Row(2400, '⚝', false, "bbff778"),
            new Row(2300, '⚝', false, "55dd6ee"),
            new Row(2200, '⚝', false, "66ffb33"),
            new Row(2100, '⚝', false, "77ee666"),
            new Row(2000, '✪', false, "87ff778"),
            new Row(1900, '✪', false, "7555587"),
            new Row(1800, '✪', false, "7999917"),
            new Row(1700, '✪', false, "7dddd57"),
            new Row(1600, '✪', false, "7cccc47"),
            new Row(1500, '✪', false, "7333397"),
            new Row(1400, '✪', false, "7aaaa27"),
            new Row(1300, '✪', false, "7bbbb37"),
            new Row(1200, '✪', false, "7eeee67"),
            new Row(1100, '✪', false, "7ffff77"),
            new Row(1000, '✫', false, "c6eabd5"),
            new Row(900, '✫', false, "5"),
            new Row(800, '✫', false, "9"),
            new Row(700, '✫', false, "d"),
            new Row(600, '✫', false, "4"),
            new Row(500, '✫', false, "3"),
            new Row(400, '✫', false, "2"),
            new Row(300, '✫', false, "b"),
            new Row(200, '✫', false, "6"),
            new Row(100, '✫', false, "f"),
            new Row(0, '✫', false, "7"),
    };

    private static final Row[] SKYWARS_ROWS = {
            new Row(500, '✯', true, "bdc6ea"),
            new Row(490, '✯', false, "9bffc4"),
            new Row(480, '✯', false, "1193bf"),
            new Row(470, '✯', false, "087f78"),
            new Row(460, '✯', false, "9b3d44"),
            new Row(450, '✯', false, "44c6ef"),
            new Row(440, '✯', false, "a2aeff"),
            new Row(430, '✯', false, "66ffb3"),
            new Row(420, '✯', false, "057750"),
            new Row(410, '✯', false, "3bbbb3"),
            new Row(400, '✯', true, "abdc6e"),
            new Row(390, '✯', false, "2aaaa2"),
            new Row(380, '✯', false, "cfccfc"),
            new Row(370, '✯', false, "eeff88"),
            new Row(360, '✯', false, "13bfee"),
            new Row(350, '✯', false, "ffee66"),
            new Row(340, '✯', false, "babdaa"),
            new Row(330, '✯', false, "ecccce"),
            new Row(320, '✯', false, "daaaad"),
            new Row(310, '✯', false, "877778"),
            new Row(300, '✯', true, "eabdc6"),
            new Row(290, '✯', false, "9bbbb9"),
            new Row(280, '✯', false, "a2aea2"),
            new Row(270, '✯', false, "133331"),
            new Row(260, '✯', false, "0e66e0"),
            new Row(250, '✯', false, "c6ee6c"),
            new Row(240, '✯', false, "0"),
            new Row(230, '✯', false, "dbbbbd"),
            new Row(220, '✯', false, "8"),
            new Row(210, '✯', false, "5dddd5"),
            new Row(200, '✯', true, "6eadbc"),
            new Row(190, '✯', false, "4eeee4"),
            new Row(180, '✯', false, "3"),
            new Row(170, '✯', false, "199991"),
            new Row(160, '✯', false, "2"),
            new Row(150, '✯', false, "6eeee6"),
            new Row(140, '✯', false, "4"),
            new Row(130, '✯', false, "cffffc"),
            new Row(120, '✯', false, "1"),
            new Row(110, '✯', false, "4cccc4"),
            new Row(100, '✯', true, "c6eabd"),
            new Row(90, '✯', false, "a"),
            new Row(80, '✯', false, "e"),
            new Row(70, '✯', false, "1"),
            new Row(60, '✯', false, "5"),
            new Row(50, '✯', false, "d"),
            new Row(40, '✯', false, "c"),
            new Row(30, '✯', false, "b"),
            new Row(20, '✯', false, "6"),
            new Row(10, '✯', false, "f"),
            new Row(0, '✯', false, "7"),
    };

    private StarText() {
    }

    public static String bedwars(int stars) {
        return line(Math.max(0, stars), BEDWARS_ROWS);
    }

    public static String skywars(int stars) {
        return line(Math.max(0, stars), SKYWARS_ROWS);
    }

    private static String line(int stars, Row[] rows) {
        for (Row row : rows) {
            if (stars >= row.minimum) {
                return format("[" + stars + row.glyph + "]", row.obfuscated, row.codes);
            }
        }
        return "";
    }

    private static String format(String text, boolean obfuscated, String codes) {
        final StringBuilder sb = new StringBuilder(text.length() * 3);
        for (int i = 0; i < text.length(); i++) {
            final char code = codes.charAt(Math.min(i, codes.length() - 1));
            sb.append('\u00a7');
            if (obfuscated && (i == 0 || i == text.length() - 1)) sb.append('k');
            sb.append(code).append(text.charAt(i));
        }
        return sb.toString();
    }
}
