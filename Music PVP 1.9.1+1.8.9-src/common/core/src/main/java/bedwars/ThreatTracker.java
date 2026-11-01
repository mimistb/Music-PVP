package bedwars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 床威胁判定。逐行等价移植自 Arcveil overlay_renderer_toasts.cpp 的
 * updateBedThreatAlerts：
 *
 * - 只在“床提醒开启 + 比赛已确认 + 自己队伍已知 + 自己床已锁定”时工作；
 * - 入场半径 clamp(radius, 3, 32)，离场半径 = 入场半径 + 1.25（迟滞）；
 * - 以已确认的 TAB 名单为权威：名单里查不到该实体 -> 直接跳过（不误报）；
 * - 队友跳过、本地玩家跳过；
 * - 隐身不取消威胁（名单身份优先于实时胸甲）；
 * - 实体名/UUID 短暂缺失时，复用同一 entityId + 同一张床的接触记录，
 *   防止 NetworkPlayerInfo 重建造成提醒闪烁；不同名字则拒绝复用，
 *   以防护 entityId 复用；
 * - 500ms 未再见到该实体 -> inside = false。
 *
 * 纯逻辑类，无任何 Minecraft / UI 依赖。
 */
public final class ThreatTracker {

    public static final int MAX_CONTACTS = 64;

    private static final long CONTACT_EXPIRY_MILLIS = 500L;
    private static final double LEAVE_MARGIN = 1.25;

    private final ThreatContact[] contacts = new ThreatContact[MAX_CONTACTS];

    public ThreatTracker() {
        for (int index = 0; index < contacts.length; index++) {
            contacts[index] = new ThreatContact();
        }
    }

    public void reset() {
        for (ThreatContact contact : contacts) contact.reset();
    }

    /**
     * 每帧更新。
     *
     * @param enabled       床提醒总开关
     * @param radius        配置半径（内部 clamp 到 3..32）
     * @param nowMillis     当前时间（毫秒，单调即可）
     * @param matchActive   比赛是否已确认
     * @param ownTeam       自己队伍
     * @param ownBed        已锁定的自己床
     * @param localEntityId 本地玩家 entityId
     * @param beds          当前扫描到的床（用于补齐脚部坐标，可为 null）
     * @param entities      场上实体（可为 null）
     * @param roster        已确认的 TAB 名单（见 {@link RosterBook}）
     */
    public void update(boolean enabled, int radius, long nowMillis, boolean matchActive,
                       Team ownTeam, OwnBed ownBed, int localEntityId,
                       List<BedMarker> beds, List<ThreatEntity> entities,
                       List<PlayerIdentity> roster) {
        if (!enabled || !matchActive || ownTeam == null || !ownTeam.known()
                || ownBed == null || !ownBed.known) {
            for (ThreatContact contact : contacts) contact.inside = false;
            return;
        }

        final double enterDistance = clamp(radius, 3, 32);
        final double leaveDistance = enterDistance + LEAVE_MARGIN;

        int ownBedFootX = ownBed.x;
        int ownBedFootZ = ownBed.z;
        if (beds != null) {
            for (BedMarker marker : beds) {
                if (marker != null && marker.samePosition(ownBed.x, ownBed.y, ownBed.z)) {
                    ownBedFootX = marker.footX;
                    ownBedFootZ = marker.footZ;
                    break;
                }
            }
        }

        if (entities != null) {
            for (ThreatEntity entity : entities) {
                if (entity == null || !entity.player) continue;
                checkEntity(entity, nowMillis, ownTeam, ownBed,
                        ownBedFootX, ownBedFootZ, enterDistance, leaveDistance,
                        localEntityId, roster);
            }
        }

        for (ThreatContact contact : contacts) {
            if (contact.entityId >= 0
                    && nowMillis - contact.lastSeenMillis > CONTACT_EXPIRY_MILLIS) {
                contact.inside = false;
            }
        }
    }

    /** 当前处于威胁半径内的敌人，按距离升序。 */
    public List<ThreatContact> activeThreats() {
        final List<ThreatContact> result = new ArrayList<>();
        for (ThreatContact contact : contacts) {
            if (contact.entityId >= 0 && contact.inside) result.add(contact);
        }
        Collections.sort(result, new Comparator<ThreatContact>() {
            @Override
            public int compare(ThreatContact first, ThreatContact second) {
                return Double.compare(first.distance, second.distance);
            }
        });
        return result;
    }

    /** 全部接触槽位（含已离开的），只读语义，供宿主展示“最近见过的敌人”。 */
    public List<ThreatContact> contacts() {
        final List<ThreatContact> result = new ArrayList<>(contacts.length);
        Collections.addAll(result, contacts);
        return result;
    }

    private void checkEntity(ThreatEntity entity, long nowMillis, Team ownTeam,
                             OwnBed ownBed, int ownBedFootX, int ownBedFootZ,
                             double enterDistance, double leaveDistance,
                             int localEntityId, List<PlayerIdentity> roster) {
        final ThreatContact identityContact =
                findIdentityContact(entity, ownBed);

        boolean rosterKnown = false;
        boolean rosterTeammate = false;
        Team rosterTeam = Team.UNKNOWN;
        String rosterName = "";
        if (!entity.name.isEmpty() || !entity.uuid.isEmpty()) {
            if (roster != null) {
                for (PlayerIdentity identity : roster) {
                    if (identity == null) continue;
                    final boolean uuidMatch = !entity.uuid.isEmpty() && !identity.uuid.isEmpty()
                            && entity.uuid.equals(identity.uuid);
                    final boolean nameMatch = !entity.name.isEmpty()
                            && entity.name.equalsIgnoreCase(identity.name);
                    if (uuidMatch || nameMatch) {
                        rosterKnown = true;
                        rosterTeam = identity.team();
                        rosterTeammate = rosterTeam == ownTeam;
                        rosterName = identity.name;
                        break;
                    }
                }
            }
        }
        if (!rosterKnown && identityContact != null) {
            rosterKnown = true;
            rosterTeam = identityContact.team;
            rosterTeammate = rosterTeam == ownTeam;
            rosterName = identityContact.playerName;
        }
        // 名单是权威：查不到就跳过；队友跳过；本地玩家跳过。
        if (!rosterKnown) return;
        if (rosterTeammate) return;
        if (entity.entityId == localEntityId) return;

        final double dx = Math.min(
                Math.abs(entity.x - (ownBed.x + 0.5)),
                Math.abs(entity.x - (ownBedFootX + 0.5)));
        final double dz = Math.min(
                Math.abs(entity.z - (ownBed.z + 0.5)),
                Math.abs(entity.z - (ownBedFootZ + 0.5)));
        final double dy = Math.abs(entity.y - ownBed.y);
        final double distance = Math.sqrt(dx * dx + dz * dz + dy * dy);

        ThreatContact contact = identityContact;
        ThreatContact oldest = contacts[0];
        if (contact == null) {
            for (ThreatContact candidate : contacts) {
                if (candidate.entityId == entity.entityId
                        && candidate.bedX == ownBed.x
                        && candidate.bedY == ownBed.y
                        && candidate.bedZ == ownBed.z) {
                    contact = candidate;
                    break;
                }
                if (candidate.entityId < 0 || candidate.lastSeenMillis < oldest.lastSeenMillis) {
                    oldest = candidate;
                }
            }
        }
        if (contact == null) {
            contact = oldest;
            contact.reset();
            contact.entityId = entity.entityId;
            contact.bedX = ownBed.x;
            contact.bedY = ownBed.y;
            contact.bedZ = ownBed.z;
        }

        contact.lastSeenMillis = nowMillis;
        contact.distance = distance;
        contact.team = rosterTeam;
        contact.uuid = entity.uuid;
        if (!rosterName.isEmpty()) {
            contact.playerName = rosterName;
        } else if (!entity.name.isEmpty()) {
            contact.playerName = entity.name;
        }
        contact.invisible = entity.invisible;
        if (distance > leaveDistance) {
            contact.inside = false;
        } else if (distance <= enterDistance) {
            contact.inside = true;
        }
    }

    /**
     * 同一 entityId + 同一张床的旧接触记录。仅在 UUID 相同、任一侧名字缺失、
     * 或名字相同时复用；名字明确不同则拒绝（防护 entityId 复用）。
     */
    private ThreatContact findIdentityContact(ThreatEntity entity, OwnBed ownBed) {
        for (ThreatContact candidate : contacts) {
            if (candidate.entityId != entity.entityId
                    || candidate.bedX != ownBed.x
                    || candidate.bedY != ownBed.y
                    || candidate.bedZ != ownBed.z
                    || candidate.team == Team.UNKNOWN) {
                continue;
            }
            final boolean uuidMatch = !entity.uuid.isEmpty() && !candidate.uuid.isEmpty()
                    && entity.uuid.equals(candidate.uuid);
            final boolean liveNameMissing = entity.name.isEmpty();
            final boolean cachedNameMissing = candidate.playerName.isEmpty();
            final boolean sameName = !liveNameMissing && !cachedNameMissing
                    && entity.name.equalsIgnoreCase(candidate.playerName);
            if (uuidMatch || liveNameMissing || cachedNameMissing || sameName) {
                return candidate;
            }
        }
        return null;
    }

    private static double clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
