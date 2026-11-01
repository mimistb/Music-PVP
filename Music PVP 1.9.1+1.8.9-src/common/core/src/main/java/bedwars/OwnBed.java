package bedwars;

/** 自己床的锁定状态（HEAD 坐标 + 锁定来源）。 */
public final class OwnBed {

    /** 锁定来源：队伍羊毛证据 / 开局锚点最近床。 */
    public enum Source {
        UNKNOWN,
        TEAM_WOOL,
        MATCH_SPAWN
    }

    private static final OwnBed NONE = new OwnBed(false, 0, 0, 0, Source.UNKNOWN);

    public final boolean known;
    public final int x;
    public final int y;
    public final int z;
    public final Source source;

    public OwnBed(boolean known, int x, int y, int z, Source source) {
        this.known = known;
        this.x = x;
        this.y = y;
        this.z = z;
        this.source = source == null ? Source.UNKNOWN : source;
    }

    public static OwnBed unknown() {
        return NONE;
    }

    @Override
    public String toString() {
        return known ? "OwnBed{" + x + ',' + y + ',' + z + " from " + source + '}'
                : "OwnBed{unknown}";
    }
}
