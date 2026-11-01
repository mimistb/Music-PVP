package fpsport.adnin.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 聊天文本解析（无状态，全部静态）。 */
public final class ChatParser {

    /** 恢复自二进制的计时器正则。 */
    public static final Pattern TRAILING_TIMER = Pattern.compile("\\s*\\u00A7c\\d:\\d{2}\\s*$");
    /** {@code §x} 颜色/格式码。 */
    private static final Pattern COLOR_CODE = Pattern.compile("\\u00A7[0-9A-FK-ORa-fk-or]");
    private static final Pattern ANGLE_SENDER = Pattern.compile("^<([A-Za-z0-9_]{1,16})>\\s?(.*)$");
    private static final Pattern ACTION = Pattern.compile("^\\*\\s+([A-Za-z0-9_]{1,16})\\s(.*)$");
    private static final Pattern BRACKET_SENDER = Pattern.compile("^\\[[^\\]]*\\]\\s*([A-Za-z0-9_]{1,16})\\s:\\s(.*)$");
    private static final Pattern JSON_TEXT = Pattern.compile("\"text\":\"([^\"]*)\"");

    private ChatParser() {
    }

    public static ChatEvent parse(String raw, long nowMs, ChatConfig config) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        String channelSource = text;
        if (config.stripTrailingTimer) {
            channelSource = TRAILING_TIMER.matcher(channelSource).replaceAll("");
        }
        String body = COLOR_CODE.matcher(channelSource).replaceAll("").trim();
        if (text.startsWith("{")) {
            body = flattenJson(text, config);
        }
        if (body.length() > config.maxMessageLength) {
            body = body.substring(0, config.maxMessageLength);
        }
        if (body.isEmpty()) {
            return null;
        }

        Matcher action = ACTION.matcher(body);
        if (action.matches()) {
            return new ChatEvent(ChatEvent.Channel.ACTION, raw, action.group(1), action.group(2).trim(), nowMs);
        }
        Matcher angle = ANGLE_SENDER.matcher(body);
        // 原始串可能带 § 颜色码或前导空格，只能用剥色后的正文判断是不是公聊
        if (angle.matches() && body.startsWith("<")) {
            return new ChatEvent(ChatEvent.Channel.PUBLIC, raw, angle.group(1), angle.group(2).trim(), nowMs);
        }
        Matcher bracket = BRACKET_SENDER.matcher(body);
        if (bracket.matches()) {
            return new ChatEvent(ChatEvent.Channel.PUBLIC, raw, bracket.group(1), bracket.group(2).trim(), nowMs);
        }
        return new ChatEvent(ChatEvent.Channel.SYSTEM, raw, null, body, nowMs);
    }

    /** 轻量 JSON 文本提取：只取所有 {@code "text"} 值拼接，不引入 JSON 库。 */
    private static String flattenJson(String json, ChatConfig config) {
        StringBuilder sb = new StringBuilder();
        Matcher m = JSON_TEXT.matcher(json);
        while (m.find()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(COLOR_CODE.matcher(m.group(1)).replaceAll(""));
        }
        String joined = sb.toString().trim();
        return joined.isEmpty() ? COLOR_CODE.matcher(json).replaceAll("") : joined;
    }

    /** 逐字符剥掉颜色码（保留字形本身），供宿主复用。 */
    public static String stripColors(String text) {
        return text == null ? null : COLOR_CODE.matcher(text).replaceAll("");
    }
}
