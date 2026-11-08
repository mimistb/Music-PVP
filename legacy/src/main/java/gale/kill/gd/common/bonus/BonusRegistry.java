/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.common.bonus;

import gale.kill.gd.common.bonus.BonusDefinition;
import gale.kill.gd.common.bonus.BonusDefinitionsArea;
import gale.kill.gd.common.config.ElementConfigRegistry;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class BonusRegistry {
    private static final Map<String, BonusDefinition> BY_ID = new LinkedHashMap<String, BonusDefinition>();
    private static final Map<Integer, BonusDefinition> BY_TYPE = new HashMap<Integer, BonusDefinition>();
    private static final Map<String, Integer> FORMAT_KEY_TO_TYPE = new HashMap<String, Integer>();
    private static final Set<String> FORMAT_KEYS = new HashSet<String>();

    private BonusRegistry() {
    }

    public static void register(BonusDefinition def) {
        if (BY_ID.containsKey(def.id())) {
            throw new IllegalStateException("BonusRegistry: duplicate bonus id: " + def.id());
        }
        if (BY_TYPE.containsKey(def.type())) {
            throw new IllegalStateException("BonusRegistry: duplicate bonus type " + def.type() + " for id " + def.id());
        }
        BY_ID.put(def.id(), def);
        BY_TYPE.put(def.type(), def);
        ElementConfigRegistry.registerFormatKey("subtitle/bonus_list", def.formatConfigKey(), "");
        FORMAT_KEY_TO_TYPE.put(def.formatConfigKey(), def.type());
        FORMAT_KEYS.add(def.formatConfigKey());
    }

    private static void validate() {
        for (BonusDefinition def : BY_ID.values()) {
            if (!BonusRegistry.isBlank(def.displayName())) continue;
            throw new IllegalStateException("BonusRegistry[" + def.id() + "]: displayName is required");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static BonusDefinition get(String id) {
        return BY_ID.get(id);
    }

    public static BonusDefinition get(int type) {
        return BY_TYPE.get(type);
    }

    public static boolean isRegistered(int type) {
        return BY_TYPE.containsKey(type);
    }

    public static Collection<BonusDefinition> getAll() {
        return BY_ID.values();
    }

    public static boolean isFormatKey(String key) {
        return FORMAT_KEYS.contains(key);
    }

    public static int getTypeByFormatKey(String formatKey) {
        Integer type = FORMAT_KEY_TO_TYPE.get(formatKey);
        return type != null ? type : -1;
    }

    public static String nameKey(int type) {
        BonusDefinition def = BonusRegistry.get(type);
        return def != null ? def.displayName() : "";
    }

    public static String descKey(int type) {
        BonusDefinition def = BonusRegistry.get(type);
        return def != null ? def.description() : "";
    }

    static {
        for (BonusDefinition def : BonusDefinitionsArea.all()) {
            BonusRegistry.register(def);
        }
        BonusRegistry.validate();
    }
}

