package fpsport.adnin.chat;

/** 解析后的一条聊天数据（不可变）。 */
public final class ChatEvent {

    public enum Channel {
        /** 普通聊天。 */
        PUBLIC,
        /** {@code * Name action}。 */
        ACTION,
        /** 系统/服务端提示。 */
        SYSTEM
    }

    public final Channel channel;
    public final String raw;
    /** 发送者；系统消息为 {@code null}。 */
    public final String sender;
    /** 已剥掉颜色码、末尾计时器的正文。 */
    public final String body;
    public final long timestampMs;

    public ChatEvent(Channel channel, String raw, String sender, String body, long timestampMs) {
        this.channel = channel;
        this.raw = raw;
        this.sender = sender;
        this.body = body;
        this.timestampMs = timestampMs;
    }

    @Override
    public String toString() {
        return "ChatEvent{" + channel + ", " + sender + ": " + body + "}";
    }
}
