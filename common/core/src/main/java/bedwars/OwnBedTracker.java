package bedwars;

import java.util.List;

/**
 * 自己床的锁定器。逐行等价移植自 Arcveil GameBindingsSnapshot.cpp 的
 * own-bed 选取逻辑：
 *
 * 1) 队伍羊毛证据唯一命中本队颜色的床 -> 立即锁定（TEAM_WOOL），
 *    并且可以覆盖早先由开局锚点得到的 MATCH_SPAWN 锁；
 * 2) 尚未锁定时，用“比赛开始时的玩家位置”作为锚点，取 36 格内最近、
 *    且 |dy| <= 16 的床（MATCH_SPAWN）；
 * 3) 已锁定但当前扫描结果里没有该床，且它距离玩家 <= 96 格（理应已加载）
 *    -> 视为床被拆，清除锁定；距离更远则保留锁定（区块被卸载是正常现象）。
 *
 * 纯逻辑类，无任何 Minecraft / UI 依赖。
 */
public final class OwnBedTracker {

    private static final double MATCH_SPAWN_RADIUS = 36.0;
    private static final double SPAWN_VERTICAL_LIMIT = 16.0;
    private static final double BED_LOADED_RADIUS = 96.0;

    private boolean locked;
    private int lockedX;
    private int lockedY;
    private int lockedZ;
    private OwnBed.Source source = OwnBed.Source.UNKNOWN;

    private boolean anchorValid;
    private double anchorX;
    private double anchorY;
    private double anchorZ;

    /** 比赛刚确认时调用：记录开局锚点并清空旧锁。 */
    public void onMatchStart(double playerX, double playerY, double playerZ) {
        anchorValid = true;
        anchorX = playerX;
        anchorY = playerY;
        anchorZ = playerZ;
        locked = false;
        source = OwnBed.Source.UNKNOWN;
    }

    /** 比赛失效（世界切换）时调用。 */
    public void onMatchEnd() {
        anchorValid = false;
        locked = false;
        source = OwnBed.Source.UNKNOWN;
    }

    public void reset() {
        onMatchEnd();
    }

    /**
     * 每帧更新。
     *
     * @param matchActive 比赛是否已确认
     * @param ownTeam     自己队伍
     * @param beds        当前已扫描到的床（可为 null，表示世界不可用）
     * @param playerX     本地玩家 X
     * @param playerZ     本地玩家 Z
     */
    public OwnBed update(boolean matchActive, Team ownTeam, List<BedMarker> beds,
                         double playerX, double playerZ) {
        if (!matchActive || ownTeam == null || !ownTeam.known() || beds == null) {
            locked = false;
            source = OwnBed.Source.UNKNOWN;
            return OwnBed.unknown();
        }

        BedMarker teamCandidate = null;
        int matchingBeds = 0;
        for (BedMarker bed : beds) {
            if (bed == null || bed.team != ownTeam) continue;
            teamCandidate = bed;
            matchingBeds++;
        }

        if (matchingBeds == 1 && teamCandidate != null
                && (!locked || source == OwnBed.Source.MATCH_SPAWN)) {
            locked = true;
            lockedX = teamCandidate.x;
            lockedY = teamCandidate.y;
            lockedZ = teamCandidate.z;
            source = OwnBed.Source.TEAM_WOOL;
        }

        if (!locked && anchorValid) {
            BedMarker nearest = null;
            double nearestDistanceSq = MATCH_SPAWN_RADIUS * MATCH_SPAWN_RADIUS;
            for (BedMarker bed : beds) {
                if (bed == null) continue;
                final double dx = bed.centerX() - anchorX;
                final double dy = bed.y - anchorY;
                final double dz = bed.centerZ() - anchorZ;
                if (Math.abs(dy) > SPAWN_VERTICAL_LIMIT) continue;
                final double distanceSq = dx * dx + dy * dy + dz * dz;
                if (distanceSq < nearestDistanceSq) {
                    nearestDistanceSq = distanceSq;
                    nearest = bed;
                }
            }
            if (nearest != null) {
                locked = true;
                lockedX = nearest.x;
                lockedY = nearest.y;
                lockedZ = nearest.z;
                source = OwnBed.Source.MATCH_SPAWN;
            }
        }

        if (!locked) return OwnBed.unknown();

        boolean lockedMarkerLoaded = false;
        for (BedMarker bed : beds) {
            if (bed != null && bed.samePosition(lockedX, lockedY, lockedZ)) {
                lockedMarkerLoaded = true;
                break;
            }
        }

        final double bedDx = (lockedX + 0.5) - playerX;
        final double bedDz = (lockedZ + 0.5) - playerZ;
        final boolean bedShouldBeLoaded =
                bedDx * bedDx + bedDz * bedDz <= BED_LOADED_RADIUS * BED_LOADED_RADIUS;
        if (!lockedMarkerLoaded && bedShouldBeLoaded) {
            // 床在应加载范围内却消失了：被拆掉。
            locked = false;
            source = OwnBed.Source.UNKNOWN;
            return OwnBed.unknown();
        }
        return new OwnBed(true, lockedX, lockedY, lockedZ, source);
    }
}
