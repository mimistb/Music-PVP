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
 * Custom Held Items：在 ItemRenderer#renderItemInFirstPerson 注入两段变换。
 *
 * <ul>
 *   <li>方法头注入 {@code CustomHeldItemsHooks.translate()}，平移作用在相机空间；</li>
 *   <li>{@code GlStateManager.enableRescaleNormal()} 之前注入 {@code CustomHeldItemsHooks.scale()}，
 *       此时视角旋转已经乘进矩阵，非等比缩放沿物品本地轴生效，不会再被旋转剪切。
 *       找不到该调用时退回到 {@code pushMatrix()} 之前，仍处于旋转之后。</li>
 * </ul>
 *
 * 模块未启用时两个钩子都直接返回，不改变原版渲染。
 */
public final class CustomHeldItemsTransformer implements IClassTransformer {

    private static final String OWNER = "net.minecraft.client.renderer.ItemRenderer";
    private static final String DESC = "(F)V";
    private static final String[] NAMES = {"renderItemInFirstPerson", "func_78440_a"};
    private static final String HOOK = "me/sorihiko/dango/core/CustomHeldItemsHooks";

    private static final String GL_OWNER = "net/minecraft/client/renderer/GlStateManager";
    private static final String[] RESCALE_NAMES = {"enableRescaleNormal", "func_179101_B"};
    private static final String[] PUSH_NAMES = {"pushMatrix", "func_179094_E"};

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
            AbstractInsnNode first = method.instructions.getFirst();
            if (first == null) continue;

            InsnList translate = new InsnList();
            translate.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "translate", "()V", false));
            method.instructions.insertBefore(first, translate);

            AbstractInsnNode anchor = findCall(method, RESCALE_NAMES);
            if (anchor == null) anchor = findCall(method, PUSH_NAMES);
            if (anchor != null) {
                InsnList scale = new InsnList();
                scale.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "scale", "()V", false));
                method.instructions.insertBefore(anchor, scale);
            }
            changed = true;
        }
        return changed;
    }

    /** 找到第一条对 GlStateManager 指定无参方法的调用。 */
    private static AbstractInsnNode findCall(MethodNode method, String[] names) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (call.getOpcode() != Opcodes.INVOKESTATIC) continue;
            if (!GL_OWNER.equals(call.owner) || !"()V".equals(call.desc)) continue;
            if (matches(call.name, names)) return call;
        }
        return null;
    }

    private static boolean matches(String name, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }
}
