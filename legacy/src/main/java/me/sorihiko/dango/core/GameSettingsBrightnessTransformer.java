package me.sorihiko.dango.core;

import fpsport.render.FullBright;
import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * 永久亮度存盘保护：在 {@code GameSettings#saveOptions()} 方法头插入
 * {@link FullBright#beforeSaveOptions()}。
 *
 * <p>开启永久亮度时 gamma 一直是 100。原版保存设置（滑条松手、点"完成"、退出游戏）
 * 会把这个 100 原样写进 options.txt，下次启动即使功能关着亮度也是上限——
 * 表现就是"永久亮度关不掉 / 亮度滑条失效"。存盘前先把 gamma 还原成用户值，
 * 下一帧 {@code FullBright.onUpdate()} 会自动写回 100，游戏内亮度看不出变化。
 */
public final class GameSettingsBrightnessTransformer implements IClassTransformer {

    private static final String GAME_SETTINGS = "net.minecraft.client.settings.GameSettings";

    /** saveOptions / func_74303_b（见 mcp_stable-22-1.8.9 methods.csv） */
    private static final String[] SAVE_OPTIONS_NAMES = {"saveOptions", "func_74303_b"};

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        boolean target = GAME_SETTINGS.equals(transformedName) || GAME_SETTINGS.equals(name);
        if (!target) return basicClass;
        try {
            ClassReader reader = new ClassReader(basicClass);
            ClassNode node = new ClassNode(Opcodes.ASM5);
            reader.accept(node, 0);

            boolean changed = false;
            for (MethodNode method : node.methods) {
                if (!"()V".equals(method.desc) || !matches(method.name, SAVE_OPTIONS_NAMES)) continue;
                InsnList add = new InsnList();
                add.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "fpsport/render/FullBright",
                        "beforeSaveOptions",
                        "()V",
                        false
                ));
                method.instructions.insert(add);
                changed = true;
            }
            if (!changed) return basicClass;

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            return basicClass;
        }
    }

    private static boolean matches(String name, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }
}
