package me.sorihiko.dango.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.ClassNode;

/**
 * 聊天翻译的发送拦截。
 *
 * <p>1.8.9 没有 ClientChatEvent，所以在 {@code GuiScreen#sendChatMessage} 的方法头
 * 插入 {@code ChatTranslateHook.onChatSend(String)}：返回 true 表示本次已由翻译模块
 * 异步接管（原版直接 RETURN），返回 false 则原版照常把文字发给服务器。
 *
 * <p>GuiChat 的回车、聊天组件里的 RUN_COMMAND 最终都会走到这里；命令以 / 开头时
 * 模块的 skipCommands 会让钩子返回 false，不影响 /translate 命令本身。
 * 翻译结果由模块用 {@code EntityPlayerSP#sendChatMessage} 直发，不会再经过本钩子。
 */
public final class ChatTranslateTransformer implements IClassTransformer {

    private static final String OWNER = "net.minecraft.client.gui.GuiScreen";
    private static final String HOOK = "fpsport/chattranslate/ChatTranslateHook";
    private static final String HOOK_DESC = "(Ljava/lang/String;)Z";

    /** GuiScreen 的两个重载，均从局部变量 1 取文本。 */
    private static final String[] DESCS = {
            "(Ljava/lang/String;)V",
            "(Ljava/lang/String;Z)V"
    };
    private static final String[] NAMES = {"sendChatMessage", "func_175275_f", "func_175281_b"};

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
        boolean patched = false;
        for (MethodNode method : node.methods) {
            if (!matches(method.name, NAMES)) continue;
            if (!matches(method.desc, DESCS)) continue;

            LabelNode skip = new LabelNode();
            InsnList ins = new InsnList();
            ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
            ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "onChatSend", HOOK_DESC, false));
            ins.add(new JumpInsnNode(Opcodes.IFEQ, skip));
            ins.add(new InsnNode(Opcodes.RETURN));
            ins.add(skip);
            ins.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(ins);
            patched = true;
        }
        return patched;
    }

    private static boolean matches(String value, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(value)) return true;
        }
        return false;
    }
}
