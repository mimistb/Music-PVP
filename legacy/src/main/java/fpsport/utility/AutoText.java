package fpsport.utility;

import fpsport.core.Feature;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动文本：把某个键绑定到一条聊天消息。
 *
 * <p>原始版本的 {@code AutoTextSetting} 自带一个编辑界面，这里换成纯数据的
 * {@link Entry} 列表 + 增删改方法，宿主用自己的设置界面渲染即可。
 */
public class AutoText extends Feature {

    public static final int MAX_CAPACITY = 20;

    /** 一条「组合键 → 消息」记录，不可变。mods 是修饰键码（可为空数组）。 */
    public static final class Entry {
        public final int keyCode;
        public final int[] mods;
        public final String message;

        public Entry(int keyCode, int[] mods, String message) {
            this.keyCode = keyCode;
            this.mods = mods == null ? new int[0] : mods.clone();
            this.message = message == null ? "" : message;
        }

        public Entry(Entry other) {
            this(other.keyCode, other.mods, other.message);
        }
    }

    /** 默认 G → gg（LWJGL 码 0x22 即 G）。 */
    public final ArrayList<Entry> entries = new ArrayList<>();

    public AutoText() {
        super("AutoText");
        entries.add(new Entry(0x22, new int[0], "gg"));
    }

    /** 追加一条；容量已满或组合键重复时返回 false。 */
    public boolean addEntry(Entry entry) {
        if (entry == null || entries.size() >= MAX_CAPACITY || hasKey(entry)) {
            return false;
        }
        entries.add(new Entry(entry));
        return true;
    }

    public void removeEntry(int index) {
        if (index >= 0 && index < entries.size()) {
            entries.remove(index);
        }
    }

    /** 替换第 index 条；越界或与其它条目组合键冲突时返回 false。 */
    public boolean editEntry(int index, Entry entry) {
        if (entry == null || index < 0 || index >= entries.size()) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (i != index && sameCombo(entries.get(i), entry) && entry.keyCode != 0) {
                return false;
            }
        }
        entries.set(index, new Entry(entry));
        return true;
    }

    private boolean hasKey(Entry target) {
        if (target == null || target.keyCode == 0) {
            return false;
        }
        for (Entry entry : entries) {
            if (sameCombo(entry, target)) {
                return true;
            }
        }
        return false;
    }

    /** 主键相同且修饰键集合相同视为冲突。 */
    private static boolean sameCombo(Entry a, Entry b) {
        if (a.keyCode != b.keyCode) {
            return false;
        }
        if (a.mods.length != b.mods.length) {
            return false;
        }
        java.util.HashSet<Integer> set = new java.util.HashSet<>();
        for (int mod : a.mods) {
            set.add(mod);
        }
        for (int mod : b.mods) {
            if (!set.contains(mod)) {
                return false;
            }
        }
        return true;
    }

    /** 宿主在按键回调里调用（对应源码的 EventKey）。 */
    public void onKey(int key) {
        if (key == 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.thePlayer == null) {
            return;
        }
        List<Entry> snapshot;
        synchronized (entries) {
            snapshot = new ArrayList<>(entries);
        }
        for (Entry entry : snapshot) {
            if (entry.keyCode != key || entry.message.isEmpty()) {
                continue;
            }
            boolean modsDown = true;
            for (int mod : entry.mods) {
                if (mod <= 0 || !org.lwjgl.input.Keyboard.isKeyDown(mod)) {
                    modsDown = false;
                    break;
                }
            }
            if (modsDown) {
                mc.thePlayer.sendChatMessage(entry.message);
            }
        }
    }
}
