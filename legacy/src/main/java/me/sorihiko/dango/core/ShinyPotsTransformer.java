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
 * 闪光药水：RenderItem#renderEffect 在绑定附魔光效贴图（bindTexture）之后插入
 * {@code ShinyPotsHooks.applyGlintFilter()}。
 *
 * <p>1.8.9 的 renderEffect 自己并不设置 GL_TEXTURE_MIN/MAG_FILTER（贴图过滤由加载时的
 * TextureUtil 决定），所以不能像其它版本那样替换 9729 常量；改为在光效贴图刚绑定、
 * 尚未绘制任何四边形时设置过滤，只影响这一张光效贴图，不会波及其它纹理会话。
 */
public final class ShinyPotsTransformer implements IClassTransformer {

    private static final String OWNER = "net.minecraft.client.renderer.entity.RenderItem";
    private static final String DESC = "(Lnet/minecraft/client/resources/model/IBakedModel;)V";
    private static final String[] NAMES = {"renderEffect", "func_180451_a"};
    private static final String HOOK = "me/sorihiko/dango/core/ShinyPotsHooks";
    private static final String TEXTURE_MANAGER = "net/minecraft/client/renderer/texture/TextureManager";

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
                if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL || !(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!TEXTURE_MANAGER.equals(call.owner) || !"bindTexture".equals(call.name)) continue;
                InsnList add = new InsnList();
                add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "applyGlintFilter", "()V", false));
                method.instructions.insert(call, add);
                changed = true;
                break;
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
