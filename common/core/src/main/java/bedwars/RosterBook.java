package bedwars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 名单簿记：把每帧的 TAB 观察结果累积成“本局单调名单”。
 *
 * 规则与源码一致：
 * - 比赛未确认时不发布任何队伍判定（大厅等级颜色/NPC 不会进入名单）；
 * - 比赛确认后只接纳有队伍色的玩家（未着色的开局 NPC/机器人永不进入）；
 * - 已确认玩家在一次 TAB 刷新中短暂消失时不会被移除；
 * - 同一个玩家（UUID 或名字匹配）永远保留第一次确认的队伍；
 * - 最终按名字排序，便于稳定输出。
 *
 * 纯逻辑类，无任何 Minecraft / UI 依赖。
 */
public final class RosterBook {

    private final List<PlayerIdentity> players = new ArrayList<>();

    /** 只读视图（每次 update 后内容会被替换，不要在外部长期持有引用）。 */
    public List<PlayerIdentity> players() {
        return Collections.unmodifiableList(players);
    }

    public void reset() {
        players.clear();
    }

    /**
     * 每帧更新。
     *
     * @param observed    本帧从 TAB 观察到的玩家
     * @param matchActive 比赛是否已确认
     */
    public List<PlayerIdentity> update(List<PlayerIdentity> observed, boolean matchActive) {
        if (!matchActive) {
            players.clear();
            return players();
        }

        final List<PlayerIdentity> next = new ArrayList<>();
        if (observed != null) {
            for (PlayerIdentity player : observed) {
                if (player == null || !player.team().known()) continue;
                next.add(published(player));
            }
        }

        for (PlayerIdentity old : players) {
            boolean present = false;
            for (int index = 0; index < next.size(); index++) {
                if (sameIdentity(next.get(index), old)) {
                    // 保留本局第一次确认的队伍，避免复活期间掉色误判为敌人。
                    next.set(index, published(old));
                    present = true;
                    break;
                }
            }
            if (!present) next.add(old);
        }

        Collections.sort(next, new Comparator<PlayerIdentity>() {
            @Override
            public int compare(PlayerIdentity first, PlayerIdentity second) {
                return first.name.compareTo(second.name);
            }
        });

        players.clear();
        players.addAll(next);
        return players();
    }

    private static boolean sameIdentity(PlayerIdentity first, PlayerIdentity second) {
        final boolean sameUuid = !first.uuid.isEmpty() && !second.uuid.isEmpty()
                && first.uuid.equals(second.uuid);
        return sameUuid || first.name.equals(second.name);
    }

    /** 名单对外只发布“已确认的队伍”，与源码 PlayerIdentity.teamColor 一致。 */
    private static PlayerIdentity published(PlayerIdentity player) {
        final Team team = player.team();
        return new PlayerIdentity(player.uuid, player.name, team, team);
    }
}
