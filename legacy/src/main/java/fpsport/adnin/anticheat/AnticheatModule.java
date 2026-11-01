package fpsport.adnin.anticheat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 作弊检测模块实现：只做「信号 → 窗口聚合 → 确认 → 分发」，不含 UI、不含声音播放、不含检测算法。
 *
 * <p>与原 AdOverlay 的 {@code AdninAnticheatCore$ScaffoldState} 行为对应的部分：
 * 每种检查项独立计数、{@code Legit Scaffold} 用放大后的门槛、{@code ac_flagsound} 只在确认时触发。
 */
public final class AnticheatModule implements AnticheatApi {

    private volatile AnticheatConfig config;
    private volatile String selfName;

    private final Map<String, Deque<Long>> signals = new ConcurrentHashMap<String, Deque<Long>>();
    private final Map<String, Long> lastAlertMs = new ConcurrentHashMap<String, Long>();
    private final List<Alert> alerts = new CopyOnWriteArrayList<Alert>();
    private final List<Consumer<Alert>> listeners = new CopyOnWriteArrayList<Consumer<Alert>>();
    private volatile FlagSoundSink soundSink;

    public AnticheatModule(AnticheatConfig config) {
        AnticheatConfig copy = config.copy();
        copy.validate();
        this.config = copy;
    }

    @Override
    public void submit(CheatFlag flag) {
        if (flag == null || flag.player == null || flag.type == null) {
            return;
        }
        AnticheatConfig cfg = config;
        if (!cfg.anticheat || !cfg.isCheckEnabled(flag.type)) {
            return;
        }
        if (cfg.ignoreSelf && selfName != null && selfName.equalsIgnoreCase(flag.player)) {
            return;
        }
        double confidence = clamp01(flag.confidence);
        long now = flag.timestampMs;

        String key = key(flag.player, flag.type);
        Deque<Long> bucket = signals.get(key);
        if (bucket == null) {
            bucket = new ArrayDeque<Long>();
            signals.put(key, bucket);
        }
        synchronized (bucket) {
            prune(bucket, now, cfg.flagWindowMs);
            bucket.addLast(now);
        }
        sweepStale(now, cfg.flagWindowMs);
        int count = countInWindow(flag.player, flag.type);

        boolean confirmedByConfidence = confidence >= cfg.minConfidence;
        boolean confirmedByCount = count >= cfg.requiredCountFor(flag.type);
        if (!confirmedByConfidence && !confirmedByCount) {
            return;
        }
        Long last = lastAlertMs.get(key);
        if (last != null && now - last < cfg.flagWindowMs) {
            return; // 同一窗口内同玩家同检查项只告警一次
        }
        lastAlertMs.put(key, now);

        PlayerLabel label = classify(flag.evidence);
        Alert alert = new Alert(flag.player, flag.type, label, count, confidence, flag.evidence, now);
        alerts.add(alert);
        for (Consumer<Alert> listener : listeners) {
            try {
                listener.accept(alert);
            } catch (Throwable ignored) {
                // 单个监听者出错不能打断游戏循环，也不能影响其它监听者
            }
        }
        FlagSoundSink sink = soundSink;
        if (cfg.flagSound && sink != null) {
            sink.play(cfg.flagSoundName, cfg.flagSoundVolume, cfg.flagSoundPitch);
        }
    }

    @Override
    public AnticheatApi addAlertListener(Consumer<Alert> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    @Override
    public AnticheatApi setFlagSoundSink(FlagSoundSink sink) {
        this.soundSink = sink;
        return this;
    }

    @Override
    public AnticheatApi setSelfName(String selfName) {
        this.selfName = selfName;
        return this;
    }

    @Override
    public PlayerLabel classify(String text) {
        if (text == null || text.isEmpty()) {
            return PlayerLabel.NONE;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        AnticheatConfig cfg = config;
        if (containsAny(lower, cfg.blatantKeywords)) {
            return PlayerLabel.BLATANT;
        }
        if (containsAny(lower, cfg.closetKeywords)) {
            return PlayerLabel.CLOSET;
        }
        if (containsAny(lower, cfg.confirmedKeywords)) {
            return PlayerLabel.CONFIRMED;
        }
        if (containsAny(lower, cfg.sniperKeywords)) {
            return PlayerLabel.SNIPER;
        }
        if (containsAny(lower, cfg.cautionKeywords)) {
            return PlayerLabel.CAUTION;
        }
        return PlayerLabel.NONE;
    }

    @Override
    public List<Alert> alerts() {
        return Collections.unmodifiableList(new ArrayList<Alert>(alerts));
    }

    @Override
    public int countInWindow(String player, FlagType type) {
        if (player == null || type == null) {
            return 0;
        }
        Deque<Long> bucket = signals.get(key(player, type));
        if (bucket == null) {
            return 0;
        }
        synchronized (bucket) {
            return bucket.size();
        }
    }

    @Override
    public void reset() {
        signals.clear();
        lastAlertMs.clear();
        alerts.clear();
    }

    @Override
    public AnticheatConfig config() {
        return config.copy();
    }

    @Override
    public void updateConfig(AnticheatConfig newConfig) {
        AnticheatConfig copy = newConfig.copy();
        copy.validate();
        this.config = copy;
    }

    /**
     * 清掉过期时间戳；调用方需持有 bucket 锁。
     *
     * <p>不依赖时间戳单调递增（宿主时钟可能回退或包延迟乱序），逐条判断而不是遇到第一条
     * 未过期就 break，否则旧信号会永久留在窗口里。
     */
    private static void prune(Deque<Long> bucket, long now, long windowMs) {
        long lowerBound = now - windowMs;
        Iterator<Long> it = bucket.iterator();
        while (it.hasNext()) {
            Long ts = it.next();
            if (ts < lowerBound) {
                it.remove();
            }
        }
    }

    /**
     * 淘汰长期不活跃的玩家键，避免长时间游戏里 {@code signals}/{@code lastAlertMs} 只增不减。
     *
     * <p>判定窗口复用 {@code flagWindowMs}，不引入新的魔法数。
     */
    private void sweepStale(long now, long windowMs) {
        Iterator<Map.Entry<String, Deque<Long>>> bucketIt = signals.entrySet().iterator();
        while (bucketIt.hasNext()) {
            Map.Entry<String, Deque<Long>> entry = bucketIt.next();
            Deque<Long> bucket = entry.getValue();
            synchronized (bucket) {
                prune(bucket, now, windowMs);
                if (bucket.isEmpty()) {
                    bucketIt.remove();
                }
            }
        }
        Iterator<Map.Entry<String, Long>> alertIt = lastAlertMs.entrySet().iterator();
        while (alertIt.hasNext()) {
            Map.Entry<String, Long> entry = alertIt.next();
            if (now - entry.getValue() >= windowMs) {
                alertIt.remove();
            }
        }
    }

    private static String key(String player, FlagType type) {
        return player.toLowerCase(Locale.ROOT) + '|' + type.name();
    }

    private static boolean containsAny(String haystack, java.util.Collection<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static double clamp01(double value) {
        if (value < 0.0D) {
            return 0.0D;
        }
        if (value > 1.0D) {
            return 1.0D;
        }
        return value;
    }
}
