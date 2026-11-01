package me.sorihiko.dango.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 清洁视角（CleanView）的 ASM 注入。
 *
 * <p>把 {@code EntityLivingBase#updatePotionEffects} 里的
 * <pre>
 *   this.dataWatcher.getWatchableObjectInt(7)
 * </pre>
 * 替换为
 * <pre>
 *   CleanView.readPotionColor(this.dataWatcher, 7, this)
 * </pre>
 * 栈形完全一致（DataWatcher + int → int），所以不需要插入分支或栈帧。
 * 原版在这个颜色值 &lt;= 0 时直接 return（粒子计时之前已完成），
 * 因此本地玩家返回 0 只会跳过粒子生成，不影响药效时长。
 *
 * <p>方法名同时匹配 MCP / srg / 混淆名；找不到就原样返回，绝不影响启动。
 */
public final class CleanViewTransformer implements IClassTransformer {

    /** LaunchClassLoader 传进来的类名是点分形式（内部名只用在 ASM 描述符里）。 */
    private static final String OWNER = "net.minecraft.entity.EntityLivingBase";
    private static final String DATA_WATCHER = "net/minecraft/entity/DataWatcher";
    private static final String HOOK = "fpsport/render/CleanView";
    private static final String HOOK_DESC =
            "(Lnet/minecraft/entity/DataWatcher;ILjava/lang/Object;)I";
    private static final String READ_DESC = "(I)I";
    private static final String[] METHOD_NAMES = {"updatePotionEffects", "func_70679_bo", "bi"};
    private static final String[] READ_NAMES = {"getWatchableObjectInt", "func_75679_c", "c"};

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        if (!OWNER.equals(transformedName) && !OWNER.equals(name)) return basicClass;
        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            reader.accept(node, 0);

            if (!patch(node)) return basicClass;

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            // 补丁失败就原样返回，绝不让游戏起不来
            return basicClass;
        }
    }

    private static boolean patch(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!"()V".equals(method.desc) || !matches(method.name, METHOD_NAMES)) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!DATA_WATCHER.equals(call.owner)
                        || !READ_DESC.equals(call.desc)
                        || !matches(call.name, READ_NAMES)) {
                    continue;
                }
                // 原栈：[DataWatcher, int]；补一个 this 后交给静态方法，返回值仍是 int
                method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.set(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, HOOK, "readPotionColor", HOOK_DESC, false));
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String name, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }
}
