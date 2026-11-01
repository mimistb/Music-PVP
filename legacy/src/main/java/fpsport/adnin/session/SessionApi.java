package fpsport.adnin.session;

import java.util.function.Consumer;

/**
 * 本次游戏模块对外接口：状态管理、数据缓存、生命周期。
 *
 * <p>只处理「一局开始 → 结束 → 累计 → 连胜/平均时长」，不读聊天、不读反作弊，也不绘制。
 */
public interface SessionApi {

    /** 一局开始；已在局内时忽略（防止重复触发把时长算成 0）。 */
    void onGameStart(GameSessionMode mode, long nowMs);

    /** 一局结束并结算。返回结算后的快照；不在局内时返回当前快照。 */
    SessionSnapshot onGameEnd(boolean win, int kills, int finalKills, int beds, long nowMs);

    /** 心跳：刷新当前局时长、处理超时与会话自动结算。每 tick 或每秒调用均可。 */
    SessionSnapshot tick(long nowMs);

    /** 当前快照。 */
    SessionSnapshot snapshot();

    /** 订阅变化（结算后、重置后、超时后触发）。 */
    SessionApi addListener(Consumer<SessionSnapshot> listener);

    /** 清空本会话所有累计值（对应 {@code sessionStatsResetPending} 被消费）。 */
    void reset(long nowMs);

    /** 参数快照（副本）。 */
    SessionConfig config();

    /** 热更新参数；非法值抛 IllegalArgumentException 且不改动现有配置。 */
    void updateConfig(SessionConfig newConfig);

    /** 时长格式化：{@code %dd %dh} / {@code %dh %dm} / {@code %dm %ds}，与恢复出的格式一致。 */
    String formatDuration(long millis);
}
