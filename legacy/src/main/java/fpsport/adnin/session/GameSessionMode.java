package fpsport.adnin.session;

/** 会话内的游戏模式（与聊天模块的 GameMode 解耦，两个模块互不引用）。 */
public enum GameSessionMode {
    UNKNOWN,
    BEDWARS,
    SKYWARS,
    DUELS,
    BEDWARS_DUELS
}
