package me.sorihiko.dango.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * 三个「头部直接返回」的补丁，运行时由 {@link FpsFlags} 决定是否生效：
 *   EntityRenderer#hurtCameraEffect        → FpsFlags.noHurtCam
 *   ItemRenderer#renderFireInFirstPerson   → FpsFlags.hideFirstPersonFire
 *   EffectRenderer#addEffect               → FpsFlags.dropParticles
 *
 * 运行环境里类名已去混淆、成员名是 srg，所以方法名同时匹配 MCP 名与 srg 名，描述符精确匹配。
 * 任何一步失败都原样返回，绝不因为补丁让游戏起不来，也不动其它方法和类。
 */
public final class FpsPortTransformer implements IClassTransformer {

    private static final String FLAGS = "me/sorihiko/dango/core/FpsFlags";

    private static final class Target {
        final String owner;
        final String flag;
        final String[] descs;
        final String[] names;

        Target(String owner, String flag, String[] descs, String[] names) {
            this.owner = owner;
            this.flag = flag;
            this.descs = descs;
            this.names = names;
        }
    }

    private static final Target[] TARGETS = {
            new Target("net.minecraft.client.renderer.EntityRenderer", "noHurtCam",
                    new String[]{"(F)V"},
                    new String[]{"hurtCameraEffect", "func_78482_e", "d"}),
            new Target("net.minecraft.client.renderer.ItemRenderer", "hideFirstPersonFire",
                    new String[]{"(F)V"},
                    new String[]{"renderFireInFirstPerson", "func_78442_d", "f"}),
            new Target("net.minecraft.client.particle.EffectRenderer", "dropParticles",
                    new String[]{"(Lnet/minecraft/client/particle/EntityFX;)V", "(Lbeb;)V"},
                    new String[]{"addEffect", "func_78873_a", "a"}),
    };

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        Target target = findTarget(transformedName);
        if (target == null) target = findTarget(name);
        if (target == null) return basicClass;
        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            reader.accept(node, 0);

            if (!patch(node, target)) return basicClass;

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            // 补丁失败就原样返回，绝不能因为这里让游戏起不来
            return basicClass;
        }
    }

    private static Target findTarget(String className) {
        if (className == null) return null;
        for (Target target : TARGETS) {
            if (target.owner.equals(className)) return target;
        }
        return null;
    }

    private static boolean patch(ClassNode node, Target target) {
        boolean any = false;
        for (MethodNode method : node.methods) {
            if (!matchesDesc(method.desc, target.descs)) continue;
            if (!matches(method.name, target.names)) continue;

            LabelNode skip = new LabelNode();
            InsnList ins = new InsnList();
            ins.add(new FieldInsnNode(Opcodes.GETSTATIC, FLAGS, target.flag, "Z"));
            ins.add(new JumpInsnNode(Opcodes.IFEQ, skip));
            ins.add(new InsnNode(Opcodes.RETURN));
            ins.add(skip);
            // 新加的跳转目标需要栈帧；此处局部变量与入口一致、操作数栈为空，用 F_SAME
            ins.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(ins);
            any = true;
        }
        return any;
    }

    private static boolean matches(String name, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }

    private static boolean matchesDesc(String desc, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(desc)) return true;
        }
        return false;
    }
}
