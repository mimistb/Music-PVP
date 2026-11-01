package bedwarsstats;

import bedwars.Team;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 自动名单统计管线（免 API Key）。
 *
 * <p>不访问 api.hypixel.net：名字 -> UUID 走 {@link PublicStatsSource#resolve} 的公开身份服务，
 * 统计走 {@link PublicStatsSource#fetch} 的第三方公开接口。
 *
 * - 与手动查询完全独立（不会覆盖手动结果卡片）；
 * - 比赛确认或按住 TAB 时才工作，setMatchActive(false) 清空队列/去重/已发现名单；
 * - 每两次网络请求之间固定 1100ms 间隔；
 * - 同一玩家 10 分钟去重（记忆最多 512 人）；
 * - 当场最多 128 人；统计缓存默认 6 小时（可用 {@link #setCacheTtlMillis} 改），最多 1024 条；
 * - 产出 stars / fkdr / wlr / bblr / wins / finalKills / bedsBroken / winStreak / level。
 *
 * <p>回调在注入的 Executor 线程上执行，宿主需要自行切回 Minecraft 主线程。
 * 本类不包含任何 UI。
 */
public final class RosterStatsService {

    public interface Listener {
        void onStatsReady(String playerName, String teamPrefix, BedWarsStats stats);

        void onStatsFailed(String playerName, String reason);
    }

    public static final long DEDUPE_MILLIS = 10L * 60L * 1000L;
    public static final long STATS_CACHE_MILLIS = 6L * 60L * 60L * 1000L;
    /** 两次网络请求之间的最小间隔（公开数据源没有 Key，但同样要克制）。 */
    public static final long SOURCE_SPACING_MILLIS = 1100L;
    public static final int MAXIMUM_REMEMBERED_PLAYERS = 512;
    public static final int MAXIMUM_CURRENT_MATCH_PLAYERS = 128;
    public static final int MAXIMUM_CACHE_ENTRIES = 1024;

    private static final class Job {
        final String playerName;
        final String teamPrefix;

        Job(String playerName, String teamPrefix) {
            this.playerName = playerName;
            this.teamPrefix = teamPrefix;
        }
    }

    private final Listener listener;
    private final BedWarsHttp.Transport transport;
    private final Executor executor;

    // 以下容器只在 executor 线程上访问。
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final HashMap<String, Job> discoveredPlayers = new HashMap<>();
    private final HashMap<String, Long> deduplicateUntil = new HashMap<>();
    private final HashMap<String, BedWarsStats> statsCache = new HashMap<>();

    private Job activeJob;
    private boolean activeValid;
    private long lastSourceRequestMillis;
    private boolean matchActive;

    private volatile long generation;

    /** 统计缓存有效期（可由宿主按设置写值，默认 6 小时）。 */
    private volatile long cacheTtlMillis = STATS_CACHE_MILLIS;

    /** 可选 API Key；为空表示对应来源被跳过。 */
    private volatile String officialKey = "";
    private volatile String netherKey = "";

    public RosterStatsService(Listener listener) {
        this(listener, BedWarsHttp.DEFAULT, defaultExecutor());
    }

    public RosterStatsService(Listener listener,
                              BedWarsHttp.Transport transport, Executor executor) {
        this.listener = listener;
        this.transport = transport == null ? BedWarsHttp.DEFAULT : transport;
        this.executor = executor == null ? defaultExecutor() : executor;
    }

    private static Executor defaultExecutor() {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                final Thread thread = new Thread(task, "BedWarsRosterStats");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /** 把一个本局玩家加入自动查询队列。team 未知时忽略。 */
    public void enqueuePlayer(final String playerName, final Team team,
                              final long nowMillis) {
        final long now = nowMillis > 0 ? nowMillis : System.currentTimeMillis();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                doEnqueue(playerName, team, now);
            }
        });
    }

    /** 比赛开始/结束。结束时清空队列、去重与已发现名单（缓存保留）。 */
    public void setMatchActive(final boolean active) {
        generation++;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (matchActive == active) return;
                matchActive = active;
                doCancel();
                deduplicateUntil.clear();
                discoveredPlayers.clear();
            }
        });
    }

    /** 设置可选 Key；空字符串表示跳过该来源，回退到免 Key 公开源。 */
    public void setApiKeys(final String official, final String nether) {
        officialKey = official == null ? "" : official.trim();
        netherKey = nether == null ? "" : nether.trim();
    }

    /** 配置变化时用已发现名单重新跑一遍（缓存仍可直接返回）。 */
    public void reloadConfiguration(final long nowMillis) {
        final long now = nowMillis > 0 ? nowMillis : System.currentTimeMillis();
        generation++;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                doCancel();
                deduplicateUntil.clear();
                if (!matchActive) return;
                final Map<String, Job> discovered = new HashMap<>(discoveredPlayers);
                discoveredPlayers.clear();
                for (Job job : discovered.values()) {
                    doEnqueue(job.playerName, teamFromPrefix(job.teamPrefix), now);
                }
            }
        });
    }

    public void cancel() {
        generation++;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                doCancel();
            }
        });
    }

    /**
     * 配置统计缓存有效期（设置页的“缓存分钟”）。写入值有 60 秒下限保护，
     * 已缓存的条目会在下次 prune 时按新有效期淘汰。
     */
    public void setCacheTtlMillis(final long ttlMillis) {
        cacheTtlMillis = Math.max(60_000L, ttlMillis);
    }

    // ------------------------------------------------------------------
    // 内部流程
    // ------------------------------------------------------------------

    private void doEnqueue(String playerName, Team team, long nowMillis) {
        if (!matchActive) return;
        final String name = playerName == null ? "" : playerName.trim();
        if (!HypixelJson.isValidPlayerName(name) || team == null || !team.known()) return;

        final String key = name.toLowerCase(java.util.Locale.ROOT);
        if (discoveredPlayers.size() >= MAXIMUM_CURRENT_MATCH_PLAYERS
                && !discoveredPlayers.containsKey(key)) {
            return;
        }
        final Job job = new Job(name, "\u00a7" + team.code());
        discoveredPlayers.put(key, job);

        pruneExpiredStats(nowMillis);
        final BedWarsStats cached = statsCache.get(key);
        if (cached != null) {
            emitStatsReady(name, job.teamPrefix, cached);
            deduplicateUntil.put(key, nowMillis + DEDUPE_MILLIS);
            return;
        }

        final Iterator<Map.Entry<String, Long>> iterator =
                deduplicateUntil.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= nowMillis) iterator.remove();
        }
        final Long deduplicate = deduplicateUntil.get(key);
        if (deduplicate != null && deduplicate > nowMillis) return;
        if (deduplicateUntil.size() >= MAXIMUM_REMEMBERED_PLAYERS) {
            final Iterator<String> oldest = deduplicateUntil.keySet().iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        deduplicateUntil.put(key, nowMillis + DEDUPE_MILLIS);
        queue.add(job);
        startNext();
    }

    private void startNext() {
        if (!matchActive || activeValid || queue.isEmpty()) return;
        activeJob = queue.poll();
        activeValid = true;
        generation++;
        startIdentityLookup();
    }

    private void startIdentityLookup() {
        if (activeJob == null) return;
        final long requestGeneration = generation;
        final PublicStatsSource.Identity identity =
                PublicStatsSource.resolve(transport, activeJob.playerName);
        if (requestGeneration != generation || !activeValid) return;

        if (!identity.ok()) {
            failActive(shorten(identity.error == null || identity.error.isEmpty()
                    ? "无法解析玩家 UUID" : identity.error));
            return;
        }
        startStatsLookup(identity.uuid, requestGeneration);
    }

    private void startStatsLookup(String uuid, long requestGeneration) {
        if (requestGeneration != generation || !activeValid) return;

        final long now = System.currentTimeMillis();
        final long delay = SOURCE_SPACING_MILLIS - (now - lastSourceRequestMillis);
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (requestGeneration != generation || !activeValid) return;

        lastSourceRequestMillis = System.currentTimeMillis();
        final PublicStatsSource.Fetch fetched =
                PublicStatsSource.fetch(transport, uuid, officialKey, netherKey);
        if (requestGeneration != generation || !activeValid) return;

        if (!fetched.ok()) {
            failActive(shorten(fetched.error == null || fetched.error.isEmpty()
                    ? "公开数据源暂时不可用" : fetched.error));
            return;
        }

        final PublicStatsSource.Parse parsed = PublicStatsSource.parse(
                fetched.body, uuid, activeJob.playerName, System.currentTimeMillis());
        if (!parsed.ok()) {
            failActive(shorten(parsed.error == null || parsed.error.isEmpty()
                    ? "统计数据无法解析" : parsed.error));
            return;
        }

        final BedWarsStats stats = parsed.stats;
        if (statsCache.size() >= MAXIMUM_CACHE_ENTRIES) {
            final Iterator<String> oldest = statsCache.keySet().iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        statsCache.put(activeJob.playerName.toLowerCase(java.util.Locale.ROOT), stats);
        emitStatsReady(activeJob.playerName, activeJob.teamPrefix, stats);
        completeActive();
    }

    private void pruneExpiredStats(long nowMillis) {
        final Iterator<Map.Entry<String, BedWarsStats>> iterator =
                statsCache.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().updatedAtMillis + cacheTtlMillis <= nowMillis) {
                iterator.remove();
            }
        }
    }

    private void failActive(String reason) {
        if (activeValid && activeJob != null) {
            emitStatsFailed(activeJob.playerName, reason);
        }
        completeActive();
    }

    private void completeActive() {
        activeJob = null;
        activeValid = false;
        // 等价于源码的 QTimer::singleShot(0, startNext)。
        executor.execute(new Runnable() {
            @Override
            public void run() {
                startNext();
            }
        });
    }

    private void doCancel() {
        generation++;
        queue.clear();
        activeJob = null;
        activeValid = false;
    }

    private void emitStatsReady(String playerName, String teamPrefix, BedWarsStats stats) {
        if (listener == null) return;
        try {
            listener.onStatsReady(playerName, teamPrefix, stats);
        } catch (RuntimeException ignored) {
            // 宿主回调异常不能中断自动管线。
        }
    }

    private void emitStatsFailed(String playerName, String reason) {
        if (listener == null) return;
        try {
            listener.onStatsFailed(playerName, reason);
        } catch (RuntimeException ignored) {
            // 宿主回调异常不能中断自动管线。
        }
    }

    private static Team teamFromPrefix(String prefix) {
        if (prefix == null || prefix.length() < 2) return Team.UNKNOWN;
        return Team.fromFormatCode(prefix.charAt(1));
    }

    /** 失败原因只用于回调展示，截断到 180 字符，避免异常文本撑爆 UI。 */
    private static String shorten(String value) {
        if (value == null) return "";
        return value.length() > 180 ? value.substring(0, 180) : value;
    }
}
