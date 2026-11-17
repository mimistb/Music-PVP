package me.sorihiko.dango.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * 慢挥：在 EntityLivingBase#getArmSwingAnimationEnd 的每个 IRETURN 前注入
 * {@code SlowSwingHooks.modify(original)}，只改返回值，不动控制流。
 */
public final class SlowSwingTransformer implements IClassTransformer {

    private static final String OWNER = "net.minecraft.entity.EntityLivingBase";
    private static final String DESC = "()I";
    private static final String[] NAMES = {"getArmSwingAnimationEnd", "func_82166_i"};
    private static final String HOOK = "me/sorihiko/dango/core/SlowSwingHooks";

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
            return basicClass;
        }
    }

    private static boolean patch(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (!DESC.equals(method.desc) || !matches(method.name, NAMES)) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.IRETURN) continue;
                InsnList add = new InsnList();
                add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "modify", "(I)I", false));
                method.instructions.insertBefore(insn, add);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean matches(String name, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }
}
