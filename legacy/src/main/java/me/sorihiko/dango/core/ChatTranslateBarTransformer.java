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
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 原版聊天栏翻译控制条：给 GuiChat 注入两个方法头回调。
 *
 * <ul>
 *   <li>{@code drawScreen(IIF)V} 头部：{@code ChatTranslateBarHooks.render(mouseX, mouseY)}；</li>
 *   <li>{@code mouseClicked(III)V} 头部：{@code ChatTranslateBarHooks.mouseClicked(x, y, button)}。</li>
 * </ul>
 *
 * 两个钩子都返回 void，不改控制流、不新增局部变量，因此不需要额外的 StackMapFrame，
 * 用 COMPUTE_MAXS 即可。找不到方法或补丁失败时原样返回，保证游戏能启动。
 */
public final class ChatTranslateBarTransformer implements IClassTransformer {

    private static final String OWNER = "net.minecraft.client.gui.GuiChat";
    private static final String HOOK = "me/sorihiko/dango/core/ChatTranslateBarHooks";

    private static final String DRAW_DESC = "(IIF)V";
    private static final String[] DRAW_NAMES = {"drawScreen", "func_73863_a"};
    private static final String CLICK_DESC = "(III)V";
    private static final String[] CLICK_NAMES = {"mouseClicked", "func_73864_a"};

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
            AbstractInsnNode first = method.instructions.getFirst();
            if (first == null) continue;

            if (DRAW_DESC.equals(method.desc) && matches(method.name, DRAW_NAMES)) {
                InsnList ins = new InsnList();
                ins.add(new VarInsnNode(Opcodes.ILOAD, 1));
                ins.add(new VarInsnNode(Opcodes.ILOAD, 2));
                ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "render", "(II)V", false));
                method.instructions.insertBefore(first, ins);
                changed = true;
            } else if (CLICK_DESC.equals(method.desc) && matches(method.name, CLICK_NAMES)) {
                InsnList ins = new InsnList();
                ins.add(new VarInsnNode(Opcodes.ILOAD, 1));
                ins.add(new VarInsnNode(Opcodes.ILOAD, 2));
                ins.add(new VarInsnNode(Opcodes.ILOAD, 3));
                ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "mouseClicked", "(III)V", false));
                method.instructions.insertBefore(first, ins);
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
