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
 * 聊天增强（BetterChat）的 ASM 注入，三处：
 *
 * <ul>
 *   <li>{@code NetHandlerPlayClient#handleChat(S02PacketChat)V}：包进入聊天栏之前调用
 *       {@code BetterChat.onChatPacket}，返回 true 表示已折叠，直接取消该包；</li>
 *   <li>{@code GuiScreen#handleComponentClick(IChatComponent)Z}：点击聊天里的 [C] 时
 *       调用 {@code BetterChat.handleClick}，命中复制标记就把原文写进剪贴板并返回 true，
 *       避免把带 \u0000 前缀的标记当成命令发给服务器；</li>
 *   <li>{@code GuiNewChat#drawChat(I)V}：在每条背景矩形与文字绘制之前，把颜色 int 交给
 *       {@code BetterChat.alpha} 缩放 alpha，实现「聊天透明度」设置。</li>
 * </ul>
 *
 * <p>前两处在方法头，栈为空、局部变量不变，用 F_SAME 帧即可；透明度注入只改栈顶 int，
 * 不新增局部变量、不改变控制流。失败原样返回。
 */
public final class BetterChatTransformer implements IClassTransformer {

    private static final String HOOK = "fpsport/utility/BetterChat";

    /** LaunchClassLoader 传进来的类名是点分形式（内部名只用在 ASM 描述符里）。 */
    private static final String CHAT_OWNER = "net.minecraft.client.network.NetHandlerPlayClient";
    private static final String CHAT_DESC = "(Lnet/minecraft/network/play/server/S02PacketChat;)V";
    private static final String[] CHAT_NAMES = {"handleChat", "func_147251_a", "a"};

    private static final String CLICK_OWNER = "net.minecraft.client.gui.GuiScreen";
    private static final String CLICK_DESC = "(Lnet/minecraft/util/IChatComponent;)Z";
    private static final String[] CLICK_NAMES = {"handleComponentClick", "func_175276_a", "a"};

    private static final String DRAWN_OWNER = "net.minecraft.client.gui.GuiNewChat";
    private static final String DRAWN_DESC = "(I)V";
    private static final String[] DRAWN_NAMES = {"drawChat", "func_146230_a", "a"};
    /** GuiNewChat 内部的私有静态 drawRect（背景与滚动条）。 */
    private static final String RECT_DESC = "(IIIII)V";
    private static final String FONT_OWNER = "net/minecraft/client/gui/FontRenderer";
    private static final String FONT_DESC = "(Ljava/lang/String;FFI)I";
    private static final String[] FONT_NAMES = {"drawStringWithShadow", "func_175063_a"};

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        boolean chat = CHAT_OWNER.equals(transformedName) || CHAT_OWNER.equals(name);
        boolean click = CLICK_OWNER.equals(transformedName) || CLICK_OWNER.equals(name);
        boolean drawn = DRAWN_OWNER.equals(transformedName) || DRAWN_OWNER.equals(name);
        if (!chat && !click && !drawn) return basicClass;
        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            reader.accept(node, 0);

            boolean patched = chat ? patchChat(node) : (click ? patchClick(node) : patchDrawChat(node));
            if (!patched) return basicClass;

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            // 补丁失败就原样返回，绝不让游戏起不来
            return basicClass;
        }
    }

    /** 收到聊天包：已折叠则取消（void 直接 RETURN）。 */
    private static boolean patchChat(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!CHAT_DESC.equals(method.desc) || !matches(method.name, CHAT_NAMES)) continue;

            LabelNode skip = new LabelNode();
            InsnList ins = new InsnList();
            ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
            ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "onChatPacket",
                    "(Lnet/minecraft/network/play/server/S02PacketChat;)Z", false));
            ins.add(new JumpInsnNode(Opcodes.IFEQ, skip));
            ins.add(new InsnNode(Opcodes.RETURN));
            ins.add(skip);
            ins.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(ins);
            return true;
        }
        return false;
    }

    /** 点击聊天组件：命中复制标记则写剪贴板并返回 true。 */
    private static boolean patchClick(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!CLICK_DESC.equals(method.desc) || !matches(method.name, CLICK_NAMES)) continue;

            LabelNode skip = new LabelNode();
            InsnList ins = new InsnList();
            ins.add(new VarInsnNode(Opcodes.ALOAD, 1));
            ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "handleClick",
                    "(Lnet/minecraft/util/IChatComponent;)Z", false));
            ins.add(new JumpInsnNode(Opcodes.IFEQ, skip));
            ins.add(new InsnNode(Opcodes.ICONST_1));
            ins.add(new InsnNode(Opcodes.IRETURN));
            ins.add(skip);
            ins.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(ins);
            return true;
        }
        return false;
    }

    /**
     * 聊天透明度：在 {@code GuiNewChat#drawChat} 的每次颜色参数入栈后、绘制调用前，
     * 插入 {@code BetterChat.alpha(int)int}。
     *
     * <p>命中两类调用：本类私有静态 {@code drawRect(IIIII)V}（背景/滚动条）与
     * {@code FontRenderer.drawStringWithShadow(String,FFI)I}（文字）。只动栈顶 int，
     * 不新增局部变量、不改变跳转目标，因此不需要额外帧。
     */
    private static boolean patchDrawChat(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!DRAWN_DESC.equals(method.desc) || !matches(method.name, DRAWN_NAMES)) continue;

            int patched = 0;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;

                boolean rect = call.getOpcode() == Opcodes.INVOKESTATIC
                        && RECT_DESC.equals(call.desc)
                        && node.name.equals(call.owner);
                boolean text = call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && FONT_DESC.equals(call.desc)
                        && FONT_OWNER.equals(call.owner)
                        && matches(call.name, FONT_NAMES);
                if (!rect && !text) continue;

                method.instructions.insertBefore(call,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "alpha", "(I)I", false));
                patched++;
            }
            if (patched > 0) {
                return true;
            }
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
