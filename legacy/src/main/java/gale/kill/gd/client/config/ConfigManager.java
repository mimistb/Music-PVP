/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  com.google.gson.JsonParser
 */
package gale.kill.gd.client.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConfigManager {
    private static final Map<String, JsonObject> PRESETS = new LinkedHashMap<String, JsonObject>();
    private static String current = "00009";

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public static JsonObject read(String resource) {
        try (InputStream in = ConfigManager.class.getResourceAsStream("/assets/gd656killicon/" + resource);){
            if (in == null) {
                throw new IllegalArgumentException(resource);
            }
            JsonObject jsonObject = new JsonParser().parse((Reader)new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return jsonObject;
        }
        catch (IOException e) {
            throw new IllegalStateException(resource, e);
        }
    }

    public static JsonObject preset(String id) {
        JsonObject p = PRESETS.get(id);
        if (p == null) {
            p = ConfigManager.read("presets/official/" + id + ".json");
            PRESETS.put(id, p);
        }
        return p;
    }

    public static void select(String id) {
        ConfigManager.preset(id);
        current = id;
    }

    public static String getCurrentPresetId() {
        return current;
    }

    public static JsonObject getElementConfig(String category, String name) {
        return ConfigManager.getElementConfig(current, category + "/" + name, true);
    }

    public static JsonObject getElementConfig(String preset, String element, boolean ignored) {
        JsonObject p = ConfigManager.preset(preset);
        return p.has(element) && p.get(element).isJsonObject() ? p.getAsJsonObject(element) : null;
    }
}

