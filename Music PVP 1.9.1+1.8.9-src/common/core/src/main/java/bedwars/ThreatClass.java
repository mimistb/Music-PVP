package bedwars;

/**
 * 胸甲队伍威胁分类。等价移植自 Arcveil BedWarsState 的 classifyArmorThreat。
 *
 * 这是给“没有可靠 TAB 名单”的宿主准备的独立判定工具；{@link ThreatTracker}
 * 本身使用名字名单权威策略（名单查不到就不提示）。两种策略不要混用：
 * 源码说明文档要求“无胸甲也视为威胁”，而实际发布版本以 TAB 名单为准。
 */
public final class ThreatClass {

    public enum Classification {
        TEAMMATE,
        ENEMY,
        UNKNOWN
    }

    private ThreatClass() {
    }

    public static Classification classifyArmorThreat(Team ownTeam,
                                                     boolean chestplatePresent,
                                                     Team armorTeam) {
        if (!chestplatePresent || armorTeam == null || !armorTeam.known()
                || ownTeam == null || !ownTeam.known()) {
            return Classification.UNKNOWN;
        }
        return armorTeam == ownTeam ? Classification.TEAMMATE : Classification.ENEMY;
    }

    /** 按源码说明文档的策略：只有明确队友才免除警告，未知一律视为威胁。 */
    public static boolean shouldWarn(Classification classification) {
        return classification != Classification.TEAMMATE;
    }
}
