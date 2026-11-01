package fpsport.adnin.chat;

/** 玩家统计视图（由宿主的统计客户端填充，本模块只读不取）。 */
public final class PlayerStats {

    public final String name;
    public final int stars;
    public final double fkdr;
    public final double swKdr;
    /** false 表示上游没有该模式的数据（对应 "no bedwars data" 一类跳过原因）。 */
    public final boolean bedwarsAvailable;
    public final boolean skywarsAvailable;

    public PlayerStats(String name, int stars, double fkdr, double swKdr,
                       boolean bedwarsAvailable, boolean skywarsAvailable) {
        this.name = name;
        this.stars = stars;
        this.fkdr = fkdr;
        this.swKdr = swKdr;
        this.bedwarsAvailable = bedwarsAvailable;
        this.skywarsAvailable = skywarsAvailable;
    }
}
