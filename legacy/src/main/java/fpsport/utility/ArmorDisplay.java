package fpsport.utility;

import fpsport.core.Feature;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 护甲显示（只提供数据，**不提供 HUD**）。
 *
 * <p>原始模块是 {@code InterfaceModule}，每帧在客户端自己的 HUD 上画四件护甲；本移植版把
 * 「算数据」和「画界面」拆开：本类只告诉你当前应该显示哪几件、耐久是多少，渲染交给宿主
 * 既有的液态玻璃信息胶囊。
 */
public class ArmorDisplay extends Feature {

    /** 与 armorInventory 下标 3→0 对应的部位名（头盔 → 靴子）。 */
    private static final String[] SLOT_NAMES = {"头盔", "胸甲", "护腿", "靴子"};
    private static final String[] EMPTY_SEGMENTS = new String[0];
    private static final boolean[] EMPTY_FLAGS = new boolean[0];

    /** 每 tick 由宿主重写一次的只读快照；整体替换，不做原地修改。 */
    private static volatile String[] segments = EMPTY_SEGMENTS;
    private static volatile boolean[] warnFlags = EMPTY_FLAGS;

    /** 另存一份最近一次统计结果，方便 HUD 读取（不含任何绘制）。 */
    private static final List<ItemStack> VISIBLE = new ArrayList<>();

    public ArmorDisplay() {
        super("ArmorDisplay");
    }

    /**
     * 按「头盔 → 胸甲 → 护腿 → 靴子」的顺序返回非空护甲。
     *
     * <p>原版 {@code armorInventory} 的下标 0 是靴子、3 是头盔，这里做了反向映射。
     */
    public static List<ItemStack> visibleStacks(EntityPlayer player) {
        List<ItemStack> result = new ArrayList<>(4);
        if (player == null) {
            return result;
        }
        ItemStack[] armor = player.inventory.armorInventory;
        for (int slot = 3; slot >= 0; slot--) {
            ItemStack stack = armor[slot];
            if (stack != null) {
                result.add(stack);
            }
        }
        return result;
    }

    /** 刷新内部缓存（可选），返回可显示的护甲列表。 */
    public static List<ItemStack> refresh(EntityPlayer player) {
        List<ItemStack> fresh = visibleStacks(player);
        synchronized (VISIBLE) {
            VISIBLE.clear();
            VISIBLE.addAll(fresh);
        }
        return fresh;
    }

    public static List<ItemStack> cached() {
        synchronized (VISIBLE) {
            return new ArrayList<>(VISIBLE);
        }
    }

    /** 耐久百分比（0 ~ 100）；不可损坏的物品返回 100。 */
    public static int durabilityPercent(ItemStack stack) {
        if (stack == null || !stack.isItemStackDamageable() || stack.getMaxDamage() <= 0) {
            return 100;
        }
        int left = stack.getMaxDamage() - stack.getItemDamage();
        return (int) Math.max(0, Math.min(100, (long) left * 100L / stack.getMaxDamage()));
    }

    /**
     * 每 tick 由宿主调用：按设置生成本帧要显示的文字段与低耐久标记。
     *
     * <p>只写静态快照，不碰任何 GUI。空槽在 {@code showEmpty} 时用 {@code –} 占位。
     */
    public static void update(EntityPlayer player, boolean showSlots, boolean showEmpty,
                              boolean percent, boolean onlyDamaged, int lowThreshold, boolean lowWarn) {
        String[] out = new String[4];
        boolean[] warn = new boolean[4];
        int n = 0;
        if (player != null && player.inventory != null) {
            ItemStack[] armor = player.inventory.armorInventory;
            for (int slot = 3; slot >= 0; slot--) {
                int index = 3 - slot;
                ItemStack stack = armor[slot];
                if (stack == null) {
                    // 「只显示受损护甲」时空槽同样没有意义
                    if (!showEmpty || onlyDamaged) {
                        continue;
                    }
                    out[n] = showSlots ? SLOT_NAMES[index] + " \u2013" : "\u2013";
                    warn[n] = false;
                    n++;
                    continue;
                }
                int pct = durabilityPercent(stack);
                if (onlyDamaged && pct >= 100) {
                    continue;
                }
                boolean damageable = stack.isItemStackDamageable() && stack.getMaxDamage() > 0;
                String body;
                if (percent) {
                    body = pct + "%";
                } else if (damageable) {
                    body = (stack.getMaxDamage() - stack.getItemDamage()) + "/" + stack.getMaxDamage();
                } else {
                    body = "100%";
                }
                out[n] = showSlots ? SLOT_NAMES[index] + " " + body : body;
                warn[n] = lowWarn && damageable && pct < lowThreshold;
                n++;
            }
        }
        publish(out, warn, n);
    }

    /** 关闭模块 / 不在世界内时清空，避免信息胶囊显示上一局的旧数据。 */
    public static void clear() {
        segments = EMPTY_SEGMENTS;
        warnFlags = EMPTY_FLAGS;
    }

    public static String[] segments() {
        return segments;
    }

    public static boolean[] warnFlags() {
        return warnFlags;
    }

    private static void publish(String[] out, boolean[] warn, int n) {
        if (n <= 0) {
            segments = EMPTY_SEGMENTS;
            warnFlags = EMPTY_FLAGS;
            return;
        }
        String[] s = new String[n];
        boolean[] w = new boolean[n];
        System.arraycopy(out, 0, s, 0, n);
        System.arraycopy(warn, 0, w, 0, n);
        segments = s;
        warnFlags = w;
    }
}
