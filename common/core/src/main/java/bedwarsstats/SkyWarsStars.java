package bedwarsstats;

/**
 * Hypixel SkyWars 星级换算（纯数据）。
 *
 * <p>与 HyInfo 的 {@code getSkywarsStars} 完全一致：输入
 * {@code stats.SkyWars.skywars_experience}，按固定阈值表换算成星级；
 * 19 星以上每 5000 经验加一星（从 19 星时的 26800 经验起算）。
 */
public final class SkyWarsStars {

    private SkyWarsStars() {
    }

    public static int fromExperience(double experience) {
        final long exp = (long) Math.max(0.0, experience);
        if (exp >= 31800L) return (int) (19L + (exp - 26800L) / 5000L);
        if (exp >= 26800L) return 19;
        if (exp >= 22300L) return 18;
        if (exp >= 18300L) return 17;
        if (exp >= 14800L) return 16;
        if (exp >= 11800L) return 15;
        if (exp >= 9300L) return 14;
        if (exp >= 7300L) return 13;
        if (exp >= 5550L) return 12;
        if (exp >= 4000L) return 11;
        if (exp >= 2750L) return 10;
        if (exp >= 1750L) return 9;
        if (exp >= 1000L) return 8;
        if (exp >= 500L) return 7;
        if (exp >= 250L) return 6;
        if (exp >= 125L) return 5;
        if (exp >= 75L) return 4;
        if (exp >= 35L) return 3;
        if (exp >= 10L) return 2;
        return 1;
    }
}
