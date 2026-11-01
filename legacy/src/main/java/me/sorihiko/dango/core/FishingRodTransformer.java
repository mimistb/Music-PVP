package me.sorihiko.dango.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * 增强鱼竿（BetterFishingRod）的 ASM 注入。
 *
 * <p>在 {@code RenderItem#renderItemModelForEntity} 的方法头调用
 * {@code BetterFishingRod.applyLineWidth()}：等价于原工程的 MixinRenderItem
 * （那里注入在 {@code ModelResourceLocation} 构造处，效果相同——钓线在之后的 GL_LINES
 * 绘制里读取同一个线宽状态）。
 *
 * <p>为什么不用 Mixin：宿主 1.8.9 侧只有 FML 的 coremod（ASM）通道，没有 Mixin 运行时，
 * 为了一个注入引入第三方依赖不划算。方法头栈为空、局部变量不变，用 F_SAME 帧即可。
 *
 * <p>任何一步失败都原样返回，绝不影响启动。
 */
public final class FishingRodTransformer implements IClassTransformer {

    private static final String OWNER = "net.minecraft.client.renderer.entity.RenderItem";
    private static final String DESC =
            "(Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/EntityLivingBase;"
                    + "Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;)V";
    private static final String[] NAMES = {"renderItemModelForEntity", "func_180454_a", "a"};
    private static final String HOOK = "fpsport/optimizes/BetterFishingRod";
    private static final String HOOK_METHOD = "applyLineWidth";
    private static final String HOOK_DESC = "()V";

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
        for (MethodNode method : node.methods) {
            if (!DESC.equals(method.desc) || !matches(method.name, NAMES)) continue;

            InsnList ins = new InsnList();
            ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, HOOK_METHOD, HOOK_DESC, false));
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
