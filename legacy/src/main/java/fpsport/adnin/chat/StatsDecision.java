package fpsport.adnin.chat;

/** 是否输出该玩家统计，以及不输出时的原因码（与二进制里的日志前缀一致）。 */
public final class StatsDecision {

    public static final StatsDecision ALLOW = new StatsDecision(true, "");

    public final boolean allowed;
    /** 形如 {@code co:bwthresh:}；允许时为空串。 */
    public final String reasonCode;

    private StatsDecision(boolean allowed, String reasonCode) {
        this.allowed = allowed;
        this.reasonCode = reasonCode;
    }

    public static StatsDecision skip(String reasonCode) {
        return new StatsDecision(false, reasonCode);
    }

    @Override
    public String toString() {
        return allowed ? "ALLOW" : ("SKIP(" + reasonCode + ")");
    }
}
