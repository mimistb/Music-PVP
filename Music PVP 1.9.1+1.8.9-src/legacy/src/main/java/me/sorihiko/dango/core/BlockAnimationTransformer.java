package me.sorihiko.dango.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 防砍动画的 ASM 注入。
 *
 * <p>只改 {@code ItemRenderer#renderItemInFirstPerson} 里「格挡」那一小段：
 * <pre>
 *   transformFirstPersonItem(f, 0.0F)   →   BlockAnimation.transform(f, f1)
 *   doBlockTransformations()             →   原样保留，后面再追加 BlockAnimation.post(f1, f)
 * </pre>
 * 其余（吃/喝、弓、地图、挥臂）路径完全不动。找不到目标就原样返回，绝不影响启动。
 */
public final class BlockAnimationTransformer implements IClassTransformer {

    private static final String OWNER = "net.minecraft.client.renderer.ItemRenderer";
    private static final String METHOD_DESC = "(F)V";
    private static final String[] RENDER_NAMES = {"renderItemInFirstPerson", "func_78440_a"};
    private static final String[] TRANSFORM_NAMES = {"transformFirstPersonItem", "func_178096_b"};
    private static final String[] BLOCK_NAMES = {"doBlockTransformations", "func_178103_d"};
    private static final String HOOK = "fpsport/render/BlockAnimation";

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
            if (!METHOD_DESC.equals(method.desc) || !matches(method.name, RENDER_NAMES)) continue;

            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!isTransformCall(insn)) continue;

                // 紧跟着的 doBlockTransformations() 才是格挡分支（吃/喝、弓的调用会先撞到别的 invoke）
                AbstractInsnNode next = insn.getNext();
                AbstractInsnNode blockCall = null;
                for (int i = 0; i < 6 && next != null; i++, next = next.getNext()) {
                    if (isBlockCall(next)) {
                        blockCall = next;
                        break;
                    }
                    int opcode = next.getOpcode();
                    if (opcode >= Opcodes.INVOKEVIRTUAL && opcode <= Opcodes.INVOKEDYNAMIC) break;
                }
                if (blockCall == null) continue;

                replace(method.instructions, insn, blockCall);
                return true;
            }
        }
        return false;
    }

    private static void replace(InsnList instructions, AbstractInsnNode transformCall, AbstractInsnNode blockCall) {
        // 原调用的栈是 [this, f, 0.0f]，先清空再用局部变量重新传参
        InsnList pre = new InsnList();
        pre.add(new InsnNode(Opcodes.POP2));          // 0.0f + equipProgress
        pre.add(new InsnNode(Opcodes.POP));           // this
        pre.add(new VarInsnNode(Opcodes.FLOAD, 2));   // equipProgress
        pre.add(new VarInsnNode(Opcodes.FLOAD, 4));   // swingProgress
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "transform", "(FF)V", false));
        instructions.insertBefore(transformCall, pre);
        instructions.remove(transformCall);

        InsnList post = new InsnList();
        post.add(new VarInsnNode(Opcodes.FLOAD, 4));  // swingProgress
        post.add(new VarInsnNode(Opcodes.FLOAD, 2));  // equipProgress
        post.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "post", "(FF)V", false));
        instructions.insert(blockCall, post);
    }

    private static boolean isTransformCall(AbstractInsnNode insn) {
        if (insn.getOpcode() < Opcodes.INVOKEVIRTUAL || insn.getOpcode() > Opcodes.INVOKEDYNAMIC) return false;
        MethodInsnNode call = (MethodInsnNode) insn;
        return "(FF)V".equals(call.desc) && matches(call.name, TRANSFORM_NAMES);
    }

    private static boolean isBlockCall(AbstractInsnNode insn) {
        if (insn.getOpcode() < Opcodes.INVOKEVIRTUAL || insn.getOpcode() > Opcodes.INVOKEDYNAMIC) return false;
        MethodInsnNode call = (MethodInsnNode) insn;
        return "()V".equals(call.desc) && matches(call.name, BLOCK_NAMES);
    }

    private static boolean matches(String name, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }
}
