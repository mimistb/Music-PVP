package bedwars;

/** 场上一个实体（只取床威胁判定需要的字段）。 */
public final class ThreatEntity {

    public final int entityId;
    public final double x;
    public final double y;
    public final double z;
    /** 32 位十六进制 UUID，可为空字符串。 */
    public final String uuid;
    /** 当前实体名，可为空字符串（隐身/重建 wrapper 时可能短暂取不到）。 */
    public final String name;
    public final boolean player;
    public final boolean invisible;

    public ThreatEntity(int entityId, double x, double y, double z,
                        String uuid, String name, boolean player, boolean invisible) {
        this.entityId = entityId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.uuid = uuid == null ? "" : uuid;
        this.name = name == null ? "" : name;
        this.player = player;
        this.invisible = invisible;
    }

    public ThreatEntity(int entityId, double x, double y, double z,
                        String uuid, String name) {
        this(entityId, x, y, z, uuid, name, true, false);
    }
}
