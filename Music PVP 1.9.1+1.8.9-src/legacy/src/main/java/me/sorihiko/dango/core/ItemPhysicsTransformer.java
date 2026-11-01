package me.sorihiko.dango.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 物品物理（ItemPhysics）的 ASM 注入。
 *
 * <p>在 {@code RenderEntityItem#doRender(EntityItem;DDDFF)V} 的 HEAD 调用
 * {@code ItemPhysicsRenderer.render(...)}：返回 true 表示已经画完，直接 RETURN；
 * 返回 false 则原版逻辑照常执行。注入点在方法头，栈为空、局部变量不变，用 F_SAME 帧即可。
 *
 * <p>任何一步失败都原样返回，绝不影响启动。
 */
public final class ItemPhysicsTransformer implements IClassTransformer {

    /** LaunchClassLoader 传进来的类名是点分形式（内部名只用在 ASM 描述符里）。 */
    private static final String OWNER = "net.minecraft.client.renderer.entity.RenderEntityItem";
    private static final String DESC = "(Lnet/minecraft/entity/item/EntityItem;DDDFF)V";
    private static final String[] NAMES = {"doRender", "func_76986_a", "a"};
    private static final String HOOK = "fpsport/render/ItemPhysicsRenderer";
    private static final String HOOK_DESC =
            "(Lnet/minecraft/client/renderer/entity/RenderEntityItem;"
                    + "Lnet/minecraft/entity/item/EntityItem;DDDFF)Z";

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
            if (!DESC.equals(method.desc) || !matches(method.name, NAMES)) continue;

            LabelNode skip = new LabelNode();
            InsnList ins = new InsnList();
            ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
            ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
            ins.add(new VarInsnNode(Opcodes.DLOAD, 2));
            ins.add(new VarInsnNode(Opcodes.DLOAD, 4));
            ins.add(new VarInsnNode(Opcodes.DLOAD, 6));
            ins.add(new VarInsnNode(Opcodes.FLOAD, 8));
            ins.add(new VarInsnNode(Opcodes.FLOAD, 9));
            ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "render", HOOK_DESC, false));
            ins.add(new JumpInsnNode(Opcodes.IFEQ, skip));
            ins.add(new InsnNode(Opcodes.RETURN));
            ins.add(skip);
            // 方法头：局部变量与参数保持一致、栈为空
            ins.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(ins);
            return true;
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
