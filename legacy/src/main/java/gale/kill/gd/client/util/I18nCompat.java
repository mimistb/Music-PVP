/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.util.StatCollector
 */
package gale.kill.gd.client.util;

import net.minecraft.util.StatCollector;

public final class I18nCompat {
    public static boolean exists(String key) {
        return StatCollector.canTranslate((String)key);
    }
}

