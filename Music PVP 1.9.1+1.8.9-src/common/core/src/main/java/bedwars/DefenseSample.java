package bedwars;

/** 床周围的一个方块采样点（用于防御统计与队伍归属推断）。 */
public final class DefenseSample {

    public final int x;
    public final int y;
    public final int z;
    public final int blockId;
    public final int metadata;

    public DefenseSample(int x, int y, int z, int blockId, int metadata) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockId = blockId;
        this.metadata = metadata & 0x0F;
    }
}
