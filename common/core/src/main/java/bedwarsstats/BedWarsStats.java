package bedwarsstats;

/**
 * 一个玩家的 Bed Wars / 网络统计（纯数据）。
 *
 * 字段与 Arcveil 的 BedWarsStats / statsReady 信号完全对应：
 * stars = achievements.bedwars_level，networkLevel 由 networkExp 换算，
 * 其余为 Hypixel stats.Bedwars 的原始计数。
 *
 * 比率方法保留源码语义：winRate/fkdr 都是“除以 max(1, 分母)”，
 * 即 W/L、FK/FD 比值，而不是百分比。
 */
public final class BedWarsStats {

    public static final long NO_TIMESTAMP = 0L;

    public final String uuid;
    public final String displayName;
    public final long wins;
    public final long losses;
    public final long finalKills;
    public final long finalDeaths;
    public final long bedsBroken;
    public final long bedsLost;
    /** achievements.bedwars_level（0..100000）。 */
    public final int stars;
    /** 当前连胜（0..1000000）。 */
    public final int winStreak;
    /** 网络等级（由 networkExp 换算）。 */
    public final int networkLevel;
    public final long updatedAtMillis;

    public BedWarsStats(String uuid, String displayName,
                        long wins, long losses,
                        long finalKills, long finalDeaths,
                        long bedsBroken, long bedsLost,
                        int stars, int winStreak, int networkLevel,
                        long updatedAtMillis) {
        this.uuid = uuid == null ? "" : uuid;
        this.displayName = displayName == null ? "" : displayName;
        this.wins = wins;
        this.losses = losses;
        this.finalKills = finalKills;
        this.finalDeaths = finalDeaths;
        this.bedsBroken = bedsBroken;
        this.bedsLost = bedsLost;
        this.stars = stars;
        this.winStreak = winStreak;
        this.networkLevel = networkLevel;
        this.updatedAtMillis = updatedAtMillis;
    }

    public static BedWarsStats empty() {
        return new BedWarsStats("", "", 0L, 0L, 0L, 0L, 0L, 0L, 0, 0, 0, NO_TIMESTAMP);
    }

    /** 源码中的 winRate：wins / max(1, losses)。 */
    public double winRate() {
        return wins / (double) Math.max(1L, losses);
    }

    /** 自动名单管线中的 wlr，与 winRate 同一公式。 */
    public double wlr() {
        return winRate();
    }

    public double fkdr() {
        return finalKills / (double) Math.max(1L, finalDeaths);
    }

    public double bblr() {
        return bedsBroken / (double) Math.max(1L, bedsLost);
    }

    @Override
    public String toString() {
        return "BedWarsStats{" + (displayName.isEmpty() ? uuid : displayName)
                + " stars=" + stars + " fkdr=" + String.format(java.util.Locale.ROOT, "%.2f", fkdr())
                + " wlr=" + String.format(java.util.Locale.ROOT, "%.2f", wlr())
                + " bblr=" + String.format(java.util.Locale.ROOT, "%.2f", bblr())
                + " wins=" + wins + " ws=" + winStreak + " lvl=" + networkLevel + '}';
    }
}
