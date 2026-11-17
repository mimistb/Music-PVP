package me.sorihiko.dango.core;

import net.minecraft.scoreboard.ScoreObjective;

/**
 * 计分板 ASM 回调：GuiIngame#renderScoreboard 头部调用，
 * 返回 true 时直接 return，跳过原版计分板；数据由 legacy 的 ScoreboardRuntime 读取并发布给 ScoreboardModule。
 */
public final class ScoreboardHooks {

    public static volatile boolean enabled;

    private ScoreboardHooks() {
    }

    public static boolean capture(ScoreObjective objective) {
        return enabled;
    }
}
