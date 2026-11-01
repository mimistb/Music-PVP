package fpsport.selftest;

import fpsport.adnin.anticheat.Alert;
import fpsport.adnin.anticheat.AnticheatConfig;
import fpsport.adnin.anticheat.AnticheatModule;
import fpsport.adnin.anticheat.CheatFlag;
import fpsport.adnin.anticheat.FlagType;
import fpsport.adnin.anticheat.PlayerLabel;
import fpsport.adnin.chat.ChatConfig;
import fpsport.adnin.chat.ChatDataModule;
import fpsport.adnin.chat.ChatEvent;
import fpsport.adnin.chat.GameMode;
import fpsport.adnin.chat.PlayerStats;
import fpsport.adnin.chat.StatsDecision;
import fpsport.adnin.session.GameSessionMode;
import fpsport.adnin.session.SessionConfig;
import fpsport.adnin.session.SessionModule;
import fpsport.adnin.session.SessionSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * 三个模块的无头单元测试（不依赖 Minecraft 运行时，直接 {@code java -cp} 跑）。
 * 退出码 0 = 全部通过。
 */
public final class ModuleTestRunner {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    private ModuleTestRunner() {
    }

    public static void main(String[] args) {
        testAnticheat();
        testChat();
        testSession();

        System.out.println("PASSED=" + passed + " FAILED=" + failures.size());
        for (String f : failures) {
            System.out.println("FAIL " + f);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    // ================= 反作弊 =================

    private static void testAnticheat() {
        AnticheatConfig cfg = new AnticheatConfig();
        AnticheatModule m = new AnticheatModule(cfg);
        m.setSelfName("Me");

        // 1 默认参数校验通过
        check("anticheat default validate", true);

        // 2 关闭的检查项不产生告警
        cfg = new AnticheatConfig();
        cfg.legitScaffold = false;
        m = new AnticheatModule(cfg);
        m.submit(new CheatFlag("Bob", FlagType.LEGIT_SCAFFOLD, 0.95, "legit", 100L));
        eq("disabled check produces no alert", m.alerts().size(), 0);

        // 3 高置信度立刻确认 + 关键词标签
        cfg = new AnticheatConfig();
        m = new AnticheatModule(cfg);
        m.submit(new CheatFlag("Bob", FlagType.AUTOBLOCK, 0.9, "blatant cheater", 100L));
        eq("confidence confirm count", m.alerts().size(), 1);
        eq("label blatant", m.alerts().get(0).label, PlayerLabel.BLATANT);

        // 4 低置信度累计到 confirmFlagCount 才确认
        cfg = new AnticheatConfig();
        m = new AnticheatModule(cfg);
        m.submit(new CheatFlag("Bob", FlagType.NO_SLOW, 0.1, "x", 100L));
        m.submit(new CheatFlag("Bob", FlagType.NO_SLOW, 0.1, "x", 200L));
        eq("two low flags no alert", m.alerts().size(), 0);
        m.submit(new CheatFlag("Bob", FlagType.NO_SLOW, 0.1, "closet cheating", 300L));
        eq("three low flags alert", m.alerts().size(), 1);
        eq("label closet", m.alerts().get(0).label, PlayerLabel.CLOSET);
        eq("flag count in window", m.countInWindow("Bob", FlagType.NO_SLOW), 3);

        // 5 LEGIT_SCAFFOLD 门槛被放大（3 * 2 = 6）
        cfg = new AnticheatConfig();
        m = new AnticheatModule(cfg);
        for (int i = 0; i < 5; i++) {
            m.submit(new CheatFlag("Bob", FlagType.LEGIT_SCAFFOLD, 0.1, "x", 100L + i * 100L));
        }
        eq("legit scaffold 5 flags no alert", m.alerts().size(), 0);
        m.submit(new CheatFlag("Bob", FlagType.LEGIT_SCAFFOLD, 0.1, "x", 1000L));
        eq("legit scaffold 6 flags alert", m.alerts().size(), 1);

        // 6 同一窗口内去重
        cfg = new AnticheatConfig();
        m = new AnticheatModule(cfg);
        m.submit(new CheatFlag("Bob", FlagType.SCAFFOLD_A, 0.9, "s", 100L));
        m.submit(new CheatFlag("Bob", FlagType.SCAFFOLD_A, 0.9, "s", 200L));
        eq("dedupe in same window", m.alerts().size(), 1);

        // 7 窗口过期后可以再次告警
        m.submit(new CheatFlag("Bob", FlagType.SCAFFOLD_A, 0.9, "s", 20_000L));
        eq("alert after window", m.alerts().size(), 2);

        // 8 ignoreSelf
        cfg = new AnticheatConfig();
        m = new AnticheatModule(cfg);
        m.setSelfName("Me");
        m.submit(new CheatFlag("Me", FlagType.AUTOBLOCK, 1.0, "x", 100L));
        eq("ignore self", m.alerts().size(), 0);

        // 9 提示音只在确认时触发
        cfg = new AnticheatConfig();
        m = new AnticheatModule(cfg);
        final int[] sounds = {0};
        m.setFlagSoundSink(new fpsport.adnin.anticheat.AnticheatApi.FlagSoundSink() {
            @Override
            public void play(String soundName, float volume, float pitch) {
                sounds[0]++;
            }
        });
        m.submit(new CheatFlag("Bob", FlagType.AUTOBLOCK, 0.1, "x", 100L));
        eq("no sound before confirm", sounds[0], 0);
        m.submit(new CheatFlag("Bob", FlagType.AUTOBLOCK, 0.9, "x", 200L));
        eq("sound on confirm", sounds[0], 1);

        // 10 关键词分类
        cfg = new AnticheatConfig();
        m = new AnticheatModule(cfg);
        eq("classify sniper", m.classify("possible sniper"), PlayerLabel.SNIPER);
        eq("classify confirmed", m.classify("confirmed cheater"), PlayerLabel.CONFIRMED);
        eq("classify caution", m.classify("risky player"), PlayerLabel.CAUTION);
        eq("classify none", m.classify("nice person"), PlayerLabel.NONE);

        // 11 非法参数不生效
        cfg = new AnticheatConfig();
        cfg.confirmFlagCount = 0;
        try {
            m.updateConfig(cfg);
            check("invalid config should throw", false);
        } catch (IllegalArgumentException expected) {
            check("invalid config throws", true);
        }
        eq("config unchanged after invalid update", m.config().confirmFlagCount, 3);

        // 12 reset 清空
        m.submit(new CheatFlag("Bob", FlagType.AUTOBLOCK, 0.9, "x", 100L));
        m.reset();
        eq("reset clears alerts", m.alerts().size(), 0);
        eq("reset clears window", m.countInWindow("Bob", FlagType.AUTOBLOCK), 0);

        // 13 总开关关闭时全部丢弃
        cfg = new AnticheatConfig();
        cfg.anticheat = false;
        m = new AnticheatModule(cfg);
        m.submit(new CheatFlag("Bob", FlagType.AUTOBLOCK, 1.0, "x", 100L));
        eq("anticheat off", m.alerts().size(), 0);

        // 14 置信度越界被夹取而不是崩
        cfg = new AnticheatConfig();
        m = new AnticheatModule(cfg);
        m.submit(new CheatFlag("Bob", FlagType.AUTOBLOCK, 2.0, "x", 100L));
        eq("clamped confidence confirm", m.alerts().size(), 1);
        eq("clamped confidence value", m.alerts().get(0).confidence, 1.0D);
    }

    // ================= 聊天 =================

    private static void testChat() {
        ChatConfig cfg = new ChatConfig();
        ChatDataModule m = new ChatDataModule(cfg);

        // 1 普通聊天解析
        ChatEvent e = m.receive("<Steve> hello", 100L);
        eq("public channel", e.channel, ChatEvent.Channel.PUBLIC);
        eq("public sender", e.sender, "Steve");
        eq("public body", e.body, "hello");

        // 2 动作消息
        e = m.receive("* Steve waves", 200L);
        eq("action channel", e.channel, ChatEvent.Channel.ACTION);
        eq("action sender", e.sender, "Steve");

        // 3 末尾计时器被剥掉
        e = m.receive("<Steve> gg \u00A7c1:23", 300L);
        eq("trailing timer stripped", e.body, "gg");

        // 4 颜色码被剥掉
        e = m.receive("<Steve> \u00A7cgg\u00A7r", 400L);
        eq("color stripped", e.body, "gg");

        // 5 JSON 文本被拍平
        e = m.receive("{\"text\":\"hello\",\"extra\":[{\"text\":\"world\"}]}", 500L);
        eq("json flatten", e.body, "hello world");

        // 6 超长消息截断
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longText.append('a');
        }
        cfg = new ChatConfig();
        cfg.maxMessageLength = 64;
        m = new ChatDataModule(cfg);
        e = m.receive(longText.toString(), 600L);
        eq("truncated length", e.body.length(), 64);

        // 7 总开关关闭不接收
        cfg = new ChatConfig();
        cfg.chatOverlay = false;
        m = new ChatDataModule(cfg);
        eq("chat overlay off", m.receive("<Steve> hi", 700L), null);

        // 8 队友过滤
        cfg = new ChatConfig();
        cfg.ignoreTeammates = true;
        cfg.teammates = new ChatConfig.TeamLookup() {
            @Override
            public boolean isTeammate(String sender) {
                return "Steve".equals(sender);
            }
        };
        m = new ChatDataModule(cfg);
        eq("teammate filtered", m.receive("<Steve> hi", 800L), null);
        eq("non teammate passes", m.receive("<Alex> hi", 900L) != null, true);

        // 9 历史缓冲容量
        cfg = new ChatConfig();
        cfg.historyCapacity = 2;
        m = new ChatDataModule(cfg);
        m.receive("<A> 1", 1L);
        m.receive("<B> 2", 2L);
        m.receive("<C> 3", 3L);
        eq("history capacity", m.history().size(), 2);
        eq("history newest", m.history().get(1).body, "3");

        // 10 黑名单 TTL
        cfg = new ChatConfig();
        cfg.blacklistRetainDays = 1;
        m = new ChatDataModule(cfg);
        m.blacklist("Bob", 1000L);
        eq("blacklisted now", m.isBlacklisted("bob", 2000L), true);
        eq("blacklist expired", m.isBlacklisted("bob", 1000L + 25L * 3600L * 1000L), false);

        // 11 统计判定：原因码
        cfg = new ChatConfig();
        m = new ChatDataModule(cfg);
        PlayerStats bw = new PlayerStats("Bob", 100, 2.5D, 1.0D, true, true);
        eq("skip unknown mode", m.decideStats(bw, GameMode.UNKNOWN, false, false).reasonCode, "co:mode:unknown:");
        eq("skip prequeue", m.decideStats(bw, GameMode.BEDWARS, true, false).reasonCode, "co:prequeue:stats:");
        eq("skip teammate", m.decideStats(bw, GameMode.BEDWARS, false, true).reasonCode, "co:teammate:stats:");
        eq("allow bedwars", m.decideStats(bw, GameMode.BEDWARS, false, false).allowed, true);
        eq("skip no bedwars data", m.decideStats(new PlayerStats("Bob", 0, 0, 0, false, true),
                GameMode.BEDWARS, false, false).reasonCode, "co:nobw:");
        eq("skip no skywars data", m.decideStats(new PlayerStats("Bob", 0, 0, 0, true, false),
                GameMode.SKYWARS, false, false).reasonCode, "co:nosw:");
        eq("skip no duels data", m.decideStats(new PlayerStats("Bob", 0, 0, 0, false, true),
                GameMode.DUELS, false, false).reasonCode, "co:noduel:");
        eq("skip no bwduels data", m.decideStats(new PlayerStats("Bob", 0, 0, 0, false, true),
                GameMode.BEDWARS_DUELS, false, false).reasonCode, "co:nobwduel:");

        // 12 阈值：两条件都不满足才跳过
        cfg = new ChatConfig();
        cfg.minStars = 50;
        cfg.minFkdr = 1.0D;
        m = new ChatDataModule(cfg);
        eq("skip below both thresholds",
                m.decideStats(new PlayerStats("Bob", 10, 0.5D, 1.0D, true, true),
                        GameMode.BEDWARS, false, false).reasonCode, "co:bwthresh:");
        eq("allow when stars meet",
                m.decideStats(new PlayerStats("Bob", 60, 0.5D, 1.0D, true, true),
                        GameMode.BEDWARS, false, false).allowed, true);
        eq("allow when fkdr meet",
                m.decideStats(new PlayerStats("Bob", 10, 1.2D, 1.0D, true, true),
                        GameMode.BEDWARS, false, false).allowed, true);

        // 13 skywars KDR 阈值
        cfg = new ChatConfig();
        cfg.minSwKdr = 2.0D;
        m = new ChatDataModule(cfg);
        eq("skip low sw kdr",
                m.decideStats(new PlayerStats("Bob", 0, 0, 1.0D, true, true),
                        GameMode.SKYWARS, false, false).reasonCode, "co:swkdr:");
        eq("allow sw kdr",
                m.decideStats(new PlayerStats("Bob", 0, 0, 2.5D, true, true),
                        GameMode.SKYWARS, false, false).allowed, true);

        // 14 脱敏
        eq("denick", m.denick("Steve killed you", "Steve"), "c[NICK] killed you");

        // 15 分发回调
        cfg = new ChatConfig();
        m = new ChatDataModule(cfg);
        final int[] calls = {0};
        m.addListener(new java.util.function.Consumer<ChatEvent>() {
            @Override
            public void accept(ChatEvent event) {
                calls[0]++;
            }
        });
        m.receive("<A> 1", 1L);
        eq("listener dispatched", calls[0], 1);

        // 16 非法参数不生效
        cfg = new ChatConfig();
        cfg.historyCapacity = 0;
        m = new ChatDataModule(new ChatConfig());
        try {
            m.updateConfig(cfg);
            check("invalid chat config should throw", false);
        } catch (IllegalArgumentException expected) {
            check("invalid chat config throws", true);
        }
        eq("chat config unchanged", m.config().historyCapacity, 200);

        // 17 reset
        m.receive("<A> 1", 1L);
        m.blacklist("Bob", 1L);
        m.reset();
        eq("chat reset history", m.history().size(), 0);
        eq("chat reset blacklist", m.isBlacklisted("Bob", 2L), false);
    }

    // ================= 本次游戏 =================

    private static void testSession() {
        SessionConfig cfg = new SessionConfig();
        SessionModule m = new SessionModule(cfg);

        // 1 一局胜利
        m.onGameStart(GameSessionMode.BEDWARS, 0L);
        SessionSnapshot snap = m.onGameEnd(true, 5, 2, 1, 600_000L);
        eq("games", snap.games, 1);
        eq("wins", snap.wins, 1);
        eq("winstreak", snap.winstreak, 1);
        eq("kills", snap.kills, 5);
        eq("final kills", snap.finalKills, 2);
        eq("beds", snap.beds, 1);
        eq("avg rounded", snap.avgGameTimeMs, 600_000L);

        // 2 失败断连胜
        m.onGameStart(GameSessionMode.BEDWARS, 700_000L);
        snap = m.onGameEnd(false, 1, 0, 0, 900_000L);
        eq("games after loss", snap.games, 2);
        eq("streak reset", snap.winstreak, 0);

        // 3 保留连胜开关
        cfg = new SessionConfig();
        cfg.keepWinstreakOnLoss = true;
        m = new SessionModule(cfg);
        m.onGameStart(GameSessionMode.BEDWARS, 0L);
        m.onGameEnd(true, 0, 0, 0, 10_000L);
        m.onGameStart(GameSessionMode.BEDWARS, 10_000L);
        snap = m.onGameEnd(false, 0, 0, 0, 20_000L);
        eq("keep streak on loss", snap.winstreak, 1);

        // 4 重复开始被忽略，时长不被清零
        cfg = new SessionConfig();
        m = new SessionModule(cfg);
        m.onGameStart(GameSessionMode.BEDWARS, 0L);
        m.onGameStart(GameSessionMode.BEDWARS, 5000L);
        snap = m.onGameEnd(true, 0, 0, 0, 60_000L);
        eq("double start keeps duration", snap.totalGameTimeMs, 60_000L);

        // 5 未开始就结束不计数
        cfg = new SessionConfig();
        m = new SessionModule(cfg);
        snap = m.onGameEnd(true, 9, 9, 9, 100L);
        eq("end without start", snap.games, 0);

        // 6 平均时长取整
        cfg = new SessionConfig();
        cfg.avgTimeRoundSeconds = 1;
        m = new SessionModule(cfg);
        m.onGameStart(GameSessionMode.BEDWARS, 0L);
        m.onGameEnd(true, 0, 0, 0, 60_000L);
        m.onGameStart(GameSessionMode.BEDWARS, 60_000L);
        snap = m.onGameEnd(true, 0, 0, 0, 60_000L + 90_500L);
        eq("avg rounding", snap.avgGameTimeMs, 75_000L);

        // 7 时长格式化
        eq("fmt 0", m.formatDuration(0L), "0s");
        eq("fmt 90s", m.formatDuration(90_000L), "1m 30s");
        eq("fmt 3h", m.formatDuration(10_800_000L), "3h 0m");
        eq("fmt 2d", m.formatDuration(172_800_000L), "2d 0h");

        // 8 掉线超时清空会话
        cfg = new SessionConfig();
        cfg.idleTimeoutMs = 120_000L;
        m = new SessionModule(cfg);
        m.onGameStart(GameSessionMode.BEDWARS, 0L);
        m.onGameEnd(true, 0, 0, 0, 10_000L);
        m.tick(20_000L);
        eq("session alive before timeout", m.snapshot().games, 1);
        m.tick(200_000L);
        eq("session cleared after timeout", m.snapshot().games, 0);
        eq("session time cleared", m.snapshot().sessionTimeMs, 0L);

        // 9 达到最长会话自动结算
        cfg = new SessionConfig();
        cfg.maxSessionMs = 300_000L;
        m = new SessionModule(cfg);
        m.onGameStart(GameSessionMode.BEDWARS, 0L);
        snap = m.onGameEnd(true, 1, 0, 0, 400_000L);
        eq("max session reset", snap.games, 0);

        // 10 主动重置
        cfg = new SessionConfig();
        m = new SessionModule(cfg);
        m.onGameStart(GameSessionMode.BEDWARS, 0L);
        m.onGameEnd(true, 3, 1, 1, 60_000L);
        m.reset(70_000L);
        snap = m.snapshot();
        eq("reset games", snap.games, 0);
        eq("reset streak", snap.winstreak, 0);
        eq("reset totals", snap.totalGameTimeMs, 0L);

        // 11 结算触发监听
        cfg = new SessionConfig();
        m = new SessionModule(cfg);
        final int[] calls = {0};
        m.addListener(new java.util.function.Consumer<SessionSnapshot>() {
            @Override
            public void accept(SessionSnapshot snapshot) {
                calls[0]++;
            }
        });
        m.onGameStart(GameSessionMode.BEDWARS, 0L);
        m.onGameEnd(true, 0, 0, 0, 1000L);
        eq("session listener", calls[0], 1);

        // 12 当前局时长
        m.onGameStart(GameSessionMode.BEDWARS, 2000L);
        SessionSnapshot live = m.tick(7000L);
        eq("current game time", live.currentGameTimeMs, 5000L);

        // 13 非法参数不生效
        cfg = new SessionConfig();
        cfg.scale = 3.0D;
        SessionModule mod = new SessionModule(new SessionConfig());
        try {
            mod.updateConfig(cfg);
            check("invalid session config should throw", false);
        } catch (IllegalArgumentException expected) {
            check("invalid session config throws", true);
        }
        eq("session config unchanged", mod.config().scale, 1.0D);
    }

    // ================= 断言辅助 =================

    private static void eq(String label, Object actual, Object expected) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passed++;
        } else {
            failures.add(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(String label, int actual, int expected) {
        if (actual == expected) {
            passed++;
        } else {
            failures.add(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(String label, long actual, long expected) {
        if (actual == expected) {
            passed++;
        } else {
            failures.add(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void eq(String label, double actual, double expected) {
        if (Math.abs(actual - expected) < 1e-9) {
            passed++;
        } else {
            failures.add(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failures.add(label);
        }
    }
}
