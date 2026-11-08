package fpsport.killicon;

import gale.core.Scheduler;
import gale.event.impl.Render2DEvent;
import gale.kill.gd.GdKillEffect;

/**
 * 击杀图标模块的宿主入口（无 UI）。
 *
 * <p>对外只有「开关 + 参数 + 四个时机」：tick、击杀、渲染、重置。所有参数都是 public 字段，
 * 宿主设置界面直接读写；本类不创建任何 Screen / HUD 组件。
 *
 * <p>渲染时机必须与世界在同一帧批次内，且在宿主自己的 HUD 层里调用 {@link #render(int, int, float)}。
 */
public final class KillIconModule {

    /** 总开关。 */
    public boolean enabled = true;
    /** 是否播放图标自带音效。 */
    public boolean playSound = true;
    /** 音效音量 0.0~1.0。 */
    public float soundVolume = 0.7F;
    /** 预设下标，作用域 [0, presets().length-1]；默认 8 对应原实现的 "00009"。 */
    public int presetIndex = 8;
    /** 锚点 X，屏幕宽百分比。 */
    public float anchorXPercent = 50.0F;
    /** 锚点 Y，屏幕高百分比。 */
    public float anchorYPercent = 66.0F;
    /** 尺寸基准像素，原实现按 96 换算缩放。 */
    public float size = 96.0F;
    /** 调试：在没有任何击杀时也循环播放当前预设。 */
    public boolean demoMode = false;

    private final GdKillEffect effect = new GdKillEffect();
    private int appliedPreset = -1;

    /** 可在宿主初始化时调用一次；重复调用无副作用。 */
    public void applyPreset(int index) {
        int clamped = Math.max(0, Math.min(presets().length - 1, index));
        if (clamped == appliedPreset) {
            return;
        }
        effect.clear();
        effect.select(clamped);
        effect.clear();
        appliedPreset = clamped;
        presetIndex = clamped;
    }

    /** 每个客户端 tick 调一次：推进调度器与模块内部计时。 */
    public void onServerTick() {
        Scheduler.tick();
        if (!enabled) {
            return;
        }
        ensurePreset();
        effect.tick(playSound, soundVolume);
    }

    /**
     * 击杀/助攻发生时调用。
     *
     * @param stage 1 = 击杀，2 = 助攻/连杀（与原实现一致）
     * @param victimName 被击杀者名字，可为 null
     */
    public void onKill(int stage, String victimName) {
        if (!enabled) {
            return;
        }
        ensurePreset();
        effect.trigger(stage, victimName);
    }

    /** HUD 层调用，宽高单位为 GUI 缩放后的像素。 */
    public void render(int width, int height, float partialTicks) {
        if (!enabled && !demoMode) {
            return;
        }
        ensurePreset();
        float x = width * anchorXPercent / 100.0F;
        float y = height * anchorYPercent / 100.0F;
        float scale = size / 96.0F;
        effect.render(new Render2DEvent(width, height, partialTicks), x, y, scale);
    }

    /** 切换预设（宿主 UI 的 onChange 直接转发到 index）。 */
    public void setPreset(int index) {
        applyPreset(index);
    }

    /** 预设显示名，长度与 {@link #presetIndex} 一一对应。 */
    public String[] presets() {
        return GdKillEffect.PRESET_NAMES;
    }

    /** 预设内部 ID，一般用于持久化。 */
    public String[] presetIds() {
        return GdKillEffect.PRESET_IDS;
    }

    /** 清空当前播放状态（切服、切预设、关闭模块时调用）。 */
    public void reset() {
        effect.clear();
    }

    /** 供宿主显示/调试：当前生效的预设 ID。 */
    public String currentPresetId() {
        return presetIds()[Math.max(0, Math.min(presetIds().length - 1, presetIndex))];
    }

    private void ensurePreset() {
        if (appliedPreset != presetIndex) {
            applyPreset(presetIndex);
        }
    }
}
