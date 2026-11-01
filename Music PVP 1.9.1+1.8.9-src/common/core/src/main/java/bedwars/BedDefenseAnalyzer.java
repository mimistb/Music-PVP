package bedwars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 床防御分析：把床周围方块折叠成环形统计，并用队伍羊毛证据推断床的归属。
 * 阈值与源码一致：只统计 ring 1..10，羊毛只在 ring <= 6 参与证据（权重 7-ring），
 * 需要最佳证据 >= 4 且比第二名领先 >= 2 才认定队伍（否则保持 Unknown，宁可不判）。
 *
 * 纯逻辑类，无任何 Minecraft / UI 依赖。
 */
public final class BedDefenseAnalyzer {

    public static final int MAX_BLOCKS = 8;

    private BedDefenseAnalyzer() {
    }

    public static BedDefense analyze(BedMarker marker, Iterable<DefenseSample> samples) {
        final List<DefenseBlock> blocks = new ArrayList<>(MAX_BLOCKS);
        final int[] woolEvidence = new int[8];
        if (samples != null) {
            for (DefenseSample sample : samples) {
                if (sample == null) continue;
                final int vertical = sample.y - marker.y;
                if (vertical < 0 || vertical > 10) continue;
                final int dx = Math.min(Math.abs(sample.x - marker.x), Math.abs(sample.x - marker.footX));
                final int dz = Math.min(Math.abs(sample.z - marker.z), Math.abs(sample.z - marker.footZ));
                final int ring = Math.max(dx, Math.max(dz, vertical));
                if (ring < 1 || ring > 10) continue;

                if (sample.blockId == 35 && ring <= 6) {
                    final int woolIndex = Team.fromWoolMetadata(sample.metadata).index();
                    if (woolIndex >= 0 && woolIndex < woolEvidence.length) {
                        woolEvidence[woolIndex] += 7 - ring;
                    }
                }

                int normalizedMeta = sample.metadata;
                if (sample.blockId == 17 || sample.blockId == 162) normalizedMeta &= 3;

                DefenseBlock summary = null;
                for (DefenseBlock candidate : blocks) {
                    if (candidate.blockId == sample.blockId && candidate.metadata == normalizedMeta) {
                        summary = candidate;
                        break;
                    }
                }
                if (summary == null && blocks.size() < MAX_BLOCKS) {
                    summary = new DefenseBlock(sample.blockId, normalizedMeta);
                    blocks.add(summary);
                }
                if (summary != null) summary.add(ring);
            }
        }

        int bestEvidence = 0;
        int secondEvidence = 0;
        int bestTeam = -1;
        for (int index = 0; index < woolEvidence.length; index++) {
            final int evidence = woolEvidence[index];
            if (evidence > bestEvidence) {
                secondEvidence = bestEvidence;
                bestEvidence = evidence;
                bestTeam = index;
            } else if (evidence > secondEvidence) {
                secondEvidence = evidence;
            }
        }

        final Team team = bestTeam >= 0 && bestEvidence >= 4
                && bestEvidence >= secondEvidence + 2
                ? Team.fromIndex(bestTeam)
                : Team.UNKNOWN;

        return new BedDefense(Collections.unmodifiableList(blocks), team, woolEvidence.clone());
    }

    /** 分析结果。 */
    public static final class BedDefense {
        public final List<DefenseBlock> blocks;
        public final Team team;
        public final int[] woolEvidence;

        BedDefense(List<DefenseBlock> blocks, Team team, int[] woolEvidence) {
            this.blocks = blocks;
            this.team = team;
            this.woolEvidence = woolEvidence;
        }
    }
}
