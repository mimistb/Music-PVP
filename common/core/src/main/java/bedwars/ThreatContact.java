package bedwars;

/**
 * 一个敌人接触记录。只保存数据，不含任何渲染/动画字段。
 *
 * inside 表示该敌人当前处于“床威胁”半径内（带迟滞），可直接用于
 * 宿主自己的液态玻璃提醒面板 / 提示条。
 */
public final class ThreatContact {

    public int entityId = -1;
    public String playerName = "";
    public String uuid = "";
    public Team team = Team.UNKNOWN;
    /** 记录该接触时锁定的自己床（HEAD 坐标）。 */
    public int bedX;
    public int bedY;
    public int bedZ;
    /** 与最近一次锁定的自己床的距离（格）。 */
    public double distance;
    public long lastSeenMillis;
    public boolean inside;
    public boolean invisible;

    void reset() {
        entityId = -1;
        playerName = "";
        uuid = "";
        team = Team.UNKNOWN;
        bedX = 0;
        bedY = 0;
        bedZ = 0;
        distance = 0.0;
        lastSeenMillis = 0L;
        inside = false;
        invisible = false;
    }

    @Override
    public String toString() {
        return "ThreatContact{" + (playerName.isEmpty() ? "?" : playerName)
                + " id=" + entityId + " team=" + team
                + " dist=" + String.format(java.util.Locale.ROOT, "%.2f", distance)
                + " inside=" + inside + '}';
    }
}
