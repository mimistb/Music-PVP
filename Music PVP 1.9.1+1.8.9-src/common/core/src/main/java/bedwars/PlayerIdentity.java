package bedwars;

/**
 * TAB 名单里的一个玩家身份（已解析出队伍信息）。
 *
 * taggedTeam 来自显式标签（[R]/[RED] 等），colorTeam 来自名字的第一个格式码。
 * 两者都可能是 UNKNOWN（大厅 NPC、开局机器人等未着色条目）。
 *
 * 纯逻辑类，无任何 Minecraft / UI 依赖。
 */
public final class PlayerIdentity {

    /** 玩家 UUID（32 位十六进制，可为空字符串）。 */
    public final String uuid;
    /** 去掉格式码后的玩家名。 */
    public final String name;
    /** 显式队伍标签解析结果。 */
    public final Team taggedTeam;
    /** 名字颜色解析结果。 */
    public final Team colorTeam;

    public PlayerIdentity(String uuid, String name, Team taggedTeam, Team colorTeam) {
        this.uuid = uuid == null ? "" : uuid;
        this.name = name == null ? "" : name;
        this.taggedTeam = taggedTeam == null ? Team.UNKNOWN : taggedTeam;
        this.colorTeam = colorTeam == null ? Team.UNKNOWN : colorTeam;
    }

    /**
     * 从“Minecraft 已格式化名字”（含 § 格式码）构造，并把去格式码后的整串当作玩家名。
     * 适用于没有显式队伍标签的简单格式（如 "§9Player"）。
     */
    public static PlayerIdentity fromFormattedName(String uuid, String formattedName) {
        return fromFormattedName(uuid, Text.stripFormatting(formattedName), formattedName);
    }

    /**
     * 从“真实账号名 + 已格式化名字”构造。Hypixel TAB 的格式化名通常形如
     * "§9[B] Player"，真实账号名需要另行从 GameProfile 取，不能直接用去格式码的整串。
     */
    public static PlayerIdentity fromFormattedName(String uuid, String rawName,
                                                   String formattedName) {
        return new PlayerIdentity(uuid, rawName,
                Text.parseRosterTeamTag(formattedName),
                Text.parseRosterTeam(formattedName));
    }

    /** 与源码 parseRosterTeam 一致：优先显式标签，其次名字颜色。 */
    public Team team() {
        return taggedTeam.known() ? taggedTeam : colorTeam;
    }

    public PlayerIdentity withTeam(Team team) {
        return new PlayerIdentity(uuid, name, taggedTeam, team);
    }

    @Override
    public String toString() {
        return "PlayerIdentity{" + name + ", uuid=" + uuid + ", team=" + team() + '}';
    }
}
