package fpsport.adnin.session;

/** 会话快照（不可变）。宿主 HUD 只读它，不直接读模块内部计数。 */
public final class SessionSnapshot {

    public final boolean active;
    public final GameSessionMode mode;
    public final int games;
    public final int wins;
    public final int winstreak;
    public final int kills;
    public final int finalKills;
    public final int beds;
    public final long totalGameTimeMs;
    public final long avgGameTimeMs;
    public final long sessionTimeMs;
    public final long currentGameTimeMs;

    public SessionSnapshot(boolean active, GameSessionMode mode, int games, int wins, int winstreak,
                           int kills, int finalKills, int beds, long totalGameTimeMs,
                           long avgGameTimeMs, long sessionTimeMs, long currentGameTimeMs) {
        this.active = active;
        this.mode = mode;
        this.games = games;
        this.wins = wins;
        this.winstreak = winstreak;
        this.kills = kills;
        this.finalKills = finalKills;
        this.beds = beds;
        this.totalGameTimeMs = totalGameTimeMs;
        this.avgGameTimeMs = avgGameTimeMs;
        this.sessionTimeMs = sessionTimeMs;
        this.currentGameTimeMs = currentGameTimeMs;
    }

    @Override
    public String toString() {
        return "Session{games=" + games + ", wins=" + wins + ", ws=" + winstreak
                + ", avgMs=" + avgGameTimeMs + "}";
    }
}
