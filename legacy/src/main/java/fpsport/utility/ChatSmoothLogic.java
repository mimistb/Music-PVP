package fpsport.utility;

import java.util.List;

/**
 * 聊天增强的纯逻辑部分（不引用任何 Minecraft 类，可以无头单测）：
 * 淡入曲线、逐条错峰、玩家名匹配、滚轮分帧。
 *
 * <p>注入点只负责把运行时状态喂进来，所有可测的算术都在这里。
 */
public final class ChatSmoothLogic {

    /** 同 tick 内最多错峰多少条，避免一次刷 10 行时最后一条等太久。 */
    private static final int MAX_STAGGER_INDEX = 8;

    private ChatSmoothLogic() {
    }

    /**
     * 淡入曲线：0 → 1，两端速度为 0 的 smoothstep。
     *
     * @param elapsedMs  已经过去的毫秒数（可以小于 0，表示错峰还没开始）
     * @param durationMs 动画总时长，&lt;= 0 时直接返回 1
     */
    public static float fadeFactor(long elapsedMs, int durationMs) {
        if (durationMs <= 0) {
            return 1f;
        }
        if (elapsedMs <= 0L) {
            return 0f;
        }
        if (elapsedMs >= durationMs) {
            return 1f;
        }
        float t = elapsedMs / (float) durationMs;
        return t * t * (3f - 2f * t);
    }

    /** 同一 tick 内第 index 条消息的额外延迟（毫秒）。 */
    public static long staggerDelay(int index, int stepMs) {
        if (index <= 0 || stepMs <= 0) {
            return 0L;
        }
        int capped = Math.min(index, MAX_STAGGER_INDEX);
        return (long) capped * stepMs;
    }

    /**
     * 从聊天文本里匹配玩家名：取出现位置最靠前的一个；同位置取更长的名字
     * （避免 Bob 抢了 Bobby 的位置）。命中位置必须是一个完整的词，
     * 防止 "Art" 匹配到 "Party" 里。
     *
     * @return 匹配到的名字（与 candidates 中是同一个字符串对象），没有则 null
     */
    public static String matchPlayerName(String text, List<String> candidates) {
        if (text == null || text.isEmpty() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        String best = null;
        int bestIndex = Integer.MAX_VALUE;
        for (String name : candidates) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            int from = 0;
            while (true) {
                int index = text.indexOf(name, from);
                if (index < 0) {
                    break;
                }
                if (isWholeWord(text, index, name.length())) {
                    boolean earlier = index < bestIndex;
                    boolean longer = index == bestIndex && (best == null || name.length() > best.length());
                    if (earlier || longer) {
                        bestIndex = index;
                        best = name;
                    }
                    break;
                }
                from = index + 1;
            }
        }
        return best;
    }

    /** text 的 [index, index+length) 是否独立成词（前后都不是字母 / 数字 / 下划线）。 */
    public static boolean isWholeWord(String text, int index, int length) {
        if (index < 0 || length <= 0 || index + length > text.length()) {
            return false;
        }
        if (index > 0 && isWordChar(text.charAt(index - 1))) {
            return false;
        }
        int end = index + length;
        return end >= text.length() || !isWordChar(text.charAt(end));
    }

    public static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
    }

    /**
     * 滚轮分帧：把累计的 pending 行拆成每次 ±1 行。
     *
     * @param lastStepMs 上次出步的时间；0 表示「立刻走第一步」
     * @param intervalMs 两步之间的最小间隔
     * @return 本次应当应用的行数（-1 / 0 / 1）
     */
    public static int scrollStep(int pending, long nowMs, long lastStepMs, int intervalMs) {
        if (pending == 0) {
            return 0;
        }
        if (lastStepMs != 0L && nowMs - lastStepMs < intervalMs) {
            return 0;
        }
        return pending > 0 ? 1 : -1;
    }
}
