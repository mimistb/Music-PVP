/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.common.killtype;

import gale.kill.gd.common.config.ElementConfigRegistry;
import gale.kill.gd.common.killtype.KillTypeDefinition;
import gale.kill.gd.common.killtype.KillTypeDefinitionsArea;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class KillTypeRegistry {
    private static final Map<String, KillTypeDefinition> BY_ID = new LinkedHashMap<String, KillTypeDefinition>();
    private static final Map<Integer, KillTypeDefinition> BY_TYPE = new HashMap<Integer, KillTypeDefinition>();

    private KillTypeRegistry() {
    }

    public static void register(KillTypeDefinition def) {
        if (BY_ID.containsKey(def.id())) {
            throw new IllegalStateException("KillTypeRegistry: duplicate id: " + def.id());
        }
        if (BY_TYPE.containsKey(def.type())) {
            throw new IllegalStateException("KillTypeRegistry: duplicate type " + def.type() + " for id " + def.id());
        }
        BY_ID.put(def.id(), def);
        BY_TYPE.put(def.type(), def);
        ElementConfigRegistry.registerFormatKey("subtitle/kill_feed", def.formatKey(), "");
    }

    private static void validate() {
        for (KillTypeDefinition def : BY_ID.values()) {
            if (def.displayName() != null && !def.displayName().trim().isEmpty()) continue;
            throw new IllegalStateException("KillTypeRegistry[" + def.id() + "]: displayName is required");
        }
    }

    public static KillTypeDefinition get(int type) {
        return BY_TYPE.get(type);
    }

    public static KillTypeDefinition get(String id) {
        return BY_ID.get(id);
    }

    public static Collection<KillTypeDefinition> getAll() {
        return BY_ID.values();
    }

    public static int getKillTypeByTextureKey(String textureKey) {
        if (textureKey == null) {
            return -1;
        }
        for (KillTypeDefinition def : BY_ID.values()) {
            if (!textureKey.equals(def.textureKey())) continue;
            return def.type();
        }
        return -1;
    }

    public static int getKillTypeByFormatKey(String formatKey) {
        if (formatKey == null) {
            return -1;
        }
        for (KillTypeDefinition def : BY_ID.values()) {
            if (!formatKey.equals(def.formatKey())) continue;
            return def.type();
        }
        return -1;
    }

    static {
        for (KillTypeDefinition def : KillTypeDefinitionsArea.all()) {
            KillTypeRegistry.register(def);
        }
        KillTypeRegistry.validate();
    }
}

