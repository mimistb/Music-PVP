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
