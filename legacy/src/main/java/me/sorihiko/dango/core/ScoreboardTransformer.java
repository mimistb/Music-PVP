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
 * 计分板：GuiIngame#renderScoreboard 头部调用 {@code ScoreboardHooks.capture(objective)}，
 * 返回 true 时直接 return（本项目的 ScoreboardModule 自己画）。
 */
public final class ScoreboardTransformer implements IClassTransformer {

    private static final String OWNER = "net.minecraft.client.gui.GuiIngame";
    private static final String DESC =
            "(Lnet/minecraft/scoreboard/ScoreObjective;Lnet/minecraft/client/gui/ScaledResolution;)V";
    private static final String[] NAMES = {"renderScoreboard", "func_180475_a"};
    private static final String HOOK = "me/sorihiko/dango/core/ScoreboardHooks";

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
            LabelNode cont = new LabelNode();
            InsnList ins = new InsnList();
            ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
            ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "capture",
                    "(Lnet/minecraft/scoreboard/ScoreObjective;)Z", false));
            ins.add(new JumpInsnNode(Opcodes.IFEQ, cont));
            ins.add(new InsnNode(Opcodes.RETURN));
            ins.add(cont);
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
