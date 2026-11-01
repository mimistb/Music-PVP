package fpsport.adnin.chat;

import java.util.List;
import java.util.function.Consumer;

/**
 * 聊天数据模块对外接口：接收 → 解析 → 存储 → 分发 全链路。
 *
 * <p>宿主只把原始 chat 文本/JSON 文本交进来，其余（黑名单、队友过滤、阈值判定、历史缓冲）
 * 都由模块处理；模块不绘制任何 UI。
 */
public interface ChatApi {

    /**
     * 接收一条原始聊天（普通文本或已序列化的 JSON 文本）。
     *
     * @return 解析出的 {@link ChatEvent}；被开关/黑名单/队友规则过滤时返回 {@code null}
     */
    ChatEvent receive(String raw, long nowMs);

    /** 订阅分发事件（可多个）。 */
    ChatApi addListener(Consumer<ChatEvent> listener);

    /** 历史缓冲快照（按时间升序）。 */
    List<ChatEvent> history();

    /** 加入黑名单，保留期由 {@link ChatConfig#blacklistRetainDays} 决定。 */
    void blacklist(String name, long nowMs);

    /** 是否在黑名单且未过期。 */
    boolean isBlacklisted(String name, long nowMs);

    /** 统计输出判定（阈值、模式、队友、预队列等全部规则）。 */
    StatsDecision decideStats(PlayerStats stats, GameMode mode, boolean prequeue, boolean teammate);

    /** 脱敏：把名字替换为 {@link ChatConfig#nickMask}。 */
    String denick(String text, String playerName);

    /** 参数快照（副本）。 */
    ChatConfig config();

    /** 热更新参数；非法值抛 IllegalArgumentException 且不改动现有配置。 */
    void updateConfig(ChatConfig newConfig);

    /** 清空历史与黑名单。 */
    void reset();
}
