package fpsport.adnin.anticheat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 作弊检测模块的全部可调参数。
 *
 * <p>所有阈值/开关都在这里，模块内部不出现任何魔法数：构造时传入或之后用 setter 改。
 * 字段名对应 AdOverlay 配置里恢复出的键；默认值来自二进制里的界面文案与行为推断，
 * 未被恢复的用 {@code INFERRED} 注释标出（见 REVERSE_REPORT.md 的置信度表）。
 */
public final class AnticheatConfig {

    // ---- 开关（配置键原样保留） ----

    /** {@code anticheat}：模块总开关。 */
    public boolean anticheat = true;
    /** {@code ac_flagsound}：确认作弊时播放提示音。 */
    public boolean flagSound = true;
    /** {@code ac_autoblock}：启用自动格挡检查。 */
    public boolean autoblock = true;
    /** {@code ac_legitscaff}：启用合法搭桥检查。 */
    public boolean legitScaffold = true;
    /** {@code ac_scaffold}：启用搭桥 A 检查。 */
    public boolean scaffoldA = true;
    /** {@code ac_scaffoldb}：启用搭桥 B 检查。 */
    public boolean scaffoldB = true;
    /** {@code ac_noslow}：启用无减速检查。 */
    public boolean noSlow = true;

    // ---- 阈值（INFERRED：二进制未直接暴露数值，仅恢复出判定顺序） ----

    /** 同一玩家同一检查项在窗口内累计多少条信号就算“确认”。INFERRED，默认 3。 */
    public int confirmFlagCount = 3;
    /** 信号统计窗口（毫秒）。INFERRED，默认 10000。 */
    public long flagWindowMs = 10_000L;
    /** 单条信号的置信度门槛，达到即立刻确认。INFERRED，默认 0.6。 */
    public double minConfidence = 0.6D;
    /** 低置信度项的额外倍率（{@link FlagType#LEGIT_SCAFFOLD} 用它放大门槛）。INFERRED，默认 2。 */
    public int legitScaffoldMultiplier = 2;
    /** 是否忽略自己的信号。INFERRED，默认 true。 */
    public boolean ignoreSelf = true;

    /** 提示音事件名（宿主自行播放）。 */
    public String flagSoundName = "note.pling";
    /** 提示音音量/音高，交给宿主的播放回调。 */
    public float flagSoundVolume = 0.6F;
    public float flagSoundPitch = 1.2F;

    // ---- 分类关键词（从二进制里原样恢复） ----

    /** {@code blatant cheater} / {@code blatant cheating}。 */
    public final Set<String> blatantKeywords = new LinkedHashSet<String>(Arrays.asList(
            "blatant cheater", "blatant cheating"));
    /** {@code closet cheater} / {@code closet cheating}。 */
    public final Set<String> closetKeywords = new LinkedHashSet<String>(Arrays.asList(
            "closet cheater", "closet cheating"));
    /** {@code sniper} / {@code possible sniper} / {@code potential sniper} / {@code legit sniper}。 */
    public final Set<String> sniperKeywords = new LinkedHashSet<String>(Arrays.asList(
            "sniper", "possible sniper", "potential sniper", "legit sniper", "sniping", "legit sniping"));
    /** {@code confirmed cheater}。 */
    public final Set<String> confirmedKeywords = new LinkedHashSet<String>(Arrays.asList(
            "confirmed cheater", "cheater", "cheat", "beamed"));
    /** {@code caution} / {@code risky} / {@code annoying}。 */
    public final Set<String> cautionKeywords = new LinkedHashSet<String>(Arrays.asList(
            "caution", "risky", "annoying"));

    public AnticheatConfig() {
    }

    /** 检查项 → 是否启用。 */
    public boolean isCheckEnabled(FlagType type) {
        switch (type) {
            case AUTOBLOCK:
                return autoblock;
            case NO_SLOW:
                return noSlow;
            case SCAFFOLD_A:
                return scaffoldA;
            case SCAFFOLD_B:
                return scaffoldB;
            case LEGIT_SCAFFOLD:
                return legitScaffold;
            default:
                return false;
        }
    }

    /** 该检查项在窗口内需要的信号条数（低置信度项放大）。 */
    public int requiredCountFor(FlagType type) {
        int base = Math.max(1, confirmFlagCount);
        return type == FlagType.LEGIT_SCAFFOLD ? base * Math.max(1, legitScaffoldMultiplier) : base;
    }

    /** 配置校验：宿主 UI 提交非法值时抛 IllegalArgumentException，问题暴露在设置页而不是运行期。 */
    public void validate() {
        if (confirmFlagCount < 1) {
            throw new IllegalArgumentException("confirmFlagCount must be >= 1");
        }
        if (flagWindowMs <= 0L) {
            throw new IllegalArgumentException("flagWindowMs must be > 0");
        }
        if (Double.isNaN(minConfidence) || minConfidence < 0.0D || minConfidence > 1.0D) {
            throw new IllegalArgumentException("minConfidence must be in [0,1]");
        }
        if (legitScaffoldMultiplier < 1) {
            throw new IllegalArgumentException("legitScaffoldMultiplier must be >= 1");
        }
        if (Float.isNaN(flagSoundVolume) || flagSoundVolume < 0.0F || flagSoundVolume > 1.0F) {
            throw new IllegalArgumentException("flagSoundVolume must be in [0,1]");
        }
        if (Float.isNaN(flagSoundPitch) || flagSoundPitch < 0.0F || flagSoundPitch > 2.0F) {
            throw new IllegalArgumentException("flagSoundPitch must be in [0,2]");
        }
        if (flagSoundName == null || flagSoundName.trim().isEmpty()) {
            throw new IllegalArgumentException("flagSoundName must not be empty");
        }
    }

    /** 深拷贝：模块之间不共享可变配置对象。 */
    public AnticheatConfig copy() {
        AnticheatConfig c = new AnticheatConfig();
        c.anticheat = anticheat;
        c.flagSound = flagSound;
        c.autoblock = autoblock;
        c.legitScaffold = legitScaffold;
        c.scaffoldA = scaffoldA;
        c.scaffoldB = scaffoldB;
        c.noSlow = noSlow;
        c.confirmFlagCount = confirmFlagCount;
        c.flagWindowMs = flagWindowMs;
        c.minConfidence = minConfidence;
        c.legitScaffoldMultiplier = legitScaffoldMultiplier;
        c.ignoreSelf = ignoreSelf;
        c.flagSoundName = flagSoundName;
        c.flagSoundVolume = flagSoundVolume;
        c.flagSoundPitch = flagSoundPitch;
        copyKeywords(c.blatantKeywords, blatantKeywords);
        copyKeywords(c.closetKeywords, closetKeywords);
        copyKeywords(c.sniperKeywords, sniperKeywords);
        copyKeywords(c.confirmedKeywords, confirmedKeywords);
        copyKeywords(c.cautionKeywords, cautionKeywords);
        return c;
    }

    /** 关键词集合可能被宿主整体置空；拷贝时按空集合兜底，避免运行期 NPE。 */
    private static void copyKeywords(Set<String> target, Set<String> source) {
        target.clear();
        if (source != null) {
            target.addAll(source);
        }
    }

    /** 导出所有开关与阈值（宿主用于保存配置，键名与 AdOverlay 一致）。 */
    public List<String> exportKeys() {
        List<String> keys = new ArrayList<String>();
        keys.add("anticheat");
        keys.add("ac_flagsound");
        keys.add("ac_autoblock");
        keys.add("ac_legitscaff");
        keys.add("ac_scaffold");
        keys.add("ac_scaffoldb");
        keys.add("ac_noslow");
        return keys;
    }
}
