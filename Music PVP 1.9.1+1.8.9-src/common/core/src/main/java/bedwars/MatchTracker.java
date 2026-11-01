package bedwars;

import java.util.List;

/**
 * 比赛识别（三路证据）。逐行等价移植自 Arcveil GameBindingsSnapshot.cpp：
 *
 * 1) Sidebar：至少 2 个不同队伍且恰好 1 行包含 YOU -> 立即采信（1 个样本）；
 * 2) TAB 名单：显式队伍标签覆盖 >= 2 人、>= 2 个不同队伍、且能确定自己队伍
 *    （优先显式标签，其次本地皮革胸甲颜色，最后名字颜色）-> 需要 2 个样本；
 * 3) 皮革胸甲兜底：本地胸甲颜色已知 + 至少 2 个不同胸甲队伍 + 已扫描到床
 *    -> 需要 2 个样本。
 *
 * 一旦确认（matchActive = true），只有世界切换（reset）才能清除；
 * 实体/TAB/Sidebar 的瞬时缺失都不会把会话退回大厅模式。
 *
 * relaxed（通用模式，任何服务器）：Sidebar 只要出现 >= 2 个不同队伍即视为有效
 * （不再强制 "YOU" 行，因为其它服务器常把玩家名放在队伍行里却没有 YOU），
 * 自己队伍取 Sidebar ownTeam -> 本地皮革胸甲 -> TAB 名字色 -> TAB 显式标签，
 * 任意一路证据 1 个样本即可激活；胸甲兜底不要求场上 >= 2 种胸甲颜色。
 * relaxed == false 时完全保持上面的严格逻辑（仅 Hypixel）。
 *
 * 纯逻辑类，无任何 Minecraft / UI 依赖。
 */
public final class MatchTracker {

    /** 一次 update 的结果快照。 */
    public static final class State {
        public final boolean matchActive;
        public final Team ownTeam;
        public final boolean justActivated;
        public final boolean justDeactivated;
        public final boolean sidebarValid;
        public final boolean rosterValid;
        public final boolean armorValid;
        public final int stableSamples;
        public final Team candidateTeam;

        State(boolean matchActive, Team ownTeam, boolean justActivated,
              boolean justDeactivated, boolean sidebarValid, boolean rosterValid,
              boolean armorValid, int stableSamples, Team candidateTeam) {
            this.matchActive = matchActive;
            this.ownTeam = ownTeam;
            this.justActivated = justActivated;
            this.justDeactivated = justDeactivated;
            this.sidebarValid = sidebarValid;
            this.rosterValid = rosterValid;
            this.armorValid = armorValid;
            this.stableSamples = stableSamples;
            this.candidateTeam = candidateTeam;
        }
    }

    private boolean matchActive;
    private Team ownTeam = Team.UNKNOWN;
    private Team candidateTeam = Team.UNKNOWN;
    private int stableSamples;
    private int missingCount;

    public boolean isMatchActive() {
        return matchActive;
    }

    public Team ownTeam() {
        return ownTeam;
    }

    /** 世界切换时调用：清除所有比赛状态。 */
    public void reset() {
        matchActive = false;
        ownTeam = Team.UNKNOWN;
        candidateTeam = Team.UNKNOWN;
        stableSamples = 0;
        missingCount = 0;
    }

    /**
     * 生成胸甲队伍位掩码。
     *
     * @param localArmorTeam 本地玩家胸甲颜色
     * @param liveArmorTeams 场上所有玩家（含本地）的胸甲颜色，可为 null
     */
    public static int armorMask(Team localArmorTeam, Iterable<Team> liveArmorTeams) {
        int mask = 0;
        if (localArmorTeam != null && localArmorTeam.known()) {
            mask |= 1 << localArmorTeam.index();
        }
        if (liveArmorTeams != null) {
            for (Team team : liveArmorTeams) {
                if (team != null && team.known()) mask |= 1 << team.index();
            }
        }
        return mask;
    }

    /**
     * 每帧更新比赛状态（严格模式，等价于 relaxed == false）。
     *
     * @param sidebar           已解析的 Sidebar（可为 null）
     * @param roster            当前 TAB 名单（可为 null）
     * @param localArmorTeam    本地玩家皮革胸甲颜色
     * @param armorTeamMask     场上所有玩家胸甲颜色掩码（见 {@link #armorMask}）
     * @param bedCount          当前扫描到的床数量
     * @param localPlayerName   本地玩家名（可为空）
     */
    public State update(SidebarSnapshot sidebar, List<PlayerIdentity> roster,
                        Team localArmorTeam, int armorTeamMask, int bedCount,
                        String localPlayerName) {
        return update(sidebar, roster, localArmorTeam, armorTeamMask, bedCount,
                localPlayerName, false);
    }

    /**
     * 每帧更新比赛状态（带通用模式开关）。
     *
     * @param sidebar           已解析的 Sidebar（可为 null）
     * @param roster            当前 TAB 名单（可为 null）
     * @param localArmorTeam    本地玩家皮革胸甲颜色
     * @param armorTeamMask     场上所有玩家胸甲颜色掩码（见 {@link #armorMask}）
     * @param bedCount          当前扫描到的床数量
     * @param localPlayerName   本地玩家名（可为空）
     * @param relaxed           通用模式：Sidebar 只要有 2 个不同队伍即成立（不要求 YOU 行），
     *                          胸甲兜底不要求 >= 2 种胸甲颜色，任意一路证据 1 个样本即激活
     */
    public State update(SidebarSnapshot sidebar, List<PlayerIdentity> roster,
                        Team localArmorTeam, int armorTeamMask, int bedCount,
                        String localPlayerName, boolean relaxed) {
        final boolean wasActive = matchActive;

        Team rosterTaggedOwnTeam = Team.UNKNOWN;
        Team rosterColourOwnTeam = Team.UNKNOWN;
        int rosterTeamMask = 0;
        int taggedPlayers = 0;
        if (roster != null) {
            for (PlayerIdentity player : roster) {
                if (player == null) continue;
                final Team tagged = player.taggedTeam;
                if (!tagged.known()) continue;
                taggedPlayers++;
                rosterTeamMask |= 1 << tagged.index();
                if (localPlayerName != null && !localPlayerName.isEmpty()
                        && player.name.equals(localPlayerName)) {
                    rosterTaggedOwnTeam = tagged;
                    rosterColourOwnTeam = player.colorTeam;
                }
            }
        }
        final int rosterDistinctTeams = bitCount(rosterTeamMask);
        final Team rosterOwnTeam = rosterTaggedOwnTeam.known() ? rosterTaggedOwnTeam
                : localArmorTeam != null && localArmorTeam.known() ? localArmorTeam
                : rosterColourOwnTeam;
        final boolean rosterValid = taggedPlayers >= 2 && rosterDistinctTeams >= 2
                && rosterOwnTeam.known();

        // SidebarSnapshot.valid 含 "YOU 行恰好 1 行" 的硬条件；通用模式直接看不同队伍数
        final boolean sidebarValid = sidebar != null
                && (relaxed ? sidebar.distinctTeams >= 2 : sidebar.valid);

        final boolean armorValid = localArmorTeam != null && localArmorTeam.known()
                && bedCount > 0 && (relaxed || bitCount(armorTeamMask) >= 2);

        final boolean evidenceValid = sidebarValid || rosterValid || armorValid;
        final Team candidate;
        if (relaxed) {
            // 通用模式：Sidebar ownTeam -> 本地皮革胸甲 -> TAB 名字色 -> TAB 显式标签
            candidate = relaxedOwnTeam(sidebarValid ? sidebar : null, localArmorTeam,
                    rosterColourOwnTeam, rosterTaggedOwnTeam);
        } else {
            candidate = sidebarValid ? sidebar.ownTeam
                    : rosterValid ? rosterOwnTeam
                    : localArmorTeam;
        }

        boolean justActivated = false;
        boolean justDeactivated = false;
        if (evidenceValid) {
            missingCount = 0;
            if (candidate == candidateTeam) {
                if (stableSamples < 255) stableSamples++;
            } else {
                candidateTeam = candidate;
                stableSamples = 1;
            }
            // Sidebar 的 YOU 行由服务端生成，可以立即采信；
            // TAB 名单与胸甲兜底需要连续两个样本，避免大厅误判。
            // 通用模式下其它服务器不保证有 YOU 行，任意一路证据都只取 1 个样本。
            final int requiredStableSamples = relaxed ? 1 : (sidebarValid ? 1 : 2);
            if (stableSamples >= requiredStableSamples && !matchActive) {
                matchActive = true;
                ownTeam = candidate;
                justActivated = true;
            }
            // 通用模式开局可能先只有队伍行/床位证据，自己队伍后到时补上（matchActive 不受影响）
            if (matchActive && relaxed && !ownTeam.known() && candidate.known()) {
                ownTeam = candidate;
            }
        } else if (matchActive) {
            if (missingCount < 255) missingCount++;
        } else {
            candidateTeam = Team.UNKNOWN;
            stableSamples = 0;
        }

        if (!matchActive && wasActive) justDeactivated = true;
        return new State(matchActive, matchActive ? ownTeam : Team.UNKNOWN,
                justActivated, justDeactivated, sidebarValid, rosterValid,
                armorValid, stableSamples, candidateTeam);
    }

    /**
     * 通用模式的自己队伍候选：Sidebar ownTeam -> 本地皮革胸甲 -> TAB 名字色 -> TAB 显式标签。
     * 全都未知时返回 UNKNOWN（比赛仍可激活，只是自己床 / 队伍色暂时无解）。
     */
    private static Team relaxedOwnTeam(SidebarSnapshot sidebar, Team localArmorTeam,
                                       Team rosterColourTeam, Team rosterTaggedTeam) {
        if (sidebar != null && sidebar.ownTeam.known()) return sidebar.ownTeam;
        if (localArmorTeam != null && localArmorTeam.known()) return localArmorTeam;
        if (rosterColourTeam != null && rosterColourTeam.known()) return rosterColourTeam;
        if (rosterTaggedTeam != null && rosterTaggedTeam.known()) return rosterTaggedTeam;
        return Team.UNKNOWN;
    }

    private static int bitCount(int mask) {
        int count = 0;
        for (int value = mask; value != 0; value >>>= 1) count += value & 1;
        return count;
    }
}
