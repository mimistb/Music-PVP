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
 * 聊天增强（BetterChat）的 ASM 注入：
 *
 * <ul>
 *   <li>{@code NetHandlerPlayClient#handleChat(S02PacketChat)V}：包进入聊天栏之前调用
 *       {@code BetterChat.onChatPacket}，返回 true 表示已折叠，直接取消该包；</li>
 *   <li>{@code GuiScreen#handleComponentClick(IChatComponent)Z}：点击聊天里的 [C] 时
 *       调用 {@code BetterChat.handleClick}，命中复制标记就把原文写进剪贴板并返回 true，
 *       避免把带 \u0000 前缀的标记当成命令发给服务器；</li>
 *   <li>{@code GuiNewChat#drawChat(I)V}：每条背景矩形与文字绘制之前把颜色 int 交给
 *       {@code BetterChat.alpha} 缩放 alpha；每行开始前调用 {@code beginLine} 记录淡入；
 *       文字 x 交给 {@code textX} 右移（头像占位）；文字绘制前调用 {@code drawAvatar}。</li>
 *   <li>{@code GuiNewChat#setChatLine(...)}：折行宽度交给 {@code wrapWidth}，开启头像时先扣掉
 *       头像占位，长消息不会顶出聊天框；</li>
 *   <li>{@code GuiNewChat#scroll(I)V}：交给 {@code onScroll}，滚轮改成按帧分步吸附；</li>
 *   <li>{@code GuiNewChat#resetScroll()V}：清掉还没走完的滚轮步进。</li>
 * </ul>
 *
 * <p>所有注入只动栈顶 / 不新增局部变量、不改变原控制流；新增分支（scroll 的提前返回）
 * 用 F_SAME 帧。任一步失败都原样返回，绝不让游戏起不来。
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

    private static final String NEW_CHAT_OWNER = "net.minecraft.client.gui.GuiNewChat";
    private static final String DRAWN_DESC = "(I)V";
    private static final String[] DRAWN_NAMES = {"drawChat", "func_146230_a", "a"};
    /** GuiNewChat 内部的私有静态 drawRect（背景与滚动条）。 */
    private static final String RECT_DESC = "(IIIII)V";
    private static final String FONT_OWNER = "net/minecraft/client/gui/FontRenderer";
    private static final String FONT_DESC = "(Ljava/lang/String;FFI)I";
    private static final String[] FONT_NAMES = {"drawStringWithShadow", "func_175063_a"};

    private static final String CHAT_LINE_OWNER = "net/minecraft/client/gui/ChatLine";
    private static final String COMPONENT_OWNER = "net/minecraft/util/IChatComponent";
    private static final String[] FORMATTED_NAMES = {"getFormattedText", "func_150254_d"};

    private static final String SET_LINE_DESC = "(Lnet/minecraft/util/IChatComponent;IIZ)V";
    private static final String[] SET_LINE_NAMES = {"setChatLine", "func_146237_a"};

    private static final String SCROLL_DESC = "(I)V";
    private static final String[] SCROLL_NAMES = {"scroll", "func_146229_b"};

    private static final String RESET_SCROLL_DESC = "()V";
    private static final String[] RESET_SCROLL_NAMES = {"resetScroll", "func_146240_d"};

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        boolean chat = CHAT_OWNER.equals(transformedName) || CHAT_OWNER.equals(name);
        boolean click = CLICK_OWNER.equals(transformedName) || CLICK_OWNER.equals(name);
        boolean newChat = NEW_CHAT_OWNER.equals(transformedName) || NEW_CHAT_OWNER.equals(name);
        if (!chat && !click && !newChat) return basicClass;
        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            reader.accept(node, 0);

            boolean patched = chat ? patchChat(node) : (click ? patchClick(node) : patchNewChat(node));
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

    /** GuiNewChat 里四处独立的注入，任意一处成功都要重写字节码。 */
    private static boolean patchNewChat(ClassNode node) {
        boolean draw = patchDrawChat(node);
        boolean setLine = patchSetChatLine(node);
        boolean scroll = patchScroll(node);
        boolean reset = patchResetScroll(node);
        return draw || setLine || scroll || reset;
    }

    /**
     * drawChat：
     *
     * <ol>
     *   <li>方法头 {@code BetterChat.beginFrame(updateCounter)}；</li>
     *   <li>每条行取 updateCounter 之前 {@code beginLine(chatline)}（先 DUP 保住栈上的行）；</li>
     *   <li>文字 x 入栈后 {@code textX(float)} 右移；</li>
     *   <li>文字字符串存入局部变量后 {@code drawAvatar(chatline, y)}；</li>
     *   <li>每次颜色参数入栈后、绘制调用前 {@code alpha(int)}。</li>
     * </ol>
     */
    private static boolean patchDrawChat(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!DRAWN_DESC.equals(method.desc) || !matches(method.name, DRAWN_NAMES)) continue;

            int patched = 0;

            // 1) 透明度：颜色 int 在栈顶
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

            // 2) 逐行淡入：age = updateCounter - chatline.getUpdatedCounter()
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !CHAT_LINE_OWNER.equals(call.owner)
                        || !"()I".equals(call.desc)) {
                    continue;
                }
                InsnList ins = new InsnList();
                ins.add(new InsnNode(Opcodes.DUP));
                ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "beginLine",
                        "(Lnet/minecraft/client/gui/ChatLine;)V", false));
                method.instructions.insertBefore(call, ins);
                patched++;
                break;
            }

            // 3) 文字 x 右移：ILOAD 15 / I2F 就是 drawStringWithShadow 的 x 参数。
            //    再压一行引用（ALOAD 10）交给 textX，让「缩进」和「画头像」用同一份解析结果：
            //    解析不到头像时 textX 原样返回，不留空占位。
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.ILOAD || ((VarInsnNode) insn).var != 15) continue;
                AbstractInsnNode next = skipMarkers(insn.getNext());
                if (next == null || next.getOpcode() != Opcodes.I2F) continue;
                InsnList ins = new InsnList();
                ins.add(new VarInsnNode(Opcodes.ALOAD, 10));
                ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "textX",
                        "(FLnet/minecraft/client/gui/ChatLine;)F", false));
                method.instructions.insert(next, ins);
                patched++;
                break;
            }

            // 4) 头像：文字存进局部变量之后插一条 drawAvatar(chatline, lineY)
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.getOpcode() != Opcodes.INVOKEINTERFACE
                        || !COMPONENT_OWNER.equals(call.owner)
                        || !"()Ljava/lang/String;".equals(call.desc)
                        || !matches(call.name, FORMATTED_NAMES)) {
                    continue;
                }
                AbstractInsnNode store = skipMarkers(call.getNext());
                if (!(store instanceof VarInsnNode) || store.getOpcode() != Opcodes.ASTORE) continue;
                InsnList ins = new InsnList();
                ins.add(new VarInsnNode(Opcodes.ALOAD, 10));
                ins.add(new VarInsnNode(Opcodes.ILOAD, 16));
                ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "drawAvatar",
                        "(Lnet/minecraft/client/gui/ChatLine;I)V", false));
                method.instructions.insert(store, ins);
                patched++;
                break;
            }

            // 5) 帧首：记录 updateCounter 并推进滚轮分帧
            InsnList head = new InsnList();
            head.add(new VarInsnNode(Opcodes.ILOAD, 1));
            head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "beginFrame", "(I)V", false));
            method.instructions.insert(head);

            return patched > 0;
        }
        return false;
    }

    /** 折行宽度：MathHelper.floor_float 之后、存局部变量之前扣掉头像占位。 */
    private static boolean patchSetChatLine(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!SET_LINE_DESC.equals(method.desc) || !matches(method.name, SET_LINE_NAMES)) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.getOpcode() != Opcodes.INVOKESTATIC
                        || !"net/minecraft/util/MathHelper".equals(call.owner)
                        || !"(F)I".equals(call.desc)
                        || !("floor_float".equals(call.name) || "func_76141_d".equals(call.name))) {
                    continue;
                }
                method.instructions.insert(call,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "wrapWidth", "(I)I", false));
                return true;
            }
            return false;
        }
        return false;
    }

    /** 滚轮：onScroll 返回 true 就跳过原版实现，由 beginFrame 分帧消化。 */
    private static boolean patchScroll(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!SCROLL_DESC.equals(method.desc) || !matches(method.name, SCROLL_NAMES)) continue;

            LabelNode skip = new LabelNode();
            InsnList ins = new InsnList();
            ins.add(new VarInsnNode(Opcodes.ILOAD, 1));
            ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "onScroll", "(I)Z", false));
            ins.add(new JumpInsnNode(Opcodes.IFEQ, skip));
            ins.add(new InsnNode(Opcodes.RETURN));
            ins.add(skip);
            ins.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(ins);
            return true;
        }
        return false;
    }

    /** 重置滚动：把还没走完的步进丢掉。 */
    private static boolean patchResetScroll(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!RESET_SCROLL_DESC.equals(method.desc) || !matches(method.name, RESET_SCROLL_NAMES)) continue;
            method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "onResetScroll", "()V", false));
            return true;
        }
        return false;
    }

    private static AbstractInsnNode skipMarkers(AbstractInsnNode insn) {
        AbstractInsnNode current = insn;
        while (current instanceof LabelNode || current instanceof org.objectweb.asm.tree.LineNumberNode) {
            current = current.getNext();
        }
        return current;
    }

    private static boolean matches(String name, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }
}
