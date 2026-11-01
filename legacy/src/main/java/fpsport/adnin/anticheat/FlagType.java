package fpsport.adnin.anticheat;

/** 反作弊检查项；与二进制里恢复的配置键一一对应。 */
public enum FlagType {

    /** {@code ac_autoblock}：自动格挡。 */
    AUTOBLOCK("ac_autoblock"),
    /** {@code ac_noslow}：无减速。 */
    NO_SLOW("ac_noslow"),
    /** {@code ac_scaffold}：搭桥 A。 */
    SCAFFOLD_A("ac_scaffold"),
    /** {@code ac_scaffoldb}：搭桥 B。 */
    SCAFFOLD_B("ac_scaffoldb"),
    /** {@code ac_legitscaff}：合法搭桥（合法玩家也常触发的低置信度项）。 */
    LEGIT_SCAFFOLD("ac_legitscaff");

    public final String configKey;

    FlagType(String configKey) {
        this.configKey = configKey;
    }
}
