package fpsport.adnin.session;

/** 本次游戏模块的全部可调参数；键名对应 AdOverlay 配置里恢复出的键。 */
public final class SessionConfig {

    /** {@code sessionStats}：模块总开关。 */
    public boolean sessionStats = true;

    // ---- 下列为「显示哪些指标」的数据开关（宿主 UI 读取，模块不绘制） ----
    /** {@code sessionStatsGame}。 */
    public boolean showGame = true;
    /** {@code sessionStatsFinalKills}。 */
    public boolean showFinalKills = true;
    /** {@code sessionStatsBeds}。 */
    public boolean showBeds = true;
    /** {@code sessionStatsKills}。 */
    public boolean showKills = true;
    /** {@code sessionStatsWins}。 */
    public boolean showWins = true;
    /** {@code sessionStatsWinstreak}。 */
    public boolean showWinstreak = true;
    /** {@code sessionStatsSessionGames}。 */
    public boolean showSessionGames = true;
    /** {@code sessionStatsGameTime}。 */
    public boolean showGameTime = true;
    /** {@code sessionStatsAvgTime}。 */
    public boolean showAvgTime = true;
    /** {@code sessionStatsSessionTime}。 */
    public boolean showSessionTime = true;
    /** {@code sessionStatsSlumberTickets}。 */
    public boolean showSlumberTickets = false;
    /** {@code sessionStatsTextShadow}：文字阴影（宿主 UI 用）。 */
    public boolean textShadow = true;
    /** {@code sessionStatsBgOpacity}：0~255。 */
    public int backgroundOpacity = 160;
    /** {@code sessionStatsScale}：0.5~2.0。 */
    public double scale = 1.0D;

    // ---- 行为阈值（INFERRED） ----

    /** 掉线后会话保持时间（对应恢复出的 {@code expiresIn:120s}）。默认 120000ms。 */
    public long idleTimeoutMs = 120_000L;
    /** 失败时是否保留连胜。默认 false（原版连胜被断）。 */
    public boolean keepWinstreakOnLoss = false;
    /** 会话达到该时长后自动结算（0 = 不限制）。默认 0。 */
    public long maxSessionMs = 0L;
    /** {@code sessionStatsAvgTime} 的平均时长取整粒度（秒）。默认 1。 */
    public int avgTimeRoundSeconds = 1;

    public SessionConfig() {
    }

    public void validate() {
        if (idleTimeoutMs < 0L) {
            throw new IllegalArgumentException("idleTimeoutMs must be >= 0");
        }
        if (maxSessionMs < 0L) {
            throw new IllegalArgumentException("maxSessionMs must be >= 0");
        }
        if (avgTimeRoundSeconds < 1) {
            throw new IllegalArgumentException("avgTimeRoundSeconds must be >= 1");
        }
        if (backgroundOpacity < 0 || backgroundOpacity > 255) {
            throw new IllegalArgumentException("backgroundOpacity must be in [0,255]");
        }
        if (Double.isNaN(scale) || Double.isInfinite(scale) || scale < 0.5D || scale > 2.0D) {
            throw new IllegalArgumentException("scale must be in [0.5,2.0]");
        }
    }

    public SessionConfig copy() {
        SessionConfig c = new SessionConfig();
        c.sessionStats = sessionStats;
        c.showGame = showGame;
        c.showFinalKills = showFinalKills;
        c.showBeds = showBeds;
        c.showKills = showKills;
        c.showWins = showWins;
        c.showWinstreak = showWinstreak;
        c.showSessionGames = showSessionGames;
        c.showGameTime = showGameTime;
        c.showAvgTime = showAvgTime;
        c.showSessionTime = showSessionTime;
        c.showSlumberTickets = showSlumberTickets;
        c.textShadow = textShadow;
        c.backgroundOpacity = backgroundOpacity;
        c.scale = scale;
        c.idleTimeoutMs = idleTimeoutMs;
        c.keepWinstreakOnLoss = keepWinstreakOnLoss;
        c.maxSessionMs = maxSessionMs;
        c.avgTimeRoundSeconds = avgTimeRoundSeconds;
        return c;
    }
}
