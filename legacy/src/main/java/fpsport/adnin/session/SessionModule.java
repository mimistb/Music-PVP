package fpsport.adnin.session;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 本次游戏模块实现：会话状态机 + 数据缓存。
 *
 * <p>状态迁移：{@code IDLE → IN_GAME → IDLE}，只在 IN_GAME 期间累计时长；
 * 掉线超过 {@link SessionConfig#idleTimeoutMs} 视为新会话（对应恢复出的 {@code expiresIn:120s}）。
 */
public final class SessionModule implements SessionApi {

    private volatile SessionConfig config;

    private int games;
    private int wins;
    private int winstreak;
    private int kills;
    private int finalKills;
    private int beds;
    private long totalGameTimeMs;

    private boolean active;
    private GameSessionMode mode = GameSessionMode.UNKNOWN;
    private long gameStartMs;
    /** -1 表示本会话尚未开始；0 是合法时间戳，不能当哨兵。 */
    private long sessionStartMs = -1L;
    private long lastNowMs;
    private long lastGameEndMs = -1L;

    private final List<Consumer<SessionSnapshot>> listeners = new CopyOnWriteArrayList<Consumer<SessionSnapshot>>();

    public SessionModule(SessionConfig config) {
        SessionConfig copy = config.copy();
        copy.validate();
        this.config = copy;
    }

    @Override
    public void onGameStart(GameSessionMode mode, long nowMs) {
        if (!config.sessionStats || active) {
            return;
        }
        active = true;
        this.mode = mode == null ? GameSessionMode.UNKNOWN : mode;
        gameStartMs = nowMs;
        lastNowMs = nowMs;
        if (sessionStartMs < 0L) {
            sessionStartMs = nowMs;
        }
    }

    @Override
    public SessionSnapshot onGameEnd(boolean win, int kills, int finalKills, int beds, long nowMs) {
        if (!active) {
            lastNowMs = Math.max(lastNowMs, nowMs);
            return snapshot();
        }
        long duration = Math.max(0L, nowMs - gameStartMs);
        active = false;
        lastNowMs = nowMs;
        lastGameEndMs = nowMs;

        games++;
        totalGameTimeMs += duration;
        this.kills += Math.max(0, kills);
        this.finalKills += Math.max(0, finalKills);
        this.beds += Math.max(0, beds);
        if (win) {
            wins++;
            winstreak++;
        } else if (!config.keepWinstreakOnLoss) {
            winstreak = 0;
        }
        if (config.maxSessionMs > 0L && sessionTimeMs(nowMs) >= config.maxSessionMs) {
            reset(nowMs);
            return snapshot();
        }
        SessionSnapshot snap = snapshot();
        notifyListeners(snap);
        return snap;
    }

    @Override
    public SessionSnapshot tick(long nowMs) {
        lastNowMs = Math.max(lastNowMs, nowMs);
        SessionConfig cfg = config;
        if (!cfg.sessionStats) {
            return snapshot();
        }
        if (!active && cfg.idleTimeoutMs > 0L && lastGameEndMs >= 0L && lastNowMs - lastGameEndMs > cfg.idleTimeoutMs) {
            reset(lastNowMs);
        } else if (active && cfg.maxSessionMs > 0L && sessionTimeMs(lastNowMs) >= cfg.maxSessionMs) {
            reset(lastNowMs);
        }
        return snapshot();
    }

    @Override
    public SessionSnapshot snapshot() {
        long avgRaw = games == 0 ? 0L : totalGameTimeMs / games;
        long unit = Math.max(1L, config.avgTimeRoundSeconds) * 1000L;
        long avg = games == 0 ? 0L : (avgRaw / unit) * unit;
        long current = active ? Math.max(0L, lastNowMs - gameStartMs) : 0L;
        return new SessionSnapshot(active, mode, games, wins, winstreak, kills, finalKills, beds,
                totalGameTimeMs, avg, sessionTimeMs(lastNowMs), current);
    }

    @Override
    public SessionApi addListener(Consumer<SessionSnapshot> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    @Override
    public void reset(long nowMs) {
        games = 0;
        wins = 0;
        winstreak = 0;
        kills = 0;
        finalKills = 0;
        beds = 0;
        totalGameTimeMs = 0L;
        active = false;
        mode = GameSessionMode.UNKNOWN;
        gameStartMs = 0L;
        sessionStartMs = -1L;
        lastGameEndMs = -1L;
        lastNowMs = Math.max(lastNowMs, nowMs);
        notifyListeners(snapshot());
    }

    @Override
    public SessionConfig config() {
        return config.copy();
    }

    @Override
    public void updateConfig(SessionConfig newConfig) {
        SessionConfig copy = newConfig.copy();
        copy.validate();
        this.config = copy;
    }

    @Override
    public String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (days > 0L) {
            return String.format(Locale.ROOT, "%dd %dh", days, hours);
        }
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh %dm", hours, minutes);
        }
        if (minutes > 0L) {
            return String.format(Locale.ROOT, "%dm %ds", minutes, seconds);
        }
        return String.format(Locale.ROOT, "%ds", seconds);
    }

    private long sessionTimeMs(long nowMs) {
        return sessionStartMs < 0L ? 0L : Math.max(0L, nowMs - sessionStartMs);
    }

    private void notifyListeners(SessionSnapshot snapshot) {
        for (Consumer<SessionSnapshot> listener : listeners) {
            listener.accept(snapshot);
        }
    }
}
