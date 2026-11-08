/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 */
package gale.kill.gd.client.config;

import com.google.gson.JsonObject;
import gale.kill.gd.client.config.ElementConfigManager;
import gale.kill.gd.client.config.ValorantStyleCatalog;
import gale.kill.gd.client.textures.ExternalTextureManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ElementTextureDefinition {
    public static final String VALORANT_SKIN_PRIME = "prime";
    public static final String VALORANT_SKIN_GLITCHPOP = "glitchpop";
    public static final String VALORANT_SKIN_SINGULARITY_V1 = "singularity_v1";
    public static final String VALORANT_SKIN_SINGULARITY_V2 = "singularity_v2";
    public static final String VALORANT_SKIN_SINGULARITY_V3 = "singularity_v3";
    public static final String VALORANT_SKIN_GAIA = "gaia";
    public static final String VALORANT_SKIN_GAIA_V1 = "gaia_v1";
    public static final String VALORANT_SKIN_GAIA_V2 = "gaia_v2";
    public static final String VALORANT_SKIN_GAIA_V3 = "gaia_v3";
    public static final Map<String, List<String>> ELEMENT_TEXTURES;

    public static List<String> getTextures(String elementId) {
        return ELEMENT_TEXTURES.getOrDefault(elementId, Collections.emptyList());
    }

    public static boolean hasTextures(String elementId) {
        return ELEMENT_TEXTURES.containsKey(elementId);
    }

    public static String getTextureFileName(String presetId, String elementId, String textureKey) {
        if (elementId == null || textureKey == null) {
            return null;
        }
        switch (elementId) {
            case "kill_icon/scrolling": {
                return ElementTextureDefinition.resolveScrollingFileName(presetId, textureKey);
            }
            case "kill_icon/combo": {
                return "killicon_" + textureKey + ".png";
            }
            case "kill_icon/valorant": {
                return ValorantStyleCatalog.getOfficialTextureFileName(presetId, textureKey);
            }
            case "kill_icon/card": {
                return "killicon_card_" + textureKey + ".png";
            }
            case "kill_icon/card_bar": {
                return "killicon_card_" + textureKey + ".png";
            }
            case "kill_icon/battlefield1": {
                return "killicon_battlefield1_" + ElementTextureDefinition.normalizeDestroyVehicle(textureKey) + ".png";
            }
        }
        return null;
    }

    public static String getSelectedTextureFileName(String presetId, String elementId, String textureKey) {
        JsonObject config2 = ElementConfigManager.getElementConfig(presetId, elementId);
        return ElementTextureDefinition.getSelectedTextureFileName(presetId, elementId, textureKey, config2);
    }

    public static String getSelectedTextureFileName(String presetId, String elementId, String textureKey, JsonObject config2) {
        String fileName;
        String mode;
        String defaultFileName = ElementTextureDefinition.getDefaultSelectedTextureFileName(presetId, elementId, textureKey, config2);
        if (config2 == null) {
            return defaultFileName;
        }
        String modeKey = ElementTextureDefinition.getTextureModeKey(textureKey);
        String string = mode = config2.has(modeKey) ? config2.get(modeKey).getAsString() : "official";
        if ("vanilla".equalsIgnoreCase(mode)) {
            String path;
            String vanillaKey = ElementTextureDefinition.getVanillaTextureKey(textureKey);
            if (config2.has(vanillaKey) && ExternalTextureManager.isVanillaTexturePath(path = config2.get(vanillaKey).getAsString())) {
                return path;
            }
            return defaultFileName;
        }
        if ("custom".equalsIgnoreCase(mode)) {
            String fileName2;
            String fileName3;
            String key = ElementTextureDefinition.getCustomTextureKey(textureKey);
            if (config2.has(key) && ExternalTextureManager.isCustomTextureName(fileName3 = config2.get(key).getAsString())) {
                return fileName3;
            }
            String fallbackKey = ElementTextureDefinition.getOfficialTextureKey(textureKey);
            if (config2.has(fallbackKey) && ExternalTextureManager.isCustomTextureName(fileName2 = config2.get(fallbackKey).getAsString())) {
                return fileName2;
            }
            return defaultFileName;
        }
        String officialKey = ElementTextureDefinition.getOfficialTextureKey(textureKey);
        if (config2.has(officialKey) && ExternalTextureManager.isOfficialTextureName(fileName = config2.get(officialKey).getAsString())) {
            return fileName;
        }
        return defaultFileName;
    }

    private static String getDefaultSelectedTextureFileName(String presetId, String elementId, String textureKey, JsonObject config2) {
        String styleId;
        String styleFileName;
        if ("kill_icon/valorant".equals(elementId) && (styleFileName = ValorantStyleCatalog.getOfficialTextureFileNameForStyle(styleId = ValorantStyleCatalog.resolveStyleId(presetId, config2), textureKey)) != null) {
            return styleFileName;
        }
        return ElementTextureDefinition.getTextureFileName(presetId, elementId, textureKey);
    }

    public static String getOfficialTextureKey(String textureKey) {
        return "texture_style_" + textureKey;
    }

    public static String getCustomTextureKey(String textureKey) {
        return "custom_texture_" + textureKey;
    }

    public static String getTextureModeKey(String textureKey) {
        return "texture_mode_" + textureKey;
    }

    public static String getVanillaTextureKey(String textureKey) {
        return "vanilla_texture_" + textureKey;
    }

    private static String resolveScrollingFileName(String presetId, String textureKey) {
        if ("spot_assist".equals(textureKey) && !"00037".equals(presetId)) {
            return "killicon_scrolling_assist.png";
        }
        if ("00007".equals(presetId)) {
            switch (textureKey) {
                case "headshot": {
                    return "killicon_battlefield5_headshot.png";
                }
                case "assist": {
                    return "killicon_battlefield5_assist.png";
                }
                case "capture": {
                    return "killicon_battlefield5_capture.png";
                }
                case "destroy_vehicle": {
                    return "killicon_battlefield5_destroyvehicle.png";
                }
                case "vehicle_destroy_assist": {
                    return "killicon_battlefield5_vehicle_destroy_assist.png";
                }
                case "medic": {
                    return "killicon_battlefield5_medic.png";
                }
                case "explosion": 
                case "crit": 
                case "default": {
                    return "killicon_battlefield5_default.png";
                }
            }
            return "killicon_battlefield5_default.png";
        }
        if ("00001".equals(presetId)) {
            switch (textureKey) {
                case "vehicle_destroy_assist": {
                    return "killicon_df_vehicle_destroy_assist.png";
                }
                case "medic": {
                    return "killicon_df_medic.png";
                }
                case "capture": {
                    return "killicon_df_capture.png";
                }
            }
            return "killicon_scrolling_" + ElementTextureDefinition.normalizeDestroyVehicle(textureKey) + ".png";
        }
        if ("00008".equals(presetId)) {
            switch (textureKey) {
                case "headshot": {
                    return "killicon_df_headshot.png";
                }
                case "destroy_vehicle": {
                    return "killicon_df_destroyvehicle.png";
                }
                case "assist": {
                    return "killicon_scrolling_assist.png";
                }
                case "capture": {
                    return "killicon_df_capture.png";
                }
                case "vehicle_destroy_assist": {
                    return "killicon_df_vehicle_destroy_assist.png";
                }
                case "medic": {
                    return "killicon_df_medic.png";
                }
                case "explosion": 
                case "crit": 
                case "default": {
                    return "killicon_df_default.png";
                }
            }
            return "killicon_df_default.png";
        }
        if ("00037".equals(presetId)) {
            switch (textureKey) {
                case "headshot": {
                    return "killicon_battlefield6_headshot.png";
                }
                case "explosion": {
                    return "killicon_battlefield6_explosion.png";
                }
                case "crit": {
                    return "killicon_battlefield6_crit.png";
                }
                case "destroy_vehicle": {
                    return "killicon_battlefield6_destroyvehicle.png";
                }
                case "assist": {
                    return "killicon_battlefield6_assist.png";
                }
                case "capture": {
                    return "killicon_battlefield6_capture.png";
                }
                case "vehicle_destroy_assist": {
                    return "killicon_battlefield6_vehicle_destroy_assist.png";
                }
                case "medic": {
                    return "killicon_battlefield6_medic.png";
                }
                case "spot_assist": {
                    return "killicon_battlefield6_spot_assist.png";
                }
            }
            return "killicon_battlefield6_default.png";
        }
        if ("capture".equals(textureKey)) {
            return "killicon_df_capture.png";
        }
        return "killicon_scrolling_" + ElementTextureDefinition.normalizeDestroyVehicle(textureKey) + ".png";
    }

    private static String normalizeDestroyVehicle(String textureKey) {
        return "destroy_vehicle".equals(textureKey) ? "destroyvehicle" : textureKey;
    }

    static {
        HashMap<String, List<String>> map = new HashMap<String, List<String>>();
        map.put("kill_icon/scrolling", Arrays.asList("default", "headshot", "explosion", "crit", "destroy_vehicle", "assist", "capture", "vehicle_destroy_assist", "medic", "spot_assist"));
        map.put("kill_icon/combo", Arrays.asList("combo_1", "combo_2", "combo_3", "combo_4", "combo_5", "combo_6"));
        map.put("kill_icon/valorant", Arrays.asList("emblem", "frame", "blade", "bar", "headshot", "base_particle", "hero_flame", "large_sparks", "x_sparks"));
        map.put("kill_icon/card", Arrays.asList("default_ct", "default_t", "headshot_ct", "headshot_t", "explosion_ct", "explosion_t", "crit_ct", "crit_t", "light_ct", "light_t"));
        map.put("kill_icon/card_bar", Arrays.asList("bar_ct", "bar_t"));
        map.put("kill_icon/battlefield1", Arrays.asList("default", "headshot", "explosion", "crit", "destroy_vehicle"));
        ELEMENT_TEXTURES = Collections.unmodifiableMap(map);
    }
}

