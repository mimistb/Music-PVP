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

    /** 一条「按键 → 消息」记录，不可变。 */
    public static final class Entry {
        public final int keyCode;
        public final String message;

        public Entry(int keyCode, String message) {
            this.keyCode = keyCode;
            this.message = message == null ? "" : message;
        }

        public Entry(Entry other) {
            this(other.keyCode, other.message);
        }
    }

    /** 默认 G → gg（LWJGL 码 0x22 即 G）。 */
    public final ArrayList<Entry> entries = new ArrayList<>();

    public AutoText() {
        super("AutoText");
        entries.add(new Entry(0x22, "gg"));
    }

    /** 追加一条；容量已满或键位重复时返回 false。 */
    public boolean addEntry(Entry entry) {
        if (entry == null || entries.size() >= MAX_CAPACITY || hasKey(entry.keyCode)) {
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

    /** 替换第 index 条；越界或与其它条目键位冲突时返回 false。 */
    public boolean editEntry(int index, Entry entry) {
        if (entry == null || index < 0 || index >= entries.size()) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (i != index && entries.get(i).keyCode == entry.keyCode && entry.keyCode != 0) {
                return false;
            }
        }
        entries.set(index, new Entry(entry));
        return true;
    }

    private boolean hasKey(int keyCode) {
        if (keyCode == 0) {
            return false;
        }
        for (Entry entry : entries) {
            if (entry.keyCode == keyCode) {
                return true;
            }
        }
        return false;
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
            if (entry.keyCode == key && !entry.message.isEmpty()) {
                mc.thePlayer.sendChatMessage(entry.message);
            }
        }
    }
}
