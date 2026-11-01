package fpsport.adnin.anticheat;

/** 一条原始作弊信号（由客户端钩子送入，本模块只做判定与聚合，不检测、不绘制）。 */
public final class CheatFlag {

    public final String player;
    public final FlagType type;
    /** 0.0 ~ 1.0，来自检测端；低于配置阈值时不计入确认。 */
    public final double confidence;
    public final String evidence;
    public final long timestampMs;

    public CheatFlag(String player, FlagType type, double confidence, String evidence, long timestampMs) {
        this.player = player;
        this.type = type;
        this.confidence = confidence;
        this.evidence = evidence;
        this.timestampMs = timestampMs;
    }
}
