/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.renderer.texture.DynamicTexture
 *  net.minecraft.util.ResourceLocation
 */
package gale.kill.gd.client.render.effect;

import gale.kill.gd.client.textures.ExternalTextureManager;
import gale.kill.gd.compat.Mth;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

public final class IconGlowProcessor {
    private static final int MAX_ENTRIES = 48;
    private static final long MAX_PIXELS = 0x1000000L;
    private static long cachedPixels;
    private static final Map<CacheKey, CachedTexture> CACHE;
    private static final Set<CacheKey> FAILED;
    private static final Map<CacheKey, FutureTask<BufferedImage>> PENDING;
    private static final ThreadPoolExecutor WORKER;

    private IconGlowProcessor() {
    }

    public static void clearCache() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft != null) {
            for (CachedTexture cachedTexture : CACHE.values()) {
                minecraft.getTextureManager().deleteTexture(cachedTexture.location);
            }
        }
        CACHE.clear();
        FAILED.clear();
        cachedPixels = 0L;
        for (FutureTask futureTask : PENDING.values()) {
            futureTask.cancel(true);
        }
        PENDING.clear();
        WORKER.purge();
    }

    public static void prepare(String presetId, String path, boolean tint, int color, float brightness, float contrast) {
        if (path == null || path.isEmpty()) {
            return;
        }
        CacheKey key = new CacheKey(path, tint, color, brightness, contrast);
        if (CACHE.containsKey(key) || PENDING.containsKey(key) || FAILED.contains(key)) {
            return;
        }
        if (PENDING.size() >= 8) {
            Iterator<FutureTask<BufferedImage>> old = PENDING.values().iterator();
            while (old.hasNext() && PENDING.size() >= 8) {
                if (!old.next().isDone()) continue;
                old.remove();
            }
            if (PENDING.size() >= 8) {
                return;
            }
        }
        FutureTask<BufferedImage> task = new FutureTask<BufferedImage>(() -> {
            BufferedImage source = ExternalTextureManager.readTextureImage(presetId, path);
            if (source == null) {
                throw new IOException("Unsupported texture: " + path);
            }
            int width = source.getWidth();
            int height = source.getHeight();
            int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
            for (int i = 0; i < pixels.length; ++i) {
                if ((i & 0x3FFF) == 0 && Thread.currentThread().isInterrupted()) {
                    throw new CancellationException();
                }
                pixels[i] = IconGlowProcessor.processTexturePixel(pixels[i], tint, color, brightness, contrast);
            }
            BufferedImage processed = new BufferedImage(width, height, 2);
            processed.setRGB(0, 0, width, height, pixels, 0, width);
            return processed;
        });
        PENDING.put(key, task);
        try {
            WORKER.execute(task);
        }
        catch (RejectedExecutionException full) {
            PENDING.remove(key);
        }
    }

    public static ResourceLocation getOrCreateProcessedTexture(String presetId, String texturePath, boolean accentTintEnabled, int accentColor, float brightness, float contrast) {
        if (texturePath == null || texturePath.isEmpty()) {
            return null;
        }
        CacheKey key = new CacheKey(texturePath, accentTintEnabled, accentColor, brightness, contrast);
        CachedTexture cached = CACHE.get(key);
        if (cached != null) {
            return cached.location;
        }
        if (FAILED.contains(key)) {
            return null;
        }
        FutureTask<BufferedImage> task = PENDING.get(key);
        if (task == null) {
            IconGlowProcessor.prepare(presetId, texturePath, accentTintEnabled, accentColor, brightness, contrast);
            return null;
        }
        if (!task.isDone()) {
            return null;
        }
        PENDING.remove(key);
        try {
            BufferedImage processed = task.get();
            ResourceLocation location = IconGlowProcessor.registerBufferedTexture("processed", key.hashCode(), processed);
            long pixels = (long)processed.getWidth() * (long)processed.getHeight();
            CACHE.put(key, new CachedTexture(location, pixels));
            cachedPixels += pixels;
            Iterator<Map.Entry<CacheKey, CachedTexture>> entries = CACHE.entrySet().iterator();
            while (CACHE.size() > 1 && (CACHE.size() > 48 || cachedPixels > 0x1000000L)) {
                CachedTexture old = entries.next().getValue();
                entries.remove();
                cachedPixels -= old.pixels;
                Minecraft.getMinecraft().getTextureManager().deleteTexture(old.location);
            }
            return location;
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (CancellationException | ExecutionException failed) {
            if (FAILED.size() >= 48) {
                FAILED.remove(FAILED.iterator().next());
            }
            FAILED.add(key);
            return null;
        }
    }

    public static int applyBrightnessContrastToRgb(int rgb, float brightness, float contrast) {
        int red = IconGlowProcessor.applyBrightnessContrastToChannel(rgb >> 16 & 0xFF, brightness, contrast);
        int green = IconGlowProcessor.applyBrightnessContrastToChannel(rgb >> 8 & 0xFF, brightness, contrast);
        int blue = IconGlowProcessor.applyBrightnessContrastToChannel(rgb & 0xFF, brightness, contrast);
        return red << 16 | green << 8 | blue;
    }

    private static ResourceLocation registerBufferedTexture(String prefix, int hash, BufferedImage image) {
        DynamicTexture texture = new DynamicTexture(image);
        String dynamicName = "gd656killicon_" + prefix + "_" + Integer.toHexString(hash);
        return Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation(dynamicName, texture);
    }

    private static int processTexturePixel(int argb, boolean accentTintEnabled, int accentColor, float brightness, float contrast) {
        int alpha = argb >>> 24 & 0xFF;
        if (alpha <= 0) {
            return 0;
        }
        int red = argb >> 16 & 0xFF;
        int green = argb >> 8 & 0xFF;
        int blue = argb & 0xFF;
        if (accentTintEnabled && IconGlowProcessor.isAccentPixel(argb)) {
            float luminance = (float)Math.max(red, Math.max(green, blue)) / 255.0f;
            red = Math.round((float)(accentColor >> 16 & 0xFF) * luminance);
            green = Math.round((float)(accentColor >> 8 & 0xFF) * luminance);
            blue = Math.round((float)(accentColor & 0xFF) * luminance);
        }
        red = IconGlowProcessor.applyBrightnessContrastToChannel(red, brightness, contrast);
        green = IconGlowProcessor.applyBrightnessContrastToChannel(green, brightness, contrast);
        blue = IconGlowProcessor.applyBrightnessContrastToChannel(blue, brightness, contrast);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static boolean isAccentPixel(int argb) {
        int alpha = argb >>> 24 & 0xFF;
        if (alpha < 16) {
            return false;
        }
        int red = argb >> 16 & 0xFF;
        int green = argb >> 8 & 0xFF;
        int blue = argb & 0xFF;
        int maximum = Math.max(red, Math.max(green, blue));
        int minimum = Math.min(red, Math.min(green, blue));
        float saturation = maximum == 0 ? 0.0f : (float)(maximum - minimum) / (float)maximum;
        float brightness = (float)maximum / 255.0f;
        return brightness >= 0.18f && brightness <= 0.98f && saturation >= 0.18f;
    }

    private static int applyBrightnessContrastToChannel(int channel, float brightness, float contrast) {
        float normalized = (float)channel / 255.0f;
        float contrasted = (normalized - 0.5f) * contrast + 0.5f;
        float adjusted = contrasted * brightness;
        return Mth.clamp(Math.round(adjusted * 255.0f), 0, 255);
    }

    static {
        CACHE = new LinkedHashMap<CacheKey, CachedTexture>(16, 0.75f, true);
        FAILED = new LinkedHashSet<CacheKey>();
        PENDING = new LinkedHashMap<CacheKey, FutureTask<BufferedImage>>();
        WORKER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(8), task -> {
            Thread thread = new Thread(task, "Gale GD texture preparation");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    private static final class CacheKey {
        final String path;
        final boolean tint;
        final int color;
        final int brightness;
        final int contrast;
        final int hash;

        CacheKey(String path, boolean tint, int color, float brightness, float contrast) {
            this.path = path;
            this.tint = tint;
            this.color = tint ? color & 0xFFFFFF : 0;
            this.brightness = Math.round(brightness * 1000.0f);
            this.contrast = Math.round(contrast * 1000.0f);
            int h = path.hashCode();
            h = 31 * h + (tint ? 1 : 0);
            h = 31 * h + this.color;
            h = 31 * h + this.brightness;
            this.hash = 31 * h + this.contrast;
        }

        public int hashCode() {
            return this.hash;
        }

        public boolean equals(Object other) {
            if (!(other instanceof CacheKey)) {
                return false;
            }
            CacheKey k = (CacheKey)other;
            return this.tint == k.tint && this.color == k.color && this.brightness == k.brightness && this.contrast == k.contrast && this.path.equals(k.path);
        }
    }

    private static final class CachedTexture {
        final ResourceLocation location;
        final long pixels;

        CachedTexture(ResourceLocation location, long pixels) {
            this.location = location;
            this.pixels = pixels;
        }
    }
}

