package bedwars;

/** 一个已扫描到的床方块（HEAD 坐标 + 脚部坐标 + 推断出的队伍）。 */
public final class BedMarker {

    public final int x;
    public final int y;
    public final int z;
    public final int footX;
    public final int footZ;
    public final Team team;

    public BedMarker(int x, int y, int z, int footX, int footZ) {
        this(x, y, z, footX, footZ, Team.UNKNOWN);
    }

    public BedMarker(int x, int y, int z, int footX, int footZ, Team team) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.footX = footX;
        this.footZ = footZ;
        this.team = team == null ? Team.UNKNOWN : team;
    }

    public BedMarker withTeam(Team newTeam) {
        return new BedMarker(x, y, z, footX, footZ, newTeam);
    }

    /**
     * 从任意一个床方块（HEAD 或 FOOT）及其 metadata 生成 marker。
     *
     * <p>meta bit 0x8 = HEAD；低 2 位是 vanilla {@code FACING}（FOOT -&gt; HEAD）的水平索引
     * （0=+Z、1=-X、2=-Z、3=+X）。这里换算成 HEAD -&gt; FOOT 向量再定位另一端，
     * 所以扫到 HEAD 或扫到 FOOT 都会得到同一个 HEAD 坐标。
     */
    public static BedMarker fromBlock(int x, int y, int z, int meta) {
        final boolean head = (meta & 0x8) != 0;
        final int fx;
        final int fz;
        switch (meta & 0x3) {
            case 1:
                fx = 1;
                fz = 0;
                break;
            case 2:
                fx = 0;
                fz = 1;
                break;
            case 3:
                fx = -1;
                fz = 0;
                break;
            default:
                fx = 0;
                fz = -1;
                break;
        }
        return head
                ? new BedMarker(x, y, z, x + fx, z + fz)
                : new BedMarker(x - fx, y, z - fz, x, z);
    }

    /** 床中心（HEAD 与脚部中点，+0.5 对齐方块中心）。 */
    public double centerX() {
        return (x + footX + 1.0) * 0.5;
    }

    public double centerZ() {
        return (z + footZ + 1.0) * 0.5;
    }

    public boolean samePosition(int px, int py, int pz) {
        return x == px && y == py && z == pz;
    }

    @Override
    public String toString() {
        return "BedMarker{" + x + ',' + y + ',' + z + " foot=" + footX + ',' + footZ
                + " team=" + team + '}';
    }
}
