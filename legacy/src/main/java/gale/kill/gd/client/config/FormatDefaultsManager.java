/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  net.minecraft.client.Minecraft
 */
package gale.kill.gd.client.config;

import com.google.gson.JsonObject;
import gale.kill.gd.client.config.ConfigManager;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;

public final class FormatDefaultsManager {
    private static final Map<String, JsonObject> FORMATS = new HashMap<String, JsonObject>();

    public static String getDefault(String element, String key) {
        JsonObject formats;
        String lang = Minecraft.getMinecraft().getLanguageManager().getCurrentLanguage().getLanguageCode().toLowerCase(Locale.ROOT);
        if (!(lang.equals("zh_cn") || lang.equals("zh_tw") || lang.equals("lzh"))) {
            lang = "en_us";
        }
        if ((formats = FORMATS.get(lang)) == null) {
            formats = ConfigManager.read("formats/" + lang + ".json");
            FORMATS.put(lang, formats);
        }
        JsonObject values = formats.has(element) ? formats.getAsJsonObject(element) : null;
        return values != null && values.has(key) ? values.get(key).getAsString() : "";
    }
}

