/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 */
package gale.kill.gd.client.config;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ValorantStyleCatalog {
    public static final String STYLE_PRIME = "prime";
    public static final String STYLE_GLITCHPOP = "glitchpop";
    public static final String STYLE_SINGULARITY_V1 = "singularity_v1";
    public static final String STYLE_SINGULARITY_V2 = "singularity_v2";
    public static final String STYLE_SINGULARITY_V3 = "singularity_v3";
    public static final String STYLE_GAIA = "gaia";
    public static final String STYLE_GAIA_V1 = "gaia_v1";
    public static final String STYLE_GAIA_V2 = "gaia_v2";
    public static final String STYLE_GAIA_V3 = "gaia_v3";
    public static final String STYLE_BUBBLEGUM_DEATHWISH = "bubblegum_deathwish";
    public static final String STYLE_BUBBLEGUM_DEATHWISH_V1 = "bubblegum_deathwish_v1";
    public static final String STYLE_BUBBLEGUM_DEATHWISH_V2 = "bubblegum_deathwish_v2";
    public static final String STYLE_BUBBLEGUM_DEATHWISH_V3 = "bubblegum_deathwish_v3";
    public static final String STYLE_CHAMPIONS_2021 = "champions_2021";
    public static final String STYLE_PRELUDE_TO_CHAOS_V1 = "prelude_to_chaos_v1";
    public static final String STYLE_PRELUDE_TO_CHAOS_V2 = "prelude_to_chaos_v2";
    public static final String STYLE_PRELUDE_TO_CHAOS_V3 = "prelude_to_chaos_v3";
    public static final String STYLE_PRIMORDIUM = "primordium";
    public static final String STYLE_PRIMORDIUM_V1 = "primordium_v1";
    public static final String STYLE_PRIMORDIUM_V2 = "primordium_v2";
    public static final String STYLE_PRIMORDIUM_V3 = "primordium_v3";
    public static final String STYLE_RADIANT_CRISIS_001 = "radiant_crisis_001";
    public static final String STYLE_RGX_11Z_PRO = "rgx_11z_pro";
    public static final String STYLE_RGX_11Z_PRO_V1 = "rgx_11z_pro_v1";
    public static final String STYLE_RGX_11Z_PRO_V2 = "rgx_11z_pro_v2";
    public static final String STYLE_RGX_11Z_PRO_V3 = "rgx_11z_pro_v3";
    public static final String TEXTURE_ICON = "icon";
    public static final String TEXTURE_FRAME = "frame";
    public static final String TEXTURE_RING = "ring";
    public static final String TEXTURE_BLADE = "blade";
    public static final String TEXTURE_BAR = "bar";
    public static final String TEXTURE_HEADSHOT = "headshot";
    public static final String TEXTURE_BASE_PARTICLE = "base_particle";
    public static final String TEXTURE_HERO_FLAME = "hero_flame";
    public static final String TEXTURE_LARGE_SPARKS = "large_sparks";
    public static final String TEXTURE_X_SPARKS = "x_sparks";
    private static final Map<String, StyleSpec> STYLE_SPECS = new LinkedHashMap<String, StyleSpec>();
    private static final Map<String, StyleSpec> PRESET_SPECS = new LinkedHashMap<String, StyleSpec>();

    private static void registerImported(String presetId, String skin, String name, int color) {
        ValorantStyleCatalog.register(ValorantStyleCatalog.style(presetId, "ke_" + skin, name, color, "ke_" + skin + "_kill_", 0, 0, false, null, "killicon_ke_" + skin + "_emblem.png", "killicon_ke_" + skin + "_frame.png", "killicon_ke_" + skin + "_bar.png"));
    }

    private ValorantStyleCatalog() {
    }

    private static StyleSpec style(String presetId, String styleId, String displayName, int accentColor, String comboSoundPrefix, int headshotOffsetX, int headshotOffsetY, boolean emblemOnlyEntryMotion, String frameBladeTexture, String emblemTexture, String frameTexture, String barTexture) {
        LinkedHashMap<String, String> textures = new LinkedHashMap<String, String>();
        textures.put(TEXTURE_ICON, emblemTexture);
        textures.put(TEXTURE_FRAME, frameTexture);
        textures.put(TEXTURE_RING, ValorantStyleCatalog.getDefaultRingTexture(styleId));
        if (frameBladeTexture != null && !frameBladeTexture.trim().isEmpty()) {
            textures.put(TEXTURE_BLADE, frameBladeTexture);
        }
        textures.put(TEXTURE_BAR, barTexture);
        textures.put(TEXTURE_HEADSHOT, "killicon_valorant_headshot.png");
        textures.put(TEXTURE_BASE_PARTICLE, "killicon_valorant_particle_base_t1.png");
        textures.put(TEXTURE_HERO_FLAME, "killicon_valorant_particle_hero_flame.png");
        textures.put(TEXTURE_LARGE_SPARKS, "killicon_valorant_particle_large_sparks.png");
        textures.put(TEXTURE_X_SPARKS, "killicon_valorant_particle_x_sparks.png");
        return new StyleSpec(presetId, styleId, displayName, accentColor, comboSoundPrefix, headshotOffsetX, headshotOffsetY, emblemOnlyEntryMotion, frameBladeTexture, Collections.unmodifiableMap(textures));
    }

    private static void register(StyleSpec spec) {
        STYLE_SPECS.put(spec.styleId(), spec);
        PRESET_SPECS.put(spec.presetId(), spec);
    }

    public static StyleSpec getStyleSpec(String styleId) {
        return STYLE_SPECS.get(ValorantStyleCatalog.normalizeStyleId(styleId));
    }

    public static StyleSpec getPresetSpec(String presetId) {
        return PRESET_SPECS.get(presetId);
    }

    public static List<StyleSpec> getOfficialPresetSpecs() {
        return new ArrayList<StyleSpec>(PRESET_SPECS.values());
    }

    public static List<StyleSpec> getDefinitions() {
        return ValorantStyleCatalog.getOfficialPresetSpecs();
    }

    public static String getDefaultStyleForPreset(String presetId) {
        StyleSpec spec = ValorantStyleCatalog.getPresetSpec(presetId);
        return spec != null ? spec.styleId() : STYLE_PRIME;
    }

    public static int getHeadshotOffsetX(String styleId) {
        return 0;
    }

    public static int getHeadshotOffsetY(String styleId) {
        return 0;
    }

    public static String getFrameBladeTextureFileName(String styleId) {
        if (!ValorantStyleCatalog.usesBlade(styleId)) {
            return null;
        }
        StyleSpec spec = ValorantStyleCatalog.getStyleSpec(styleId);
        if (spec == null) {
            return null;
        }
        String texture = spec.texture(TEXTURE_BLADE);
        return texture != null ? texture : spec.frameBladeTexture();
    }

    public static boolean isBubblegumDeathwishStyle(String styleId) {
        return STYLE_BUBBLEGUM_DEATHWISH.equals(styleId) || STYLE_BUBBLEGUM_DEATHWISH_V1.equals(styleId) || STYLE_BUBBLEGUM_DEATHWISH_V2.equals(styleId) || STYLE_BUBBLEGUM_DEATHWISH_V3.equals(styleId);
    }

    public static boolean usesBlade(String styleId) {
        return ValorantStyleCatalog.isBubblegumDeathwishStyle(styleId);
    }

    public static boolean useEmblemOnlyEntryMotion(String styleId) {
        StyleSpec spec = ValorantStyleCatalog.getStyleSpec(styleId);
        return spec != null && spec.emblemOnlyEntryMotion();
    }

    public static String normalizeStyleId(String styleId) {
        String normalized;
        if (styleId == null || styleId.trim().isEmpty()) {
            return STYLE_PRIME;
        }
        switch (normalized = styleId.toLowerCase(Locale.ROOT)) {
            case "prime": {
                return STYLE_PRIME;
            }
            case "glitchpop": {
                return STYLE_GLITCHPOP;
            }
            case "singularity": 
            case "singularity_v1": {
                return STYLE_SINGULARITY_V1;
            }
            case "singularity_v2": {
                return STYLE_SINGULARITY_V2;
            }
            case "singularity_v3": {
                return STYLE_SINGULARITY_V3;
            }
            case "gaia": 
            case "gaia_vengeance": {
                return STYLE_GAIA;
            }
            case "gaia_v1": 
            case "gaia_vengeance_v1": {
                return STYLE_GAIA_V1;
            }
            case "gaia_v2": 
            case "gaia_vengeance_v2": {
                return STYLE_GAIA_V2;
            }
            case "gaia_v3": 
            case "gaia_vengeance_v3": {
                return STYLE_GAIA_V3;
            }
        }
        return STYLE_SPECS.containsKey(normalized) ? normalized : STYLE_PRIME;
    }

    public static String resolveStyleId(String presetId, JsonObject config2) {
        return ValorantStyleCatalog.getDefaultStyleForPreset(presetId);
    }

    public static String getOfficialTextureFileName(String presetId, String textureKey) {
        StyleSpec spec = ValorantStyleCatalog.getPresetSpec(presetId);
        if (spec == null) {
            spec = ValorantStyleCatalog.getPresetSpec("00009");
        }
        return spec != null ? spec.texture(ValorantStyleCatalog.normalizeTextureKey(textureKey)) : null;
    }

    public static String getOfficialTextureFileNameForStyle(String styleId, String textureKey) {
        StyleSpec spec = ValorantStyleCatalog.getStyleSpec(styleId);
        if (spec == null) {
            spec = ValorantStyleCatalog.getStyleSpec(STYLE_PRIME);
        }
        return spec != null ? spec.texture(ValorantStyleCatalog.normalizeTextureKey(textureKey)) : null;
    }

    public static String getComboSoundBaseName(String presetId, JsonObject config2, int comboTier) {
        StyleSpec spec = ValorantStyleCatalog.getStyleSpec(ValorantStyleCatalog.resolveStyleId(presetId, config2));
        String style = spec != null ? spec.styleId() : "";
        boolean sixTiers = "ke_cyrax".equals(style) || "ke_reaver".equals(style) || "ke_forsaken".equals(style) || "ke_mystbloom".equals(style) || "ke_ayakashi".equals(style);
        int tier = Math.max(1, Math.min(comboTier, sixTiers ? 6 : 5));
        String prefix = spec != null ? spec.comboSoundPrefix() : "valorant_primekill_";
        return prefix + tier;
    }

    public static String getDefaultParticleColorHex(String presetId, JsonObject config2) {
        StyleSpec spec = ValorantStyleCatalog.getStyleSpec(ValorantStyleCatalog.resolveStyleId(presetId, config2));
        int color = spec != null ? spec.accentColor() : 9473229;
        return String.format("#%06X", color & 0xFFFFFF);
    }

    private static String getDefaultRingTexture(String styleId) {
        String normalized;
        switch (normalized = ValorantStyleCatalog.normalizeStyleId(styleId)) {
            case "bubblegum_deathwish": 
            case "bubblegum_deathwish_v1": 
            case "bubblegum_deathwish_v2": 
            case "bubblegum_deathwish_v3": {
                return "killicon_valorant_bubblegum_deathwish_ring.png";
            }
            case "glitchpop": {
                return "killicon_valorant_glitchpop_ring.png";
            }
            case "rgx_11z_pro": 
            case "rgx_11z_pro_v1": 
            case "rgx_11z_pro_v2": 
            case "rgx_11z_pro_v3": {
                return "killicon_valorant_rgx_11z_pro_ring.png";
            }
        }
        return "killicon_valorant_base_ring.png";
    }

    private static String normalizeTextureKey(String textureKey) {
        if ("emblem".equals(textureKey)) {
            return TEXTURE_ICON;
        }
        return textureKey;
    }

    static {
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00009", STYLE_PRIME, "Prime", 9473229, "valorant_primekill_", 0, -16, false, null, "killicon_valorant_prime_emblem.png", "killicon_valorant_prime_frame.png", "killicon_valorant_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00010", STYLE_GLITCHPOP, "Glitchpop", 2529269, "valorant_glitchpopkill_", 0, -16, false, null, "killicon_valorant_glitchpop_emblem.png", "killicon_valorant_glitchpop_frame.png", "killicon_valorant_glitchpop_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00011", STYLE_SINGULARITY_V1, "Singularity V1", 14646857, "valorant_singularitykill_", 0, -10, false, null, "killicon_valorant_singularity_v1_emblem.png", "killicon_valorant_base_frame.png", "killicon_valorant_singularity_v1_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00012", STYLE_SINGULARITY_V2, "Singularity V2", 14469489, "valorant_singularitykill_", 0, -10, false, null, "killicon_valorant_singularity_v2_emblem.png", "killicon_valorant_base_frame.png", "killicon_valorant_singularity_v2_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00013", STYLE_SINGULARITY_V3, "Singularity V3", 8298204, "valorant_singularitykill_", 0, -10, false, null, "killicon_valorant_singularity_v3_emblem.png", "killicon_valorant_base_frame.png", "killicon_valorant_singularity_v3_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00014", STYLE_GAIA, "Gaia's Vengeance", 16340062, "valorant_gaiavengeancekill_", -2, -20, false, null, "killicon_valorant_gaia_emblem.png", "killicon_valorant_gaia_frame.png", "killicon_valorant_gaia_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00015", STYLE_GAIA_V1, "Gaia's Vengeance V1", 2653939, "valorant_gaiavengeancekill_", -2, -20, false, null, "killicon_valorant_gaia_v1_emblem.png", "killicon_valorant_gaia_v1_frame.png", "killicon_valorant_gaia_v1_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00016", STYLE_GAIA_V2, "Gaia's Vengeance V2", 2602824, "valorant_gaiavengeancekill_", -2, -20, false, null, "killicon_valorant_gaia_v2_emblem.png", "killicon_valorant_gaia_v2_frame.png", "killicon_valorant_gaia_v2_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00017", STYLE_GAIA_V3, "Gaia's Vengeance V3", 16216356, "valorant_gaiavengeancekill_", -2, -20, false, null, "killicon_valorant_gaia_v3_emblem.png", "killicon_valorant_gaia_v3_frame.png", "killicon_valorant_gaia_v3_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00018", STYLE_BUBBLEGUM_DEATHWISH, "Bubblegum Deathwish", 13193145, "valorant_bubblegumdeathwishkill_", 0, -12, true, "killicon_valorant_bubblegum_deathwish_blade.png", "killicon_valorant_bubblegum_deathwish_emblem.png", "killicon_valorant_bubblegum_deathwish_frame.png", "killicon_valorant_bubblegum_deathwish_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00019", STYLE_BUBBLEGUM_DEATHWISH_V1, "Bubblegum Deathwish V1", 13209164, "valorant_bubblegumdeathwishkill_", 0, -12, true, "killicon_valorant_bubblegum_deathwish_blade.png", "killicon_valorant_bubblegum_deathwish_v3_emblem.png", "killicon_valorant_bubblegum_deathwish_frame.png", "killicon_valorant_bubblegum_deathwish_v1_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00020", STYLE_BUBBLEGUM_DEATHWISH_V2, "Bubblegum Deathwish V2", 10302255, "valorant_bubblegumdeathwishkill_", 0, -12, true, "killicon_valorant_bubblegum_deathwish_blade.png", "killicon_valorant_bubblegum_deathwish_v2_emblem.png", "killicon_valorant_bubblegum_deathwish_frame.png", "killicon_valorant_bubblegum_deathwish_v2_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00021", STYLE_BUBBLEGUM_DEATHWISH_V3, "Bubblegum Deathwish V3", 7254071, "valorant_bubblegumdeathwishkill_", 0, -12, true, "killicon_valorant_bubblegum_deathwish_blade.png", "killicon_valorant_bubblegum_deathwish_v1_emblem.png", "killicon_valorant_bubblegum_deathwish_frame.png", "killicon_valorant_bubblegum_deathwish_v3_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00022", STYLE_CHAMPIONS_2021, "Champions 2021", 9728070, "valorant_champions2021kill_", 0, -12, false, null, "killicon_valorant_champions_2021_emblem.png", "killicon_valorant_base_frame.png", "killicon_valorant_champions_2021_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00023", STYLE_PRELUDE_TO_CHAOS_V1, "Prelude to Chaos V1", 16019031, "valorant_preludetochaoskill_", 0, -12, false, null, "killicon_valorant_prelude_to_chaos_v1_emblem.png", "killicon_valorant_base_frame.png", "killicon_valorant_prelude_to_chaos_v1_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00024", STYLE_PRELUDE_TO_CHAOS_V2, "Prelude to Chaos V2", 0x10C110, "valorant_preludetochaoskill_", 0, -12, false, null, "killicon_valorant_prelude_to_chaos_v2_emblem.png", "killicon_valorant_base_frame.png", "killicon_valorant_prelude_to_chaos_v2_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00025", STYLE_PRELUDE_TO_CHAOS_V3, "Prelude to Chaos V3", 1140929, "valorant_preludetochaoskill_", 0, -12, false, null, "killicon_valorant_prelude_to_chaos_v3_emblem.png", "killicon_valorant_base_frame.png", "killicon_valorant_prelude_to_chaos_v3_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00026", STYLE_PRIMORDIUM, "Primordium", 9387569, "valorant_primordiumkill_", 0, -14, false, null, "killicon_valorant_primordium_emblem.png", "killicon_valorant_primordium_frame.png", "killicon_valorant_primordium_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00027", STYLE_PRIMORDIUM_V1, "Primordium V1", 3701329, "valorant_primordiumkill_", 0, -14, false, null, "killicon_valorant_primordium_v1_emblem.png", "killicon_valorant_primordium_frame.png", "killicon_valorant_primordium_v1_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00028", STYLE_PRIMORDIUM_V2, "Primordium V2", 3238020, "valorant_primordiumkill_", 0, -14, false, null, "killicon_valorant_primordium_v2_emblem.png", "killicon_valorant_primordium_frame.png", "killicon_valorant_primordium_v2_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00029", STYLE_PRIMORDIUM_V3, "Primordium V3", 9269059, "valorant_primordiumkill_", 0, -14, false, null, "killicon_valorant_primordium_v3_emblem.png", "killicon_valorant_primordium_frame.png", "killicon_valorant_primordium_v3_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00030", STYLE_RADIANT_CRISIS_001, "Radiant Crisis 001", 7585988, "valorant_radiantcrisis001kill_", 0, -12, false, null, "killicon_valorant_radiant_crisis_001_emblem.png", "killicon_valorant_base_frame.png", "killicon_valorant_radiant_crisis_001_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00031", STYLE_RGX_11Z_PRO, "RGX 11z Pro", 12710721, "valorant_rgx11zprokill_", 0, -12, false, null, "killicon_valorant_rgx_11z_pro_emblem.png", "killicon_valorant_rgx_11z_pro_frame.png", "killicon_valorant_rgx_11z_pro_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00032", STYLE_RGX_11Z_PRO_V1, "RGX 11z Pro V1", 15941962, "valorant_rgx11zprokill_", 0, -12, false, null, "killicon_valorant_rgx_11z_pro_v1_emblem.png", "killicon_valorant_rgx_11z_pro_frame.png", "killicon_valorant_rgx_11z_pro_v1_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00033", STYLE_RGX_11Z_PRO_V2, "RGX 11z Pro V2", 4307699, "valorant_rgx11zprokill_", 0, -12, false, null, "killicon_valorant_rgx_11z_pro_v2_emblem.png", "killicon_valorant_rgx_11z_pro_frame.png", "killicon_valorant_rgx_11z_pro_v2_bar.png"));
        ValorantStyleCatalog.register(ValorantStyleCatalog.style("00034", STYLE_RGX_11Z_PRO_V3, "RGX 11z Pro V3", 15968065, "valorant_rgx11zprokill_", 0, -12, false, null, "killicon_valorant_rgx_11z_pro_v3_emblem.png", "killicon_valorant_rgx_11z_pro_frame.png", "killicon_valorant_rgx_11z_pro_v3_bar.png"));
        ValorantStyleCatalog.registerImported("00038", "cyrax", "CYRAX", 15397368);
        ValorantStyleCatalog.registerImported("00039", "reaver", "Reaver", 10904575);
        ValorantStyleCatalog.registerImported("00040", "forsaken", "Forsaken", 2418593);
        ValorantStyleCatalog.registerImported("00041", "mystbloom", "Mystbloom", 16421306);
        ValorantStyleCatalog.registerImported("00042", "ayakashi", "Ayakashi", 13832754);
    }

    public static final class StyleSpec {
        private final String presetId;
        private final String styleId;
        private final String displayName;
        private final int accentColor;
        private final String comboSoundPrefix;
        private final int headshotOffsetX;
        private final int headshotOffsetY;
        private final boolean emblemOnlyEntryMotion;
        private final String frameBladeTexture;
        private final Map<String, String> textures;

        public StyleSpec(String presetId, String styleId, String displayName, int accentColor, String comboSoundPrefix, int headshotOffsetX, int headshotOffsetY, boolean emblemOnlyEntryMotion, String frameBladeTexture, Map<String, String> textures) {
            this.presetId = presetId;
            this.styleId = styleId;
            this.displayName = displayName;
            this.accentColor = accentColor;
            this.comboSoundPrefix = comboSoundPrefix;
            this.headshotOffsetX = headshotOffsetX;
            this.headshotOffsetY = headshotOffsetY;
            this.emblemOnlyEntryMotion = emblemOnlyEntryMotion;
            this.frameBladeTexture = frameBladeTexture;
            this.textures = textures;
        }

        public String presetId() {
            return this.presetId;
        }

        public String styleId() {
            return this.styleId;
        }

        public String displayName() {
            return this.displayName;
        }

        public int accentColor() {
            return this.accentColor;
        }

        public String comboSoundPrefix() {
            return this.comboSoundPrefix;
        }

        public int headshotOffsetX() {
            return this.headshotOffsetX;
        }

        public int headshotOffsetY() {
            return this.headshotOffsetY;
        }

        public boolean emblemOnlyEntryMotion() {
            return this.emblemOnlyEntryMotion;
        }

        public String frameBladeTexture() {
            return this.frameBladeTexture;
        }

        public Map<String, String> textures() {
            return this.textures;
        }

        public String texture(String textureKey) {
            return this.textures.get(textureKey);
        }
    }
}

