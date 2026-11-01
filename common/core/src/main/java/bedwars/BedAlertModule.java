package bedwars;

import java.util.List;

/**
 * 床提醒聚合模块（无 UI）。
 *
 * 把比赛识别、自己床锁定、TAB 名单累积、床威胁判定串成一个入口：
 *
 * <pre>
 *   BedAlertModule module = new BedAlertModule();
 *   module.setEnabled(true);
 *   module.setRadius(8);
 *
 *   MatchInput input = new MatchInput();
 *   input.nowMillis = System.currentTimeMillis();
 *   input.sidebarLines = ...;      // 计分板显示槽 1 的 § 格式化行
 *   input.roster = ...;            // TAB 名单（PlayerIdentity.fromFormattedName）
 *   input.localArmorTeam = ...;    // 皮革胸甲 RGB -> Team.fromLeatherRgb
 *   input.armorTeamMask = MatchTracker.armorMask(localArmorTeam, liveArmorTeams);
 *   input.localEntityId = mc.thePlayer.getEntityId();
 *   input.playerX/Y/Z = ...;
 *   input.beds = ...;              // 已扫描到的床（宿主自己的 BedESP 即可）
 *   input.entities = ...;          // 场上玩家实体
 *   module.update(input);
 *
 *   module.isMatchActive();
 *   module.getOwnTeam();
 *   module.getOwnBed();
 *   module.activeThreats();        // 按距离升序，交给宿主现有的液态玻璃 HUD 绘制
 * </pre>
 *
 * 世界切换（进入/退出世界、维度切换、服务器切换）时必须调用
 * {@link #onWorldChange()}。
 *
 * 纯逻辑类，无任何 Minecraft / UI 依赖。
 */
public final class BedAlertModule {

    private boolean enabled = true;
    private int radius = 8;
    /** 通用模式：任何服务器都按放宽的证据规则识别比赛（见 {@link MatchTracker#update}）。 */
    private boolean relaxed;

    private final MatchTracker matchTracker = new MatchTracker();
    private final OwnBedTracker ownBedTracker = new OwnBedTracker();
    private final ThreatTracker threatTracker = new ThreatTracker();
    private final RosterBook rosterBook = new RosterBook();

    private MatchTracker.State matchState;
    private OwnBed ownBed = OwnBed.unknown();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 配置半径，运行时会被 clamp 到 3..32（与源码一致）。 */
    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = radius;
    }

    /** 是否属于通用模式（床提醒模式 1）。严格模式只在 Hypixel 上生效。 */
    public boolean isRelaxed() {
        return relaxed;
    }

    public void setRelaxed(boolean value) {
        this.relaxed = value;
    }

    /** 世界切换时清空所有状态（比赛锁、床锁、接触记录、名单）。 */
    public void onWorldChange() {
        matchTracker.reset();
        ownBedTracker.reset();
        threatTracker.reset();
        rosterBook.reset();
        ownBed = OwnBed.unknown();
        matchState = null;
    }

    public void update(MatchInput input) {
        if (input == null) return;

        final int bedCount = input.beds == null ? 0 : input.beds.size();
        final SidebarSnapshot sidebar = Text.parseSidebar(input.sidebarLines);

        final MatchTracker.State state = matchTracker.update(sidebar, input.roster,
                input.localArmorTeam, input.armorTeamMask, bedCount, input.localPlayerName,
                relaxed);
        if (state.justActivated) {
            ownBedTracker.onMatchStart(input.playerX, input.playerY, input.playerZ);
        }
        if (state.justDeactivated) {
            ownBedTracker.onMatchEnd();
        }
        matchState = state;

        ownBed = ownBedTracker.update(state.matchActive, state.ownTeam,
                input.beds, input.playerX, input.playerZ);

        // 名单是威胁判定的权威来源：只有比赛确认后才累积。
        final List<PlayerIdentity> published = rosterBook.update(input.roster, state.matchActive);
        threatTracker.update(enabled, radius, input.nowMillis, state.matchActive,
                state.ownTeam, ownBed, input.localEntityId, input.beds,
                input.entities, published);
    }

    public boolean isMatchActive() {
        return matchState != null && matchState.matchActive;
    }

    /** 自己队伍；比赛未确认时为 UNKNOWN。 */
    public Team getOwnTeam() {
        return matchState != null ? matchState.ownTeam : Team.UNKNOWN;
    }

    public OwnBed getOwnBed() {
        return ownBed;
    }

    /** 最近一次 update 的详细证据（Sidebar/TAB/胸甲三路）。 */
    public MatchTracker.State getMatchState() {
        return matchState;
    }

    public List<ThreatContact> activeThreats() {
        return threatTracker.activeThreats();
    }

    public List<ThreatContact> contacts() {
        return threatTracker.contacts();
    }

    public List<PlayerIdentity> roster() {
        return rosterBook.players();
    }
}
