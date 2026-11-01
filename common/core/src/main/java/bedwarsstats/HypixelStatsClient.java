package bedwarsstats;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 手动查询玩家 Bed Wars 统计（可选 API Key）。
 *
 * <p>玩家 UUID 由 {@link PublicStatsSource#resolve} 通过公开身份服务解析，统计数据由
 * {@link PublicStatsSource#fetch} 抓取：填了官方 Key 时走 {@code api.hypixel.net/v2/player}，
 * 填了第三方 Key 时走 NetherAPI，两者都为空或失败时回退免 Key 公开源。
 *
 * <p>其余行为与之前一致：
 * <ul>
 *   <li>6 小时缓存（最多 256 条，LRU）；</li>
 *   <li>两次网络请求最小间隔 1000ms；</li>
 *   <li>单次响应上限 4 MiB、10 秒超时；</li>
 *   <li>取消/新查询使用 generation 丢弃过期结果。</li>
 * </ul>
 *
 * <p>所有网络与状态变更都在注入的单线程 Executor 上执行；Listener 回调也在该线程上，
 * 宿主需要自己切回 Minecraft 主线程再刷新 UI。本类不包含任何 UI。
 */
public final class HypixelStatsClient {

    public enum State {
        IDLE,
        LOADING,
        READY,
        ERROR
    }

    /** 状态变化回调（等价于源码的 stateChanged / statusMessageChanged 等信号）。 */
    public interface Listener {
        void onHypixelStatsChanged(HypixelStatsClient client);
    }

    public static final long CACHE_LIFETIME_MILLIS = 6L * 60L * 60L * 1000L;
    public static final long MINIMUM_REQUEST_INTERVAL_MILLIS = 1000L;
    public static final int MAXIMUM_RESPONSE_BYTES = PublicStatsSource.MAXIMUM_RESPONSE_BYTES;
    public static final int MAXIMUM_CACHE_ENTRIES = 256;
    public static final int REQUEST_TIMEOUT_MILLIS = PublicStatsSource.REQUEST_TIMEOUT_MILLIS;

    private static final class CacheEntry {
        final BedWarsStats stats;
        final long expiresAtMillis;
        long lastAccessedAtMillis;

        CacheEntry(BedWarsStats stats, long expiresAtMillis, long lastAccessedAtMillis) {
            this.stats = stats;
            this.expiresAtMillis = expiresAtMillis;
            this.lastAccessedAtMillis = lastAccessedAtMillis;
        }
    }

    private final Listener listener;
    private final BedWarsHttp.Transport transport;
    private final Executor executor;
    private final LinkedHashMap<String, CacheEntry> cache =
            new LinkedHashMap<>(16, 0.75F, true);

    private volatile State state = State.IDLE;
    private volatile String statusMessage = "";
    private volatile String errorMessage = "";
    private volatile BedWarsStats stats = BedWarsStats.empty();
    private volatile long generation;
    private volatile String officialKey = "";
    private volatile String netherKey = "";

    private long lastRequestAtMillis;

    public HypixelStatsClient(Listener listener) {
        this(listener, BedWarsHttp.DEFAULT, defaultExecutor());
    }

    public HypixelStatsClient(Listener listener,
                              BedWarsHttp.Transport transport, Executor executor) {
        this.listener = listener;
        this.transport = transport == null ? BedWarsHttp.DEFAULT : transport;
        this.executor = executor == null ? defaultExecutor() : executor;
    }

    private static Executor defaultExecutor() {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                final Thread thread = new Thread(task, "BedWarsStatsClient");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /** 查询玩家名或 UUID。nowMillis <= 0 时使用当前时间。 */
    public void query(final String playerId, final long nowMillis) {
        final long now = nowMillis > 0 ? nowMillis : System.currentTimeMillis();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                doQuery(playerId, now);
            }
        });
    }

    /** 设置可选 Key；空字符串表示跳过该来源，回退到免 Key 公开源。 */
    public void setApiKeys(String official, String nether) {
        officialKey = official == null ? "" : official.trim();
        netherKey = nether == null ? "" : nether.trim();
    }

    /** 取消当前请求；正在进行的结果会被丢弃。 */
    public void cancel() {
        generation++;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (state == State.LOADING) {
                    setStatusMessage("查询已取消");
                    state = State.IDLE;
                    notifyChanged();
                }
            }
        });
    }

    public boolean busy() {
        return state == State.LOADING;
    }

    public State getState() {
        return state;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public BedWarsStats getStats() {
        return stats;
    }

    // ------------------------------------------------------------------
    // 内部流程
    // ------------------------------------------------------------------

    private void doQuery(String playerId, long nowMillis) {
        if (busy()) {
            // 与源码一致：第二次点击不会打断在途请求。
            setStatusMessage("已有查询正在进行");
            return;
        }
        final String requestedId = playerId == null ? "" : playerId.trim();
        if (requestedId.isEmpty()) {
            setError("请输入玩家名或 UUID");
            return;
        }
        final String normalized = HypixelJson.normalizeUuid(requestedId);
        if (normalized.isEmpty() && !HypixelJson.isValidPlayerName(requestedId)) {
            setError("不是有效的 Minecraft 玩家名：" + requestedId);
            return;
        }
        if (normalized.isEmpty()) {
            lookupIdentity(requestedId, nowMillis);
        } else {
            startUuidLookup(normalized, requestedId, nowMillis);
        }
    }

    private void lookupIdentity(String playerName, long nowMillis) {
        errorMessage = "";
        state = State.LOADING;
        notifyChanged();
        setStatusMessage("正在解析玩家名…");

        final long requestGeneration = ++generation;
        final PublicStatsSource.Identity identity = PublicStatsSource.resolve(transport, playerName);
        if (requestGeneration != generation) return;

        if (!identity.ok()) {
            setError(identity.error == null || identity.error.isEmpty()
                    ? "无法解析玩家名" : identity.error);
            return;
        }

        // 身份请求完成，先回到 Idle，避免 busy 守卫拒绝后续统计请求。
        state = State.IDLE;
        notifyChanged();
        startUuidLookup(identity.uuid,
                identity.name.isEmpty() ? playerName : identity.name, nowMillis);
    }

    private void startUuidLookup(String uuid, String displayName, long nowMillis) {
        if (busy()) {
            setStatusMessage("已有查询正在进行");
            return;
        }

        pruneExpiredCache(nowMillis);
        final CacheEntry cached = cache.get(uuid);
        if (cached != null) {
            cached.lastAccessedAtMillis = nowMillis;
            applyStats(cached.stats, "使用缓存结果（六小时有效期）");
            return;
        }
        if (nowMillis - lastRequestAtMillis < MINIMUM_REQUEST_INTERVAL_MILLIS) {
            setError("请等待一秒后再发起下一次查询");
            return;
        }

        lastRequestAtMillis = nowMillis;
        errorMessage = "";
        state = State.LOADING;
        notifyChanged();
        setStatusMessage("正在获取 Bed Wars 统计数据…");

        final long requestGeneration = ++generation;
        final PublicStatsSource.Fetch fetched =
                PublicStatsSource.fetch(transport, uuid, officialKey, netherKey);
        if (requestGeneration != generation) return;

        if (!fetched.ok()) {
            setError(fetched.error == null || fetched.error.isEmpty()
                    ? "公开数据源暂时不可用" : fetched.error);
            return;
        }

        final PublicStatsSource.Parse parsed =
                PublicStatsSource.parse(fetched.body, uuid, displayName, nowMillis);
        if (!parsed.ok()) {
            setError(parsed.error == null || parsed.error.isEmpty() ? "统计数据无法解析" : parsed.error);
            return;
        }
        insertCacheEntry(uuid, parsed.stats, nowMillis);
        applyStats(parsed.stats, "统计已更新 · " + fetched.provider);
    }

    private void pruneExpiredCache(long nowMillis) {
        final Iterator<Map.Entry<String, CacheEntry>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAtMillis <= nowMillis) iterator.remove();
        }
    }

    private void insertCacheEntry(String uuid, BedWarsStats newStats, long nowMillis) {
        pruneExpiredCache(nowMillis);
        if (!cache.containsKey(uuid) && cache.size() >= MAXIMUM_CACHE_ENTRIES) {
            String leastRecentlyUsedKey = null;
            long leastAccess = Long.MAX_VALUE;
            for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                if (entry.getValue().lastAccessedAtMillis < leastAccess) {
                    leastAccess = entry.getValue().lastAccessedAtMillis;
                    leastRecentlyUsedKey = entry.getKey();
                }
            }
            if (leastRecentlyUsedKey != null) cache.remove(leastRecentlyUsedKey);
        }
        cache.put(uuid, new CacheEntry(newStats, nowMillis + CACHE_LIFETIME_MILLIS, nowMillis));
    }

    private void applyStats(BedWarsStats newStats, String status) {
        stats = newStats;
        errorMessage = "";
        setStatusMessage(status);
        state = State.READY;
        notifyChanged();
    }

    private void setStatusMessage(String message) {
        final String normalized = message == null ? "" : message;
        if (statusMessage.equals(normalized)) return;
        statusMessage = normalized;
        notifyChanged();
    }

    private void setError(String message) {
        errorMessage = message == null ? "" : message;
        setStatusMessage("统计查询失败");
        state = State.ERROR;
        notifyChanged();
    }

    private void notifyChanged() {
        if (listener == null) return;
        try {
            listener.onHypixelStatsChanged(this);
        } catch (RuntimeException ignored) {
            // 宿主回调异常不能中断网络管线。
        }
    }
}
