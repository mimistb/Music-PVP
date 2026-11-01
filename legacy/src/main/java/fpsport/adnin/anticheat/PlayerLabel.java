package fpsport.adnin.anticheat;

/** 玩家判定标签；关键词与二进制里恢复的字符串一一对应。 */
public enum PlayerLabel {
    /** 没有任何关键词命中。 */
    NONE(""),
    /** {@code blatant cheater} / {@code blatant cheating}。 */
    BLATANT("blatant"),
    /** {@code closet cheater} / {@code closet cheating}。 */
    CLOSET("closet"),
    /** {@code confirmed cheater} / {@code cheater} / {@code beamed}。 */
    CONFIRMED("confirmed"),
    /** {@code sniper} 系列。 */
    SNIPER("sniper"),
    /** {@code caution} / {@code risky} / {@code annoying}。 */
    CAUTION("caution");

    public final String displayName;

    PlayerLabel(String displayName) {
        this.displayName = displayName;
    }
}
