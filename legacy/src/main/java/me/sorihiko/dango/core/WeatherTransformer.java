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
 * 天气切换：把三处原版天气/温度查询的返回值接到 WeatherHooks。
 *  - World#getRainStrength(float) / getThunderStrength(float)：每个 FRETURN 前改写
 *  - World#isRaining() / isThundering()、WorldInfo#isRaining() / isThundering()：每个 IRETURN 前改写
 *  - WorldChunkManager#getTemperatureAtHeight(float,int)：雪天返回低温
 */
public final class WeatherTransformer implements IClassTransformer {

    private static final String WORLD = "net.minecraft.world.World";
    private static final String WORLD_INFO = "net.minecraft.world.storage.WorldInfo";
    private static final String CHUNK = "net.minecraft.world.biome.WorldChunkManager";
    private static final String HOOK = "me/sorihiko/dango/core/WeatherHooks";

    private static final String[] RAIN_NAMES = {"getRainStrength", "func_72867_j"};
    private static final String[] THUNDER_NAMES = {"getThunderStrength", "func_72819_i"};
    private static final String[] WORLD_RAIN_NAMES = {"isRaining", "func_72896_J"};
    private static final String[] WORLD_THUNDER_NAMES = {"isThundering", "func_72911_I"};
    private static final String[] INFO_RAIN_NAMES = {"isRaining", "func_76059_o"};
    private static final String[] INFO_THUNDER_NAMES = {"isThundering", "func_76061_m"};
    private static final String[] TEMP_NAMES = {"getTemperatureAtHeight", "func_76939_a"};

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        boolean world = WORLD.equals(transformedName) || WORLD.equals(name);
        boolean info = WORLD_INFO.equals(transformedName) || WORLD_INFO.equals(name);
        boolean chunk = CHUNK.equals(transformedName) || CHUNK.equals(name);
        if (!world && !info && !chunk) return basicClass;
        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            reader.accept(node, 0);
            boolean changed = false;
            if (world) {
                changed |= patchFloats(node, RAIN_NAMES, "rainStrength");
                changed |= patchFloats(node, THUNDER_NAMES, "thunderStrength");
                changed |= patchBools(node, WORLD_RAIN_NAMES, "raining");
                changed |= patchBools(node, WORLD_THUNDER_NAMES, "thundering");
            }
            if (info) {
                changed |= patchBools(node, INFO_RAIN_NAMES, "raining");
                changed |= patchBools(node, INFO_THUNDER_NAMES, "thundering");
            }
            if (chunk) {
                changed |= patchTemperature(node);
            }
            if (!changed) return basicClass;
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            return basicClass;
        }
    }

    private static boolean patchFloats(ClassNode node, String[] names, String hook) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (!"(F)F".equals(method.desc) || !matches(method.name, names)) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.FRETURN) continue;
                InsnList add = new InsnList();
                add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, hook, "(F)F", false));
                method.instructions.insertBefore(insn, add);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean patchBools(ClassNode node, String[] names, String hook) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (!"()Z".equals(method.desc) || !matches(method.name, names)) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.IRETURN) continue;
                InsnList add = new InsnList();
                add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, hook, "(Z)Z", false));
                method.instructions.insertBefore(insn, add);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean patchTemperature(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (!"(FI)F".equals(method.desc) || !matches(method.name, TEMP_NAMES)) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.FRETURN) continue;
                InsnList add = new InsnList();
                add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "temperature", "(F)F", false));
                method.instructions.insertBefore(insn, add);
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
