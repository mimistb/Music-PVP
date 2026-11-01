package me.sorihiko.dango.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 1.8.9 里受伤红闪的四个常量（1.0F, 0.0F, 0.0F, 0.3F）换成 RendererLivingEntity 上的静态字段，
 * 运行时由 HitColorBridge 反射写入。
 *
 * 只改 func_177092_a（MCP: setBrightness）里那一处 FloatBuffer.put 序列，
 * 关闭时字段保持 1/0/0/0.3，和原版逐像素一致；其它逻辑（纹理合成、护甲/附魔光效）一律不动。
 */
public final class HitColorTransformer implements IClassTransformer {

    private static final String TARGET = "net.minecraft.client.renderer.entity.RendererLivingEntity";
    private static final String FLOAT_BUFFER = "java/nio/FloatBuffer";
    private static final String FLOAT_BUFFER_DESC = "Ljava/nio/FloatBuffer;";
    private static final String PUT = "put";
    private static final String PUT_DESC = "(F)Ljava/nio/FloatBuffer;";

    private static final String[] FIELDS = {"dango$hitR", "dango$hitG", "dango$hitB", "dango$hitA"};
    private static final float[] VANILLA = {1.0F, 0.0F, 0.0F, 0.3F};

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        if (!TARGET.equals(transformedName) && !TARGET.equals(name)) return basicClass;
        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            reader.accept(node, 0);

            boolean patched = false;
            for (MethodNode method : node.methods) {
                if (patchMethod(method, node.name)) patched = true;
            }
            if (!patched) return basicClass;

            addFieldsIfMissing(node);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            // 补丁失败就原样返回，绝不能因为这里让游戏起不来
            return basicClass;
        }
    }

    private static void addFieldsIfMissing(ClassNode node) {
        for (int i = 0; i < FIELDS.length; i++) {
            if (hasField(node, FIELDS[i])) continue;
            node.fields.add(new FieldNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    FIELDS[i], "F", null, VANILLA[i]));
        }
    }

    private static boolean hasField(ClassNode node, String name) {
        for (FieldNode f : node.fields) if (name.equals(f.name)) return true;
        return false;
    }

    /** 找 FloatBuffer.put 的四个连续常量参数并替换成 GETSTATIC */
    private static boolean patchMethod(MethodNode method, String owner) {
        // 过滤掉 Label / LineNumber / Frame 这些不占操作数栈的节点，只留真实指令，
        // 否则语句之间的行号节点会让固定偏移失效
        List<AbstractInsnNode> ops = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode n = method.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() >= 0) ops.add(n);
        }

        boolean any = false;
        // 原版形态（每个颜色分量都是独立语句，put 的返回值会被 pop）：
        //   aload_0, getfield field_177095_g, <常量>, put, pop   × 4
        // 过滤后每段占 5 个下标：aload_0 / getfield / 常量 / put / pop
        for (int i = 0; i + 18 < ops.size(); i++) {
            if (!isBufferField(ops.get(i), owner)) continue;
            if (!isAload0(ops.get(i + 4)) || !isAload0(ops.get(i + 9)) || !isAload0(ops.get(i + 14))) continue;
            Float v0 = floatValue(ops.get(i + 1));
            Float v1 = floatValue(ops.get(i + 6));
            Float v2 = floatValue(ops.get(i + 11));
            Float v3 = floatValue(ops.get(i + 16));
            if (v0 == null || v1 == null || v2 == null || v3 == null) continue;
            if (v0 != VANILLA[0] || v1 != VANILLA[1] || v2 != VANILLA[2] || v3 != VANILLA[3]) continue;
            if (!isBufferField(ops.get(i + 5), owner) || !isBufferField(ops.get(i + 10), owner)
                    || !isBufferField(ops.get(i + 15), owner)) continue;
            if (!isBufferPut(ops.get(i + 2)) || !isBufferPut(ops.get(i + 7))
                    || !isBufferPut(ops.get(i + 12)) || !isBufferPut(ops.get(i + 17))) continue;
            if (!isPop(ops.get(i + 3)) || !isPop(ops.get(i + 8))
                    || !isPop(ops.get(i + 13)) || !isPop(ops.get(i + 18))) continue;
            for (int k = 0; k < 4; k++) {
                method.instructions.set(ops.get(i + 1 + k * 5), new FieldInsnNode(
                        Opcodes.GETSTATIC, owner, FIELDS[k], "F"));
            }
            any = true;
            i += 18;
        }
        return any;
    }

    /** aload_0 + getfield <本类> field_177095_g（受伤红闪用的那个 FloatBuffer） */
    private static boolean isBufferField(AbstractInsnNode node, String owner) {
        if (!(node instanceof FieldInsnNode)) return false;
        FieldInsnNode f = (FieldInsnNode) node;
        return owner.equals(f.owner) && FLOAT_BUFFER_DESC.equals(f.desc);
    }

    private static boolean isPop(AbstractInsnNode node) {
        return node.getOpcode() == Opcodes.POP;
    }

    /** 每段开头的 aload_0（读取 this.field_177095_g） */
    private static boolean isAload0(AbstractInsnNode node) {
        if (!(node instanceof VarInsnNode)) return false;
        VarInsnNode v = (VarInsnNode) node;
        return v.getOpcode() == Opcodes.ALOAD && v.var == 0;
    }

    private static boolean isBufferPut(AbstractInsnNode node) {
        if (!(node instanceof MethodInsnNode)) return false;
        MethodInsnNode m = (MethodInsnNode) node;
        return FLOAT_BUFFER.equals(m.owner) && PUT.equals(m.name) && PUT_DESC.equals(m.desc);
    }

    private static Float floatValue(AbstractInsnNode node) {
        if (node instanceof InsnNode) {
            int op = node.getOpcode();
            if (op == Opcodes.FCONST_0) return 0.0F;
            if (op == Opcodes.FCONST_1) return 1.0F;
            if (op == Opcodes.FCONST_2) return 2.0F;
            return null;
        }
        if (node instanceof LdcInsnNode) {
            Object c = ((LdcInsnNode) node).cst;
            if (c instanceof Float) return (Float) c;
        }
        return null;
    }
}
