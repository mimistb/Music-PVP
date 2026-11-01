package fpsport.adnin.anticheat;

/** 一次“确认”告警。模块只产出数据，画不画由宿主 UI 决定。 */
public final class Alert {

    public final String player;
    public final FlagType type;
    public final PlayerLabel label;
    /** 本次确认时统计到的信号条数。 */
    public final int flagCount;
    public final double confidence;
    public final String evidence;
    public final long timestampMs;

    public Alert(String player, FlagType type, PlayerLabel label, int flagCount,
                 double confidence, String evidence, long timestampMs) {
        this.player = player;
        this.type = type;
        this.label = label;
        this.flagCount = flagCount;
        this.confidence = confidence;
        this.evidence = evidence;
        this.timestampMs = timestampMs;
    }

    @Override
    public String toString() {
        return "Alert{" + player + ", " + type + ", " + label + ", count=" + flagCount + ", conf=" + confidence + "}";
    }
}
