/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.client.render;

public final class PreviewRenderTimeContext {
    private static final ThreadLocal<Boolean> IN_PREVIEW = ThreadLocal.withInitial(() -> false);
    private static volatile boolean paused = false;
    private static volatile long frozenTime = -1L;
    private static volatile long lastPreviewTime = -1L;

    private PreviewRenderTimeContext() {
    }

    public static void beginPreviewFrame() {
        IN_PREVIEW.set(true);
    }

    public static void endPreviewFrame() {
        IN_PREVIEW.set(false);
    }

    public static long currentTimeMillis() {
        long now;
        if (!IN_PREVIEW.get().booleanValue()) {
            return System.currentTimeMillis();
        }
        if (paused) {
            if (frozenTime < 0L) {
                frozenTime = lastPreviewTime > 0L ? lastPreviewTime : System.currentTimeMillis();
            }
            return frozenTime;
        }
        lastPreviewTime = now = System.currentTimeMillis();
        return now;
    }

    public static void setPaused(boolean value) {
        if (value) {
            if (!paused) {
                frozenTime = lastPreviewTime > 0L ? lastPreviewTime : System.currentTimeMillis();
            }
            paused = true;
            return;
        }
        paused = false;
        frozenTime = -1L;
    }
}

