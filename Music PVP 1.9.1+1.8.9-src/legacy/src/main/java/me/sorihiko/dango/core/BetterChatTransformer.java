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
 * 聊天增强（BetterChat）的 ASM 注入，两个方法头：
 *
 * <ul>
 *   <li>{@code NetHandlerPlayClient#handleChat(S02PacketChat)V}：包进入聊天栏之前调用
 *       {@code BetterChat.onChatPacket}，返回 true 表示已折叠，直接取消该包；</li>
 *   <li>{@code GuiScreen#handleComponentClick(IChatComponent)Z}：点击聊天里的 [C] 时
 *       调用 {@code BetterChat.handleClick}，命中复制标记就把原文写进剪贴板并返回 true，
 *       避免把带 \u0000 前缀的标记当成命令发给服务器。</li>
 * </ul>
 *
 * <p>两处都在方法头，栈为空、局部变量不变，用 F_SAME 帧即可。失败原样返回。
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

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        boolean chat = CHAT_OWNER.equals(transformedName) || CHAT_OWNER.equals(name);
        boolean click = CLICK_OWNER.equals(transformedName) || CLICK_OWNER.equals(name);
        if (!chat && !click) return basicClass;
        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            reader.accept(node, 0);

            boolean patched = chat ? patchChat(node) : patchClick(node);
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

    private static boolean matches(String name, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }
}
