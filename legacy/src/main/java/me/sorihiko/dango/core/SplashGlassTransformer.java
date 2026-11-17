package me.sorihiko.dango.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
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
 * 液态玻璃界面的 ASM 注入：
 *   GuiMainMenu.drawScreen 头部      → SplashGlassHook.beginMainMenuFrame()V（重置入口图标可见性）
 *   GuiMainMenu.drawScreen 末尾      → SplashGlassHook.drawMainMenuForeground(II)V（右上角入口图标）
 *   GuiScreen.mouseClicked 头部      → SplashGlassHook.onMainMenuClick(III)Z（图标点击）
 *   GuiDownloadTerrain.drawScreen 末尾 → SplashGlassHook.drawLoadingForeground(II)V（预加载动画）
 *
 * 主菜单 / 载入界面只按描述符 (IIF)V 匹配（这两个界面里该签名只有 drawScreen），避开 srg 名差异。
 * 任何一步失败都原样返回，绝不因为补丁让游戏起不来。
 */
public final class SplashGlassTransformer implements IClassTransformer {

    private static final String MAIN_MENU = "net.minecraft.client.gui.GuiMainMenu";
    private static final String LOADING = "net.minecraft.client.gui.GuiDownloadTerrain";
    private static final String SCREEN = "net.minecraft.client.gui.GuiScreen";
    private static final String HOOK = "me/sorihiko/dango/core/SplashGlassHook";
    private static final String HELLO = "me/sorihiko/dango/ui/HelloIntro";
    private static final String DRAW_DESC = "(IIF)V";
    /** drawScreen 的候选名：MCP / srg / 混淆。GuiMainMenu 里还有两个 (IIF)V 的私有方法，必须按名字筛。 */
    private static final String[] DRAW_NAMES = {"drawScreen", "func_73863_a", "a"};

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        // GuiScreen 只补鼠标点击（主菜单右上角功能入口），其余逻辑不受影响
        if (SCREEN.equals(transformedName) || SCREEN.equals(name)) {
            return patchScreenClick(basicClass);
        }
        String hookMethod;
        boolean frameStart;
        if (MAIN_MENU.equals(transformedName) || MAIN_MENU.equals(name)) {
            hookMethod = "drawMainMenuForeground";
            frameStart = true;
        } else if (LOADING.equals(transformedName) || LOADING.equals(name)) {
            hookMethod = "drawLoadingForeground";
            frameStart = false;
        } else {
            return basicClass;
        }

        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            reader.accept(node, 0);

            boolean patched = patchScreen(node, hookMethod, frameStart);
            if (!patched) return basicClass;

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            // 补丁失败就原样返回，绝不让游戏起不来
            return basicClass;
        }
    }

    /**
     * GuiScreen#mouseClicked 头部：命中主菜单右上角功能入口时直接结束本次点击。
     * 钩子内部会判断当前界面是否为 GuiMainMenu，其它界面原样放行；补丁失败返回原字节。
     */
    private static byte[] patchScreenClick(byte[] basicClass) {
        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            reader.accept(node, 0);

            boolean patched = false;
            for (MethodNode method : node.methods) {
                if ("(III)V".equals(method.desc) && isMouseClicked(method.name)) {
                    LabelNode skip = new LabelNode();
                    InsnList call = new InsnList();
                    call.add(new VarInsnNode(Opcodes.ILOAD, 1));   // mouseX
                    call.add(new VarInsnNode(Opcodes.ILOAD, 2));   // mouseY
                    call.add(new VarInsnNode(Opcodes.ILOAD, 3));   // mouseButton
                    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                            "onMainMenuClick", "(III)Z", false));
                    call.add(new JumpInsnNode(Opcodes.IFEQ, skip));
                    call.add(new InsnNode(Opcodes.RETURN));
                    call.add(skip);
                    // skip 是新增的跳转目标，1.8.9（class 版本 52）要求分支目标有对应的栈帧，
                    // COMPUTE_MAXS 不会自动补 StackMapTable，缺帧会在类校验时抛 VerifyError。
                    // 插入点在方法头，局部变量表与操作数栈都没变，用 F_SAME。
                    call.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                    method.instructions.insert(call);
                    patched = true;
                } else if ("(CI)V".equals(method.desc) && isKeyTyped(method.name)) {
                    // GuiScreen#keyTyped 头部 → HelloIntro.onKeyPress()V：开场动画任意键跳过。
                    // 只插一次无分支的静态调用，不动栈帧。
                    method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, HELLO,
                            "onKeyPress", "()V", false));
                    patched = true;
                }
            }
            if (!patched) return basicClass;

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            return basicClass;
        }
    }

    private static boolean isMouseClicked(String name) {
        return "mouseClicked".equals(name) || "func_73864_a".equals(name);
    }

    private static boolean isKeyTyped(String name) {
        return "keyTyped".equals(name) || "func_73869_a".equals(name);
    }

    /** 在 drawScreen 的每个 RETURN 前调用一次钩子（每个 RETURN 插入新的节点，避免共享链表节点）。 */
    private static boolean patchScreen(ClassNode node, String hookMethod, boolean frameStart) {
        boolean patched = false;
        for (MethodNode method : node.methods) {
            if (!DRAW_DESC.equals(method.desc) || !isDrawScreen(method.name)) continue;
            boolean hit = false;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.RETURN) continue;
                InsnList call = new InsnList();
                call.add(new VarInsnNode(Opcodes.ILOAD, 1));
                call.add(new VarInsnNode(Opcodes.ILOAD, 2));
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, hookMethod, "(II)V", false));
                method.instructions.insertBefore(insn, call);
                hit = true;
            }
            if (hit && frameStart) {
                // 方法头：开始新的一帧（重置入口图标可见性）
                method.instructions.insert(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, HOOK, "beginMainMenuFrame", "()V", false));
            }
            if (hit) patched = true;
        }
        return patched;
    }

    private static boolean isDrawScreen(String name) {
        for (String candidate : DRAW_NAMES) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }
}
