package fpsport.selftest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.sorihiko.dango.config.ConfigSchema;
import me.sorihiko.dango.module.values.KeyBindValue;
import me.sorihiko.dango.ui.LayoutBounds;
import me.sorihiko.dango.ui.SnapEngine;
import me.sorihiko.dango.ui.SnapMode;
import me.sorihiko.dango.ui.SnapResult;

/**
 * 本轮新增能力的无头单测：配置 schema 迁移 / 孤儿键清理、键位组合解析与冲突、HUD 吸附引擎。
 * 与 [ModuleTestRunner] 一样不依赖 Minecraft 运行时，由它统一调度与汇总。
 */
final class CoreLogicTest {

    private CoreLogicTest() {
    }

    static void run(ModuleTestRunner.Assertions a) {
        configSchema(a);
        keyBinds(a);
        snapping(a);
    }

    // ================= 配置 schema =================

    private static void configSchema(ModuleTestRunner.Assertions a) {
        Map<String, Map<String, Class<?>>> defs = new HashMap<String, Map<String, Class<?>>>();
        Map<String, Class<?>> music = new HashMap<String, Class<?>>();
        music.put("openKey", KeyBindValue.class);
        music.put("volume", float.class);
        defs.put("Music", music);
        Map<String, Class<?>> hud = new HashMap<String, Class<?>>();
        hud.put("posX", float.class);
        defs.put("Hud", hud);

        // 1 v1 → v2：数字键位被统一成字符串格式
        JsonObject root = new JsonParser().parse("{\"Music\":{\"values\":{\"openKey\":42,\"volume\":0.5}}}")
                .getAsJsonObject();
        ConfigSchema.Report r = ConfigSchema.INSTANCE.migrate(root, defs,
                Collections.<String>emptySet(), new ConfigSchema.Policy(false, false, false,
                        Collections.<String>emptySet(), false));
        a.eq("schema version upgraded", ConfigSchema.INSTANCE.versionOf(root), ConfigSchema.CURRENT);
        a.eq("keybind normalized", root.getAsJsonObject("Music").getAsJsonObject("values")
                .get("openKey").getAsString(), "42");
        a.check("schema report migrated", r.getMigrated());

        // 2 孤儿模块被清理
        root = new JsonParser().parse("{\"Music\":{\"values\":{}},\"Deleted\":{\"values\":{\"a\":1}}}")
                .getAsJsonObject();
        r = ConfigSchema.INSTANCE.migrate(root, defs, Collections.<String>emptySet(),
                new ConfigSchema.Policy(false, false, false, Collections.<String>emptySet(), false));
        a.check("orphan module removed", !root.has("Deleted"));
        a.eq("orphan module reported", r.getRemovedModules().size(), 1);
        a.eq("orphan module name", r.getRemovedModules().get(0), "Deleted");

        // 3 保留规则生效：preserve 指定 "Music.openKey"
        root = new JsonParser().parse("{\"Music\":{\"values\":{\"openKey\":\"42\",\"gone\":1}}}")
                .getAsJsonObject();
        Set<String> preserve = new HashSet<String>();
        preserve.add("Music.gone");
        r = ConfigSchema.INSTANCE.migrate(root, defs, Collections.<String>emptySet(),
                new ConfigSchema.Policy(false, false, false, preserve, false));
        a.check("preserved value kept",
                root.getAsJsonObject("Music").getAsJsonObject("values").has("gone"));
        a.eq("no value removed", r.getRemovedValues().size(), 0);

        // 4 未知设置项在默认策略下被清理
        root = new JsonParser().parse("{\"Music\":{\"values\":{\"gone\":1}}}").getAsJsonObject();
        r = ConfigSchema.INSTANCE.migrate(root, defs, Collections.<String>emptySet(),
                new ConfigSchema.Policy(false, false, false, Collections.<String>emptySet(), false));
        a.check("orphan value removed", !root.getAsJsonObject("Music").getAsJsonObject("values").has("gone"));

        // 5 孤儿界面键：登记过界面键后才清理（v4 起配置不再写入 ui 段，这里直接构造 v4 结构验证清理逻辑）
        root = new JsonParser().parse("{\"schema\":4,\"Music\":{\"values\":{}},\"ui\":{\"groups\":{\"A\":true,\"B\":true}}}")
                .getAsJsonObject();
        Set<String> known = new HashSet<String>();
        known.add("A");
        r = ConfigSchema.INSTANCE.migrate(root, defs, known,
                new ConfigSchema.Policy(false, false, false, Collections.<String>emptySet(), false));
        a.check("known ui key kept", root.getAsJsonObject("ui").getAsJsonObject("groups").has("A"));
        a.check("orphan ui key removed", !root.getAsJsonObject("ui").getAsJsonObject("groups").has("B"));

        // 6 未来版本只读：不迁移、不清理
        root = new JsonParser().parse("{\"schema\":99,\"Deleted\":{\"values\":{}}}").getAsJsonObject();
        r = ConfigSchema.INSTANCE.migrate(root, defs, Collections.<String>emptySet(),
                new ConfigSchema.Policy(false, false, false, Collections.<String>emptySet(), false));
        a.check("future version untouched", root.has("Deleted"));
        a.eq("future version no removal", r.getRemovedModules().size(), 0);

        // 7 保留规则序列化往返
        Set<String> p = new HashSet<String>();
        p.add("X.y");
        JsonObject policyJson = new JsonObject();
        policyJson.add("schemaPolicy", ConfigSchema.INSTANCE.policyTo(
                new ConfigSchema.Policy(true, false, true, p, true)));
        ConfigSchema.Policy back = ConfigSchema.INSTANCE.policyFrom(policyJson);
        a.check("policy roundtrip modules", back.getRetainUnknownModules());
        a.check("policy roundtrip ui", back.getRetainUnknownUiKeys());
        a.eq("policy roundtrip preserve", back.getPreserve().size(), 1);
    }

    // ================= 键位 =================

    private static void keyBinds(ModuleTestRunner.Assertions a) {
        // 1 组合键往返
        KeyBindValue v = new KeyBindValue();
        v.load("42+33");
        a.eq("combo primary", v.getPrimary(), 33);
        a.eq("combo modifiers", v.getModifiers().length, 1);
        a.eq("combo storage", v.storage(), "42+33");
        a.check("combo isSet", v.isSet());

        // 2 旧格式（单个数字）兼容
        KeyBindValue legacy = new KeyBindValue();
        legacy.load("42");
        a.eq("legacy primary", legacy.getPrimary(), 42);
        a.eq("legacy no modifiers", legacy.getModifiers().length, 0);

        // 3 非法输入被过滤
        KeyBindValue bad = new KeyBindValue();
        bad.load("0+abc");
        a.check("invalid combo rejected", !bad.isSet());
        KeyBindValue empty = new KeyBindValue();
        empty.load("");
        a.check("empty combo rejected", !empty.isSet());

        // 4 冲突：主键相同 + 修饰键互为子集
        KeyBindValue plain = new KeyBindValue();
        plain.load("33");
        KeyBindValue ctrl = new KeyBindValue();
        ctrl.load("29+33");
        a.check("subset conflict", plain.conflictsWith(ctrl));
        KeyBindValue alt = new KeyBindValue();
        alt.load("56+33");
        a.check("different modifier no conflict", !ctrl.conflictsWith(alt));
        KeyBindValue other = new KeyBindValue();
        other.load("34");
        a.check("different primary no conflict", !plain.conflictsWith(other));

        // 5 鼠标键码（第三方输入设备 / 鼠标绑定）
        KeyBindValue mouse = new KeyBindValue();
        mouse.load("-100");
        a.check("mouse bind accepted", mouse.isSet());
        a.eq("mouse label", KeyBindValue.Companion.labelOf(-100), "鼠标左键");

        // 6 默认键位复位
        KeyBindValue def = new KeyBindValue(new int[]{33}, false);
        KeyBindValue changed = new KeyBindValue(new int[]{33}, false);
        changed.load("42+33");
        changed.resetDefault();
        a.eq("reset to default", changed.storage(), def.storage());

        // 7 扩展键码（自定义设备映射）可保存
        KeyBindValue ext = new KeyBindValue();
        ext.load("400");
        a.check("extended key kept", ext.isSet());
        a.eq("extended key value", ext.getPrimary(), 400);
    }

    // ================= 吸附 =================

    private static void snapping(ModuleTestRunner.Assertions a) {
        SnapEngine engine = new SnapEngine();
        List<LayoutBounds> none = Collections.emptyList();

        // 1 网格吸附：10 → 8
        SnapResult r = engine.snap(10f, 10f, 100f, 40f, 1280f, 800f, none,
                EnumSet.of(SnapMode.GRID), 6f, 8f);
        a.eq("grid snap x", r.getX(), 8f);
        a.eq("grid snap y", r.getY(), 8f);
        a.check("grid snapped", r.getSnapped());

        // 2 超出容差不吸附
        r = engine.snap(10f, 10f, 100f, 40f, 1280f, 800f, none,
                EnumSet.of(SnapMode.GRID), 1f, 8f);
        a.eq("grid out of tolerance", r.getX(), 10f);
        a.check("grid not snapped", !r.getSnapped());

        // 3 屏幕边缘吸附（左边 + 垂直居中）
        r = engine.snap(3f, 380f, 100f, 40f, 1280f, 800f, none,
                EnumSet.of(SnapMode.EDGE), 6f, 8f);
        a.eq("edge left x", r.getX(), 0f);
        a.eq("edge center y", r.getY(), 380f);
        a.eq("edge guide x", r.getGuideX().floatValue(), 0f);

        // 4 与其它元素左边缘对齐
        List<LayoutBounds> others = new ArrayList<LayoutBounds>();
        others.add(new LayoutBounds(200f, 100f, 120f, 60f));
        r = engine.snap(204f, 103f, 100f, 40f, 1280f, 800f, others,
                EnumSet.of(SnapMode.ALIGN), 6f, 8f);
        a.eq("align left", r.getX(), 200f);
        a.eq("align top", r.getY(), 100f);
        a.eq("align guide x", r.getGuideX().floatValue(), 200f);

        // 5 关闭所有模式时原样返回
        r = engine.snap(10f, 10f, 100f, 40f, 1280f, 800f, others,
                EnumSet.noneOf(SnapMode.class), 6f, 8f);
        a.eq("no modes x", r.getX(), 10f);
        a.check("no modes not snapped", !r.getSnapped());

        // 6 模式位掩码往返
        a.eq("mask all", SnapMode.Companion.toMask(
                EnumSet.of(SnapMode.ALIGN, SnapMode.GRID, SnapMode.EDGE)), 7);
        a.eq("mask parse size", SnapMode.Companion.fromMask(5).size(), 2);
        a.check("mask parse contains align",
                SnapMode.Companion.fromMask(5).contains(SnapMode.ALIGN));
    }
}
