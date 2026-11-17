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
 * 屏幕之上的 HUD 补绘注入：
 *   EntityRenderer.updateCameraAndRender 的每个 RETURN 之前 → HudAboveScreenHook.render()V
 *
 * 原版顺序是「HUD → 当前 GuiScreen.drawScreen」。聊天框打开时 GuiChat 会再铺一层近乎
 * 不透明的聊天黑底、ESC 菜单会铺全屏黑渐变，二者都会把 HUD 的液态玻璃压黑 / 压灰。
 * 在 updateCameraAndRender 返回前（此时 currentScreen 已经画完）补绘一次 HUD，
 * 玻璃就不会被界面底纹盖住。
 *
 * 任何一步失败都原样返回，绝不因为补丁让游戏起不来；注入成功才把 patched 置 true，
 * 下游（ForgeBridge）据此决定是否把 Post(ALL) 的 HUD 推迟到补绘阶段，避免同帧画两遍。
 */
public final class HudAboveScreenTransformer implements IClassTransformer {

    private static final String TARGET = "net.minecraft.client.renderer.EntityRenderer";
    private static final String HOOK = "me/sorihiko/dango/core/HudAboveScreenHook";
    private static final String DESC = "(FJ)V";
    /** updateCameraAndRender 的候选名：MCP / srg / 混淆 */
    private static final String[] NAMES = {"updateCameraAndRender", "func_78480_b", "b"};

    /** 补丁是否成功注入。false 时 HUD 仍然走原来的 Post(ALL)，只是不会被盖住的场景得不到修复 */
    public static volatile boolean patched = false;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        if (!TARGET.equals(transformedName) && !TARGET.equals(name)) return basicClass;
        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            reader.accept(node, 0);

            boolean hit = false;
            for (MethodNode method : node.methods) {
                if (!DESC.equals(method.desc) || !isTarget(method.name)) continue;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() != Opcodes.RETURN) continue;
                    InsnList call = new InsnList();
                    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "render", "()V", false));
                    method.instructions.insertBefore(insn, call);
                    hit = true;
                }
            }
            if (!hit) return basicClass;

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            patched = true;
            System.out.println("[Dango Music] HUD-above-screen hook injected into " + transformedName);
            return writer.toByteArray();
        } catch (Throwable t) {
            return basicClass;
        }
    }

    private static boolean isTarget(String name) {
        for (String candidate : NAMES) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }
}
