package fpsport.adnin.anticheat;

import java.util.List;
import java.util.function.Consumer;

/**
 * 作弊检测模块对外接口。
 *
 * <p>宿主（1.8.9 mod）只依赖这个接口：把检测钩子产出的 {@link CheatFlag} 丢进来，
 * 从 {@link Consumer} 拿告警。模块内部不引用任何 UI 类，也不直接播放声音。
 */
public interface AnticheatApi {

    /** 提交一条原始信号；未启用/被过滤/低置信度未达门槛时会被丢弃。 */
    void submit(CheatFlag flag);

    /** 注册告警回调（可多个）；返回自身便于链式。 */
    AnticheatApi addAlertListener(Consumer<Alert> listener);

    /** 注册提示音回调（{@code ac_flagsound} 打开且确认时触发）。 */
    AnticheatApi setFlagSoundSink(FlagSoundSink sink);

    /** 设置自己名字，配合 {@code ignoreSelf}。 */
    AnticheatApi setSelfName(String selfName);

    /** 用恢复出的关键词表给一段文本打标签。 */
    PlayerLabel classify(String text);

    /** 当前所有已确认的告警快照（只读）。 */
    List<Alert> alerts();

    /** 某玩家某检查项在窗口内的累计条数。 */
    int countInWindow(String player, FlagType type);

    /** 清空所有窗口与告警。 */
    void reset();

    /** 参数快照（只读副本，外部改不影响模块）。 */
    AnticheatConfig config();

    /** 热更新参数：内部拷贝后校验，非法值抛 IllegalArgumentException 且不改动现有配置。 */
    void updateConfig(AnticheatConfig newConfig);

    /** 提示音回调。 */
    interface FlagSoundSink {
        void play(String soundName, float volume, float pitch);
    }
}
