/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonArray
 *  com.google.gson.JsonObject
 *  com.google.gson.JsonParser
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.audio.ISound
 *  net.minecraft.client.renderer.GlStateManager
 *  net.minecraft.client.resources.IReloadableResourceManager
 *  org.lwjgl.BufferUtils
 *  org.lwjgl.opengl.GL11
 */
package gale.kill.gd;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gale.core.Scheduler;
import gale.event.impl.Render2DEvent;
import gale.kill.gd.client.config.ConfigManager;
import gale.kill.gd.client.config.ValorantStyleCatalog;
import gale.kill.gd.client.render.IHudRenderer;
import gale.kill.gd.client.render.impl.Battlefield1Renderer;
import gale.kill.gd.client.render.impl.BonusListRenderer;
import gale.kill.gd.client.render.impl.CardBarRenderer;
import gale.kill.gd.client.render.impl.CardRenderer;
import gale.kill.gd.client.render.impl.ComboIconRenderer;
import gale.kill.gd.client.render.impl.ComboSubtitleRenderer;
import gale.kill.gd.client.render.impl.ScoreSubtitleRenderer;
import gale.kill.gd.client.render.impl.ScrollingIconRenderer;
import gale.kill.gd.client.render.impl.SubtitleRenderer;
import gale.kill.gd.client.render.impl.ValorantIconRenderer;
import gale.kill.gd.client.sounds.ExternalSoundManager;
import gale.kill.gd.client.stats.ClientStatsManager;
import gale.kill.gd.client.textures.ExternalTextureManager;
import gale.kill.gd.compat.GuiGraphics;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.IReloadableResourceManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

public final class GdKillEffect {
    public static final String[] PRESET_NAMES;
    public static final String[] PRESET_IDS;
    private static boolean reloadHookInstalled;
    private final Map<String, IHudRenderer> renderers = new LinkedHashMap<String, IHudRenderer>();
    private final Minecraft mc = Minecraft.getMinecraft();
    private String preset = "00009";
    private ISound playing;
    private int pendingStage;
    private long pendingTick;
    private final IntBuffer savedClip = BufferUtils.createIntBuffer((int)16);

    public GdKillEffect() {
        if (!reloadHookInstalled && this.mc.getResourceManager() instanceof IReloadableResourceManager) {
            reloadHookInstalled = true;
            ((IReloadableResourceManager)this.mc.getResourceManager()).registerReloadListener(resources -> {
                ValorantIconRenderer.clearProcessedTextureCache();
                ExternalTextureManager.clearCache();
            });
        }
        this.renderers.put("kill_icon/valorant", new ValorantIconRenderer());
        this.renderers.put("kill_icon/scrolling", new ScrollingIconRenderer());
        this.renderers.put("kill_icon/combo", new ComboIconRenderer());
        this.renderers.put("kill_icon/card", new CardRenderer());
        this.renderers.put("kill_icon/card_bar", new CardBarRenderer());
        this.renderers.put("kill_icon/battlefield1", new Battlefield1Renderer());
        this.renderers.put("subtitle/kill_feed", new SubtitleRenderer());
        this.renderers.put("subtitle/combo", ComboSubtitleRenderer.getInstance());
        this.renderers.put("subtitle/bonus_list", BonusListRenderer.getInstance());
        this.renderers.put("subtitle/score", ScoreSubtitleRenderer.getInstance());
    }

    public void select(int index) {
        boolean changed;
        String id = PRESET_IDS[Math.max(0, Math.min(PRESET_IDS.length - 1, index))];
        boolean bl = changed = !id.equals(this.preset);
        if (changed) {
            this.clear();
            this.preset = id;
        }
        ConfigManager.select(this.preset);
        if (changed) {
            ValorantIconRenderer.prepareTextures(this.preset, ConfigManager.getElementConfig(this.preset, "kill_icon/valorant", true));
        }
    }

    public void trigger(int stage, String victim) {
        ConfigManager.select(this.preset);
        ++ClientStatsManager.kills;
        IHudRenderer.TriggerContext context = IHudRenderer.TriggerContext.of(0, -1, stage, victim == null ? "\u76ee\u6807" : victim);
        for (Map.Entry<String, IHudRenderer> entry : this.renderers.entrySet()) {
            JsonObject config2 = ConfigManager.getElementConfig(this.preset, entry.getKey(), true);
            if (config2 == null || config2.has("visible") && !config2.get("visible").getAsBoolean()) continue;
            if (entry.getKey().equals("subtitle/bonus_list")) {
                entry.getValue().trigger(IHudRenderer.TriggerContext.of(1, -1, stage, "5||" + victim));
                continue;
            }
            if (entry.getKey().equals("subtitle/score")) {
                ScoreSubtitleRenderer.getInstance().addScore(5.0f);
                continue;
            }
            entry.getValue().trigger(context);
        }
        this.pendingStage = stage;
        this.pendingTick = Scheduler.ticks();
    }

    public static void queueSound(String ignored) {
    }

    public void tick(boolean sound, float volume) {
        ExternalSoundManager.enabled = sound;
        ExternalSoundManager.volume = volume;
        if (!sound) {
            this.stopSound();
        }
        if (this.pendingStage != 0 && Scheduler.ticks() > this.pendingTick) {
            int stage = this.pendingStage;
            this.pendingStage = 0;
            this.stopSound();
            if (sound) {
                this.playing = ExternalSoundManager.play(GdKillEffect.soundName(this.preset, stage));
            }
        }
    }

    public static String soundName(String id, int stage) {
        ValorantStyleCatalog.StyleSpec spec = ValorantStyleCatalog.getPresetSpec(id);
        if (spec != null) {
            return ValorantStyleCatalog.getComboSoundBaseName(id, null, stage);
        }
        if (id.equals("00002")) {
            return "combokillsound_" + Math.max(1, Math.min(6, stage)) + "_cf";
        }
        if (id.equals("00003")) {
            return "cardkillsound_default_cs";
        }
        if (id.equals("00004")) {
            return "killsound_bf1";
        }
        if (id.equals("00007") || id.equals("00036")) {
            return "killsound_bf5";
        }
        if (id.equals("00037")) {
            return "killsound_bf6";
        }
        return "killsound_df";
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void render(Render2DEvent event, float x, float y, float scale) {
        if (this.mc.gameSettings.hideGUI) {
            return;
        }
        boolean depth = GL11.glIsEnabled((int)2929);
        boolean blend = GL11.glIsEnabled((int)3042);
        boolean alpha = GL11.glIsEnabled((int)3008);
        boolean cull = GL11.glIsEnabled((int)2884);
        boolean texture = GL11.glIsEnabled((int)3553);
        boolean write = GL11.glGetBoolean((int)2930);
        boolean scissor = GL11.glIsEnabled((int)3089);
        IntBuffer clip = this.savedClip;
        clip.clear();
        GL11.glGetInteger((int)3088, (IntBuffer)clip);
        int bound = GL11.glGetInteger((int)32873);
        int shade = GL11.glGetInteger((int)2900);
        int sr = GL11.glGetInteger((int)32969);
        int dr = GL11.glGetInteger((int)32968);
        int sa = GL11.glGetInteger((int)32971);
        int da = GL11.glGetInteger((int)32970);
        GlStateManager.pushMatrix();
        GlStateManager.translate((float)x, (float)y, (float)0.0f);
        GlStateManager.scale((float)scale, (float)scale, (float)1.0f);
        GlStateManager.translate((float)((float)(-event.width()) / 2.0f), (float)(-event.height() + 80), (float)0.0f);
        GlStateManager.disableDepth();
        GlStateManager.depthMask((boolean)false);
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate((int)770, (int)771, (int)1, (int)0);
        GL11.glShadeModel((int)7425);
        try {
            ConfigManager.select(this.preset);
            GuiGraphics graphics = new GuiGraphics(x - (float)event.width() / 2.0f * scale, y - (float)(event.height() - 80) * scale, scale);
            for (Map.Entry<String, IHudRenderer> entry : this.renderers.entrySet()) {
                JsonObject config2 = ConfigManager.getElementConfig(this.preset, entry.getKey(), true);
                if (config2 == null || config2.has("visible") && !config2.get("visible").getAsBoolean()) continue;
                entry.getValue().render(graphics, event.partialTicks);
            }
        }
        finally {
            GlStateManager.popMatrix();
            GlStateManager.color((float)1.0f, (float)1.0f, (float)1.0f, (float)1.0f);
            GL11.glShadeModel((int)shade);
            GlStateManager.depthMask((boolean)write);
            if (depth) {
                GlStateManager.enableDepth();
            } else {
                GlStateManager.disableDepth();
            }
            if (alpha) {
                GlStateManager.enableAlpha();
            } else {
                GlStateManager.disableAlpha();
            }
            if (cull) {
                GlStateManager.enableCull();
            } else {
                GlStateManager.disableCull();
            }
            if (texture) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
            GlStateManager.tryBlendFuncSeparate((int)sr, (int)dr, (int)sa, (int)da);
            if (blend) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
            GlStateManager.bindTexture((int)bound);
            GL11.glScissor((int)clip.get(0), (int)clip.get(1), (int)clip.get(2), (int)clip.get(3));
            if (scissor) {
                GL11.glEnable((int)3089);
            } else {
                GL11.glDisable((int)3089);
            }
        }
    }

    public void clear() {
        for (IHudRenderer renderer : this.renderers.values()) {
            renderer.resetPreview();
        }
        this.pendingStage = 0;
        ClientStatsManager.kills = 0;
        this.stopSound();
    }

    private void stopSound() {
        if (this.playing != null) {
            this.mc.getSoundHandler().stopSound(this.playing);
            this.playing = null;
        }
    }

    static {
        try (InputStream in = GdKillEffect.class.getResourceAsStream("/assets/gd656killicon/presets/index.json");){
            if (in == null) {
                throw new IOException("GD656 preset index missing");
            }
            JsonArray index = new JsonParser().parse((Reader)new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            PRESET_NAMES = new String[index.size()];
            PRESET_IDS = new String[index.size()];
            for (int i = 0; i < index.size(); ++i) {
                JsonObject entry = index.get(i).getAsJsonObject();
                GdKillEffect.PRESET_NAMES[i] = entry.get("name").getAsString();
                GdKillEffect.PRESET_IDS[i] = entry.get("id").getAsString();
            }
        }
        catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

