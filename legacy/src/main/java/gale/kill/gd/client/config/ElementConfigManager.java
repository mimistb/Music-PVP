/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 */
package gale.kill.gd.client.config;

import com.google.gson.JsonObject;
import gale.kill.gd.client.config.ConfigManager;

public final class ElementConfigManager {
    public static JsonObject getElementConfig(String p, String e) {
        return ConfigManager.getElementConfig(p, e, true);
    }
}

