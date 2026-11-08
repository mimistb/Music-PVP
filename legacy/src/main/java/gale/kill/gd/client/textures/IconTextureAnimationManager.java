/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 */
package gale.kill.gd.client.textures;

import com.google.gson.JsonObject;
import gale.kill.gd.client.render.PreviewRenderTimeContext;
import gale.kill.gd.client.textures.ExternalTextureManager;
import gale.kill.gd.compat.Mth;
import java.util.Random;

public class IconTextureAnimationManager {
    private static final Random RANDOM = new Random();

    public static String resolveTexturePath(String path) {
        return path;
    }

    public static TextureFrame getTextureFrame(String presetId, String elementId, String textureKey, String textureFileName, long startTime, JsonObject config2) {
        int frameH;
        int frameW;
        ExternalTextureManager.TextureDimensions dims = ExternalTextureManager.getTextureDimensions(presetId, textureFileName);
        int totalW = dims.width > 0 ? dims.width : 64;
        int totalH = dims.height > 0 ? dims.height : 64;
        String prefix = "anim_" + textureKey + "_";
        boolean enabled = IconTextureAnimationManager.getBoolean(config2, prefix + "enable_texture_animation", false);
        if (!enabled) {
            return new TextureFrame(0, 0, totalW, totalH, totalW, totalH);
        }
        int totalFrames = Math.max(1, IconTextureAnimationManager.getInt(config2, prefix + "texture_animation_total_frames", 1));
        String orientation = IconTextureAnimationManager.getString(config2, prefix + "texture_animation_orientation", "vertical");
        int interval = Math.max(1, IconTextureAnimationManager.getInt(config2, prefix + "texture_animation_interval_ms", 100));
        boolean loop = IconTextureAnimationManager.getBoolean(config2, prefix + "texture_animation_loop", false);
        String style = IconTextureAnimationManager.getString(config2, prefix + "texture_animation_play_style", "sequential");
        if ("horizontal".equalsIgnoreCase(orientation)) {
            frameW = totalW / totalFrames;
            frameH = totalH;
        } else {
            frameW = totalW;
            frameH = totalH / totalFrames;
        }
        long elapsed = PreviewRenderTimeContext.currentTimeMillis() - startTime;
        if (elapsed < 0L) {
            elapsed = 0L;
        }
        int frameIndex = 0;
        long currentTick = elapsed / (long)interval;
        if ("random".equalsIgnoreCase(style)) {
            long seed = startTime ^ currentTick * -7046029254386353131L;
            seed = seed * 31L + (long)(textureKey != null ? textureKey.hashCode() : 0);
            seed = seed * 31L + (long)(elementId != null ? elementId.hashCode() : 0);
            Random random = new Random(seed);
            frameIndex = random.nextInt(totalFrames);
        } else if ("reverse".equalsIgnoreCase(style)) {
            frameIndex = loop ? totalFrames - 1 - (int)(currentTick % (long)totalFrames) : totalFrames - 1 - (int)Math.min(currentTick, (long)(totalFrames - 1));
        } else if ("pingpong".equalsIgnoreCase(style) || "ping-pong".equalsIgnoreCase(style) || "ping_pong".equalsIgnoreCase(style) || "round_trip".equalsIgnoreCase(style)) {
            if (totalFrames > 1) {
                long effectiveTick;
                int cyclePos;
                int cyclePos2;
                int cycleLen = (totalFrames - 1) * 2;
                frameIndex = loop ? ((cyclePos2 = (int)(currentTick % (long)cycleLen)) < totalFrames ? cyclePos2 : cycleLen - cyclePos2) : ((cyclePos = (int)(effectiveTick = Math.min(currentTick, (long)cycleLen))) < totalFrames ? cyclePos : cycleLen - cyclePos);
            } else {
                frameIndex = 0;
            }
        } else {
            frameIndex = loop ? (int)(currentTick % (long)totalFrames) : (int)Math.min(currentTick, (long)(totalFrames - 1));
        }
        frameIndex = Mth.clamp(frameIndex, 0, totalFrames - 1);
        int u = 0;
        int v = 0;
        if ("horizontal".equalsIgnoreCase(orientation)) {
            u = frameIndex * frameW;
        } else {
            v = frameIndex * frameH;
        }
        return new TextureFrame(u, v, frameW, frameH, totalW, totalH);
    }

    private static boolean getBoolean(JsonObject config2, String key, boolean def) {
        if (config2 == null || !config2.has(key)) {
            return def;
        }
        return config2.get(key).getAsBoolean();
    }

    private static int getInt(JsonObject config2, String key, int def) {
        if (config2 == null || !config2.has(key)) {
            return def;
        }
        return config2.get(key).getAsInt();
    }

    private static String getString(JsonObject config2, String key, String def) {
        if (config2 == null || !config2.has(key)) {
            return def;
        }
        return config2.get(key).getAsString();
    }

    public static class TextureFrame {
        public final int u;
        public final int v;
        public final int width;
        public final int height;
        public final int totalWidth;
        public final int totalHeight;

        public TextureFrame(int u, int v, int width, int height, int totalWidth, int totalHeight) {
            this.u = u;
            this.v = v;
            this.width = width;
            this.height = height;
            this.totalWidth = totalWidth;
            this.totalHeight = totalHeight;
        }
    }
}

