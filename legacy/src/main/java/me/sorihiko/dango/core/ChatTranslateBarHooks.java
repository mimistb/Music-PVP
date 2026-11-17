package me.sorihiko.dango.core;

import me.sorihiko.dango.legacy.ChatTranslateBar;

/**
 * 原版聊天栏翻译控制条的 ASM 回调。
 *
 * <p>两个钩子都不改变控制流、不返回布尔值：
 * {@code drawScreen} 头部只负责画两个胶囊，{@code mouseClicked} 头部只负责命中时改配置，
 * 点击位置在输入框之外，原版后续逻辑不会受影响。异常在这里吞掉，绝不让聊天界面崩溃。
 */
public final class ChatTranslateBarHooks {

    private ChatTranslateBarHooks() {
    }

    public static void render(int mouseX, int mouseY) {
        try {
            ChatTranslateBar.INSTANCE.render(mouseX, mouseY);
        } catch (Throwable ignored) {
            // 绘制失败时静默跳过，不影响原版聊天栏
        }
    }

    public static void mouseClicked(int mouseX, int mouseY, int button) {
        try {
            ChatTranslateBar.INSTANCE.mouseClicked(mouseX, mouseY, button);
        } catch (Throwable ignored) {
            // 点击处理失败时静默跳过，不影响原版聊天栏
        }
    }
}
