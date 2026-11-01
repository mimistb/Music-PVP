package bedwars;

import java.util.List;

/**
 * 一次床提醒更新的输入。宿主（1.8.9 Forge 模组）每帧/每 tick 填好后
 * 交给 {@link BedAlertModule#update(MatchInput)}。
 *
 * 所有字段都是纯数据，不含任何 Minecraft 类型。
 */
public final class MatchInput {

    /** 当前时间（毫秒）。用 System.currentTimeMillis() 即可。 */
    public long nowMillis;
    /** Sidebar（计分板显示槽 1）的原始格式化行，含 § 格式码。 */
    public List<String> sidebarLines;
    /** 本帧从 TAB 名单观察到的玩家（未做单调累积）。 */
    public List<PlayerIdentity> roster;
    /** 本地玩家名（用于在 Sidebar 之外确定自己队伍）。 */
    public String localPlayerName = "";
    /** 本地玩家皮革胸甲颜色。 */
    public Team localArmorTeam = Team.UNKNOWN;
    /** 场上所有玩家胸甲颜色掩码，可用 {@link MatchTracker#armorMask} 生成。 */
    public int armorTeamMask;
    /** 本地玩家 entityId（用于跳过自己）。 */
    public int localEntityId = -1;
    public double playerX;
    public double playerY;
    public double playerZ;
    /** 当前扫描到的床；null 表示世界/扫描器暂不可用。 */
    public List<BedMarker> beds;
    /** 场上实体（只填玩家即可）。 */
    public List<ThreatEntity> entities;
}
