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
 * GaleAntiAim：在 NetworkManager#channelRead0 的第一条指令前把入参 Packet
 * 替换成 {@code GaleAntiAimHooks.interceptReceive(packet)} 的返回值。
 *
 * <p>选 channelRead0 而不是 handlePlayerPosLook，是因为前者拿到的还是未分发的包，
 * 可以直接替换成 RotationEngine 内部的 Teleport 子类；后者参数已经被固定。
 */
public final class GaleAntiAimTransformer implements IClassTransformer {

    private static final String OWNER = "net.minecraft.network.NetworkManager";
    private static final String METHOD = "channelRead0";
    private static final String DESC = "(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;)V";
    private static final String HOOK = "me/sorihiko/dango/core/GaleAntiAimHooks";

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
            if (!METHOD.equals(method.name) || !DESC.equals(method.desc)) continue;
            AbstractInsnNode first = method.instructions.getFirst();
            if (first == null) continue;
            InsnList add = new InsnList();
            add.add(new VarInsnNode(Opcodes.ALOAD, 2));
            add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "interceptReceive",
                    "(Lnet/minecraft/network/Packet;)Lnet/minecraft/network/Packet;", false));
            add.add(new VarInsnNode(Opcodes.ASTORE, 2));
            method.instructions.insertBefore(first, add);
            changed = true;
        }
        return changed;
    }
}
