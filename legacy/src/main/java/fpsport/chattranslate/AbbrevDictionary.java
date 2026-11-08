package fpsport.chattranslate;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 玩家常用英文简易缩写规则库。
 *
 * <p>PVP 聊天里大量出现 gg / wp / ez / nt 这类缩写，直接丢给网络翻译往往会被当成
 * 无意义短词甚至音译成奇怪的词。这里先用本地规则把缩写展开成完整英文，
 * 再交给 Translator，命中率与译文可读性都会明显更好。
 *
 * <p>规则分三层，按优先级匹配：
 * <ol>
 *   <li>整句短语（如 "gg wp"、"ggs wp"），忽略大小写与首尾空白；</li>
 *   <li>整句单词（如 "gg"、"ez"），忽略大小写；</li>
 *   <li>逐词替换：保留原有的标点与大小写结构，只替换命中的词。</li>
 * </ol>
 * 没命中任何规则时返回 null，调用方继续走普通翻译。
 */
public final class AbbrevDictionary {

    /** 单个词（小写）→ 完整英文 */
    private static final Map<String, String> TOKENS = new HashMap<String, String>();
    /** 整句短语（小写、单词用单个空格分隔）→ 完整英文 */
    private static final Map<String, String> PHRASES = new HashMap<String, String>();

    static {
        // 对局礼仪
        put("gg", "good game");
        put("ggs", "good games");
        put("ggwp", "good game well played");
        put("wp", "well played");
        put("gl", "good luck");
        put("hf", "have fun");
        put("gf", "good fight");
        put("gr", "good round");
        put("wd", "well done");
        put("bg", "bad game");
        // 水平评价 / 情绪
        put("ez", "easy");
        put("nt", "nice try");
        put("lol", "laughing out loud");
        put("lmao", "laughing my ass off");
        put("xd", "laughing");
        put("jk", "just kidding");
        put("oof", "that hurts");
        put("rq", "rage quit");
        put("ff", "forfeit");
        put("smh", "shaking my head");
        put("tbh", "to be honest");
        put("ngl", "not going to lie");
        put("imo", "in my opinion");
        put("imho", "in my humble opinion");
        put("idc", "i do not care");
        put("idk", "i do not know");
        put("nvm", "never mind");
        put("btw", "by the way");
        put("ofc", "of course");
        put("fr", "for real");
        put("ig", "i guess");
        // 交流
        put("ty", "thank you");
        put("thx", "thanks");
        put("tyvm", "thank you very much");
        put("np", "no problem");
        put("yw", "you are welcome");
        put("sry", "sorry");
        put("pls", "please");
        put("plz", "please");
        put("brb", "be right back");
        put("omw", "on my way");
        put("afk", "away from keyboard");
        put("wb", "welcome back");
        put("gtg", "got to go");
        put("cya", "see you");
        put("dw", "do not worry");
        put("rn", "right now");
        put("inc", "incoming");
        put("m8", "mate");
        put("k", "okay");
        put("kk", "okay");
        // PVP 术语
        put("pvp", "player versus player");
        put("pve", "player versus environment");
        put("hp", "health");
        put("crit", "critical hit");
        put("combo", "combo");
        put("kdr", "kill death ratio");
        put("fkdr", "final kill death ratio");
        put("ws", "win streak");
        put("sw", "skywars");
        put("bw", "bedwars");
        put("duel", "duel");
        put("kb", "knockback");
        put("cps", "clicks per second");
        put("fps", "frames per second");
        put("pot", "potion");
        put("gapple", "golden apple");
        put("prot", "protection");
        put("sharp", "sharpness");
        put("mid", "middle");
        put("base", "base");
        put("void", "void");

        // 常见整句短语：先整句匹配，避免逐词替换后语序不通顺
        phrase("gg wp", "good game well played");
        phrase("ggs wp", "good games well played");
        phrase("gf gg", "good fight good game");
        phrase("gg gf", "good game good fight");
        phrase("gl hf", "good luck have fun");
        phrase("hf gl", "have fun good luck");
        phrase("ty gg", "thank you good game");
        phrase("gg ty", "good game thank you");
        phrase("nice try", "nice try");
        phrase("good fight", "good fight");
    }

    private AbbrevDictionary() {
    }

    private static void put(String key, String value) {
        TOKENS.put(key, value);
    }

    private static void phrase(String key, String value) {
        PHRASES.put(key, value);
    }

    /**
     * 把缩写展开成完整英文。
     *
     * @param text 原始文本
     * @return 展开后的文本；没有任何命中时返回 null（调用方按原文继续翻译）
     */
    public static String expand(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;
        String lower = trimmed.toLowerCase(Locale.ROOT);

        // 1) 整句短语
        String phrase = PHRASES.get(collapseSpaces(lower));
        if (phrase != null) {
            return phrase;
        }
        // 2) 整句单词
        String whole = TOKENS.get(lower);
        if (whole != null) {
            return whole;
        }
        // 3) 逐词替换，保留标点
        StringBuilder sb = new StringBuilder(trimmed.length() + 16);
        boolean changed = false;
        int i = 0;
        int n = trimmed.length();
        while (i < n) {
            char c = trimmed.charAt(i);
            if (Character.isLetter(c)) {
                int start = i;
                while (i < n && Character.isLetter(trimmed.charAt(i))) i++;
                String word = trimmed.substring(start, i);
                String full = TOKENS.get(word.toLowerCase(Locale.ROOT));
                if (full != null) {
                    sb.append(full);
                    changed = true;
                } else {
                    sb.append(word);
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return changed ? sb.toString() : null;
    }

    /** 文本里是否含有可识别的缩写（供宿主判断是否值得先走本地规则） */
    public static boolean containsAbbreviation(String text) {
        return expand(text) != null;
    }

    private static String collapseSpaces(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (!space && sb.length() > 0) {
                    sb.append(' ');
                    space = true;
                }
            } else {
                sb.append(c);
                space = false;
            }
        }
        int end = sb.length();
        if (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }
}
