package bedwars;

/** 同一 (blockId, metadata) 方块在床周围的环形分布统计。 */
public final class DefenseBlock {

    public final int blockId;
    public final int metadata;
    /** ringCounts[1..10] 有效，ringCounts[0] 不使用。 */
    public final int[] ringCounts = new int[11];

    public DefenseBlock(int blockId, int metadata) {
        this.blockId = blockId;
        this.metadata = metadata;
    }

    void add(int ring) {
        if (ring < 1 || ring > 10) return;
        if (ringCounts[ring] < 0xFFFF) ringCounts[ring]++;
    }

    public int total() {
        int sum = 0;
        for (int ring = 1; ring <= 10; ring++) sum += ringCounts[ring];
        return sum;
    }
}
