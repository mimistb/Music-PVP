package fpsport.adnin.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 聊天数据模块实现：接收 → 解析 → 存储 → 分发。
 *
 * <p>过滤规则按恢复出的顺序：总开关 → 队友 → 空包；统计输出判定另走
 * {@link #decideStats(PlayerStats, GameMode, boolean, boolean)}，原因码与二进制日志前缀一致。
 */
public final class ChatDataModule implements ChatApi {

    private volatile ChatConfig config;
    private final Deque<ChatEvent> history = new ArrayDeque<ChatEvent>();
    private final Map<String, Long> blacklist = new ConcurrentHashMap<String, Long>();
    private final List<Consumer<ChatEvent>> listeners = new CopyOnWriteArrayList<Consumer<ChatEvent>>();

    public ChatDataModule(ChatConfig config) {
        ChatConfig copy = config.copy();
        copy.validate();
        this.config = copy;
    }

    @Override
    public ChatEvent receive(String raw, long nowMs) {
        ChatConfig cfg = config;
        if (!cfg.chatOverlay) {
            return null;
        }
        ChatEvent event = ChatParser.parse(raw, nowMs, cfg);
        if (event == null) {
            return null;
        }
        if (event.sender != null && cfg.ignoreTeammates && isTeammate(cfg, event.sender)) {
            return null;
        }
        synchronized (history) {
            history.addLast(event);
            while (history.size() > cfg.historyCapacity) {
                history.removeFirst();
            }
        }
        for (Consumer<ChatEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Throwable ignored) {
                // 监听者出错不能打断收包链路
            }
        }
        return event;
    }

    /** 宿主的队伍查询可能抛异常（scoreboard 未初始化等），失败时按「非队友」放行。 */
    private static boolean isTeammate(ChatConfig cfg, String sender) {
        try {
            return cfg.teammates.isTeammate(sender);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public ChatApi addListener(Consumer<ChatEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    @Override
    public List<ChatEvent> history() {
        synchronized (history) {
            return Collections.unmodifiableList(new ArrayList<ChatEvent>(history));
        }
    }

    @Override
    public void blacklist(String name, long nowMs) {
        if (name != null && !name.isEmpty()) {
            blacklist.put(name.toLowerCase(Locale.ROOT), nowMs);
        }
    }

    @Override
    public boolean isBlacklisted(String name, long nowMs) {
        if (name == null) {
            return false;
        }
        Long since = blacklist.get(name.toLowerCase(Locale.ROOT));
        if (since == null) {
            return false;
        }
        long ttl = config.blacklistRetainDays * 24L * 60L * 60L * 1000L;
        if (nowMs - since > ttl) {
            blacklist.remove(name.toLowerCase(Locale.ROOT));
            return false;
        }
        return true;
    }

    @Override
    public StatsDecision decideStats(PlayerStats stats, GameMode mode, boolean prequeue, boolean teammate) {
        ChatConfig cfg = config;
        if (!cfg.chatOverlay) {
            return StatsDecision.skip("co:disabled:stats");
        }
        if (prequeue) {
            return StatsDecision.skip("co:prequeue:stats:");
        }
        if (mode == null || mode == GameMode.UNKNOWN) {
            return StatsDecision.skip("co:mode:unknown:");
        }
        if (teammate) {
            return StatsDecision.skip("co:teammate:stats:");
        }
        if (stats == null) {
            return StatsDecision.skip("co:mode:unknown:");
        }
        switch (mode) {
            case BEDWARS:
                if (!stats.bedwarsAvailable) {
                    return StatsDecision.skip("co:nobw:");
                }
                if ((cfg.minStars > 0 || cfg.minFkdr > 0.0D)
                        && stats.stars < cfg.minStars && stats.fkdr < cfg.minFkdr) {
                    return StatsDecision.skip("co:bwthresh:");
                }
                return StatsDecision.ALLOW;
            case SKYWARS:
                if (!stats.skywarsAvailable) {
                    return StatsDecision.skip("co:nosw:");
                }
                if (cfg.minSwKdr > 0.0D && stats.swKdr < cfg.minSwKdr) {
                    return StatsDecision.skip("co:swkdr:");
                }
                return StatsDecision.ALLOW;
            case DUELS:
                if (!stats.bedwarsAvailable) {
                    return StatsDecision.skip("co:noduel:");
                }
                return StatsDecision.ALLOW;
            case BEDWARS_DUELS:
                if (!stats.bedwarsAvailable) {
                    return StatsDecision.skip("co:nobwduel:");
                }
                return StatsDecision.ALLOW;
            default:
                return StatsDecision.skip("co:mode:unknown:");
        }
    }

    @Override
    public String denick(String text, String playerName) {
        if (text == null || playerName == null || playerName.isEmpty()) {
            return text;
        }
        ChatConfig cfg = config;
        return text.replace(playerName, "c[" + cfg.nickMask + "]");
    }

    @Override
    public ChatConfig config() {
        return config.copy();
    }

    @Override
    public void updateConfig(ChatConfig newConfig) {
        ChatConfig copy = newConfig.copy();
        copy.validate();
        this.config = copy;
    }

    @Override
    public void reset() {
        synchronized (history) {
            history.clear();
        }
        blacklist.clear();
    }
}
