package me.sorihiko.dango.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 液态玻璃启动画面的 ASM 注入：
 *   GuiMainMenu.drawScreen 头部      → SplashGlassHook.beginMainMenuFrame()V（清空本帧按钮记录）
 *   GuiMainMenu.drawScreen 末尾      → SplashGlassHook.drawMainMenuForeground(II)V（整屏自绘）
 *   GuiDownloadTerrain.drawScreen 末尾 → SplashGlassHook.drawLoadingForeground(II)V（预加载动画）
 *   GuiButton.drawButton 末尾        → SplashGlassHook.recordButton（只记录位置文字，不画东西）
 *
 * 主菜单 / 载入界面只按描述符 (IIF)V 匹配（这两个界面里该签名只有 drawScreen），避开 srg 名差异；
 * 按钮方法按 (MC;II)V 形状匹配，字段名同时兼容 MCP 名与 srg 名（运行环境是 srg）。
 * 任何一步失败都原样返回，绝不因为补丁让游戏起不来。
 */
public final class SplashGlassTransformer implements IClassTransformer {

    private static final String MAIN_MENU = "net.minecraft.client.gui.GuiMainMenu";
    private static final String LOADING = "net.minecraft.client.gui.GuiDownloadTerrain";
    private static final String BUTTON = "net.minecraft.client.gui.GuiButton";
    private static final String HOOK = "me/sorihiko/dango/core/SplashGlassHook";
    private static final String DRAW_DESC = "(IIF)V";
    /** drawScreen 的候选名：MCP / srg / 混淆。GuiMainMenu 里还有两个 (IIF)V 的私有方法，必须按名字筛。 */
    private static final String[] DRAW_NAMES = {"drawScreen", "func_73863_a", "a"};

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        String hookMethod;
        boolean frameStart;
        if (MAIN_MENU.equals(transformedName) || MAIN_MENU.equals(name)) {
            hookMethod = "drawMainMenuForeground";
            frameStart = true;
        } else if (LOADING.equals(transformedName) || LOADING.equals(name)) {
            hookMethod = "drawLoadingForeground";
            frameStart = false;
        } else if (BUTTON.equals(transformedName) || BUTTON.equals(name)) {
            hookMethod = null;
            frameStart = false;
        } else {
            return basicClass;
        }

        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            reader.accept(node, 0);

            boolean patched = hookMethod == null ? patchButton(node) : patchScreen(node, hookMethod, frameStart);
            if (!patched) return basicClass;

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            // 补丁失败就原样返回，绝不让游戏起不来
            return basicClass;
        }
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
                // 方法头：开始新的一帧（清空按钮记录 + 入场动画计时）
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

    /** 在每个 RETURN 前记录按钮几何与文字（不画东西，视觉全部由 drawMainMenuForeground 自绘）。 */
    private static boolean patchButton(ClassNode node) {
        // 三个候选：MCP 名 / srg 名 / 运行时混淆名（coremod 有可能在 deobf 之前执行）
        String xPos = field(node, "xPosition", "field_146128_h", "h");
        String yPos = field(node, "yPosition", "field_146129_i", "i");
        String width = field(node, "width", "field_146120_f", "f");
        String height = field(node, "height", "field_146121_g", "g");
        String label = field(node, "displayString", "field_146126_j", "j");
        String enabled = field(node, "enabled", "field_146124_l", "l");
        String visible = field(node, "visible", "field_146125_m", "m");
        if (xPos == null || yPos == null || width == null || height == null
                || label == null || enabled == null || visible == null) {
            return false;
        }

        boolean patched = false;
        for (MethodNode method : node.methods) {
            if (!method.desc.endsWith("II)V") || !method.desc.startsWith("(L")) continue;
            // 防御：只改第一个命中（drawButton），其它同形状方法原样保留
            if (patched) break;

            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.RETURN) continue;
                InsnList call = new InsnList();
                loadField(call, node.name, xPos, "I");
                loadField(call, node.name, yPos, "I");
                loadField(call, node.name, width, "I");
                loadField(call, node.name, height, "I");
                loadField(call, node.name, label, "Ljava/lang/String;");
                loadField(call, node.name, enabled, "Z");
                loadField(call, node.name, visible, "Z");
                call.add(new VarInsnNode(Opcodes.ILOAD, 2));   // mouseX
                call.add(new VarInsnNode(Opcodes.ILOAD, 3));   // mouseY
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "recordButton",
                        "(IIIILjava/lang/String;ZZII)V", false));
                method.instructions.insertBefore(insn, call);
                patched = true;
            }
        }
        return patched;
    }

    private static void loadField(InsnList ins, String owner, String fieldName, String desc) {
        ins.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ins.add(new FieldInsnNode(Opcodes.GETFIELD, owner, fieldName, desc));
    }

    /** 字段名兼容 MCP 名 / srg 名 / 混淆名。 */
    private static String field(ClassNode node, String mcpName, String srgName, String obfName) {
        for (FieldNode f : node.fields) {
            if (mcpName.equals(f.name) || srgName.equals(f.name) || obfName.equals(f.name)) {
                return f.name;
            }
        }
        return null;
    }
}
