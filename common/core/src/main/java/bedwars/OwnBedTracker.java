package bedwars;

import java.util.List;

/**
 * 自己床的锁定器。逐行等价移植自 Arcveil GameBindingsSnapshot.cpp 的
 * own-bed 选取逻辑，并做了两处修正：
 *
 * 1) 队伍羊毛证据唯一命中本队颜色的床 -> 立即锁定（TEAM_WOOL），
 *    并且可以覆盖早先由开局锚点得到的 MATCH_SPAWN 锁；
 * 2) 尚未锁定时，用“开局锚点”（首次进入本局世界时的玩家位置）作为锚点，
 *    取 36 格内最近、且 |dy| <= 16 的床（MATCH_SPAWN）；
 * 3) 已锁定但当前扫描结果里没有该床时，优先看宿主的直接探测结果：
 *    - PROBE_ABSENT   : 区块已加载但那里已不是床 -> 被拆，清除锁定；
 *    - PROBE_UNLOADED : 区块未加载 -> 无法判断，保留锁定；
 *    - PROBE_PRESENT  : 那里仍是床（只是不在本次扫描盒内）-> 保留锁定；
 *    - PROBE_NONE     : 没有探测能力时回退到“水平距离 <= 扫描半径”
 *      的保守判据（旧的 96 格判据大于宿主 40 格扫描半径，会误报失守）。
 *
 * 被拆确认后本局不再重新锁定任何床（否则会在原位置附近锁到别人的床）。
 *
 * 纯逻辑类，无任何 Minecraft / UI 依赖。
 */
public final class OwnBedTracker {

    /** 宿主本轮没有提供探测结果（纯逻辑调用 / 旧宿主）。 */
    public static final int PROBE_NONE = 0;
    /** 已锁床所在区块已加载，且该位置仍是床。 */
    public static final int PROBE_PRESENT = 1;
    /** 已锁床所在区块已加载，但该位置已不是床：被拆。 */
    public static final int PROBE_ABSENT = 2;
    /** 已锁床所在区块未加载，无法判断。 */
    public static final int PROBE_UNLOADED = 3;

    private static final double MATCH_SPAWN_RADIUS = 36.0;
    private static final double SPAWN_VERTICAL_LIMIT = 16.0;
    /** 与宿主床扫描半径一致：没有探测结果时的保守回退判据。 */
    private static final double SCAN_COVER_RADIUS = 40.0;

    private boolean locked;
    private int lockedX;
    private int lockedY;
    private int lockedZ;
    private OwnBed.Source source = OwnBed.Source.UNKNOWN;
    /** 本局已确认自己床被拆：不再重新锁定。 */
    private boolean destroyed;

    private boolean anchorValid;
    private double anchorX;
    private double anchorY;
    private double anchorZ;

    /**
     * 记录开局锚点。只保留第一次记录的位置：比赛确认往往晚于出生点，
     * 等到 justActivated 再记会锚到玩家已经走到的位置（可能是敌方床）。
     */
    public void recordAnchor(double playerX, double playerY, double playerZ) {
        if (anchorValid) return;
        anchorValid = true;
        anchorX = playerX;
        anchorY = playerY;
        anchorZ = playerZ;
    }

    /** 比赛刚确认时调用：只补锚点，不清除可能已由 TEAM_WOOL 建立的锁。 */
    public void onMatchStart(double playerX, double playerY, double playerZ) {
        recordAnchor(playerX, playerY, playerZ);
    }

    /** 比赛失效（世界切换）时调用。 */
    public void onMatchEnd() {
        anchorValid = false;
        locked = false;
        destroyed = false;
        source = OwnBed.Source.UNKNOWN;
    }

    public void reset() {
        onMatchEnd();
    }

    /** 本局自己床是否已被确认拆掉。 */
    public boolean isDestroyed() {
        return destroyed;
    }

    /**
     * 每帧更新（无探测能力时）。
     */
    public OwnBed update(boolean matchActive, Team ownTeam, List<BedMarker> beds,
                         double playerX, double playerZ) {
        return update(matchActive, ownTeam, beds, playerX, playerZ, PROBE_NONE);
    }

    /**
     * 每帧更新。
     *
     * @param matchActive 比赛是否已确认
     * @param ownTeam     自己队伍
     * @param beds        当前已扫描到的床（不可为 null；null 表示世界不可用）
     * @param playerX     本地玩家 X
     * @param playerZ     本地玩家 Z
     * @param probe       宿主对上一次已锁床坐标的直接探测结果（PROBE_*）
     */
    public OwnBed update(boolean matchActive, Team ownTeam, List<BedMarker> beds,
                         double playerX, double playerZ, int probe) {
        if (!matchActive || ownTeam == null || !ownTeam.known() || beds == null) {
            locked = false;
            source = OwnBed.Source.UNKNOWN;
            return OwnBed.unknown();
        }

        if (!destroyed) {
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
        }

        if (!locked) return OwnBed.unknown();

        boolean lockedMarkerLoaded = false;
        for (BedMarker bed : beds) {
            if (bed != null && bed.samePosition(lockedX, lockedY, lockedZ)) {
                lockedMarkerLoaded = true;
                break;
            }
        }

        if (!lockedMarkerLoaded) {
            final boolean broken;
            if (probe == PROBE_ABSENT) {
                broken = true;
            } else if (probe == PROBE_PRESENT || probe == PROBE_UNLOADED) {
                broken = false;
            } else {
                final double bedDx = (lockedX + 0.5) - playerX;
                final double bedDz = (lockedZ + 0.5) - playerZ;
                broken = bedDx * bedDx + bedDz * bedDz <= SCAN_COVER_RADIUS * SCAN_COVER_RADIUS;
            }
            if (broken) {
                // 床在应加载范围内却消失了：被拆掉。
                locked = false;
                destroyed = true;
                source = OwnBed.Source.UNKNOWN;
                return OwnBed.unknown();
            }
        }
        return new OwnBed(true, lockedX, lockedY, lockedZ, source);
    }
}
