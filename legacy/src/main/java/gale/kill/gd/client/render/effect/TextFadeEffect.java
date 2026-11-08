/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.client.render.effect;

import gale.kill.gd.compat.Mth;

public final class TextFadeEffect {
    public static final float MIN_ALPHA = 0.1f;

    private TextFadeEffect() {
    }

    public static float fadeAlpha(float fadeProgress, boolean blink) {
        float alpha = blink ? (fadeProgress < 0.33333334f ? 1.0f - 2.4f * fadeProgress : (fadeProgress < 0.6666667f ? 2.4f * fadeProgress - 0.6f : 3.0f * (1.0f - fadeProgress))) : 1.0f - fadeProgress;
        return Math.max(0.1f, Mth.clamp(alpha, 0.0f, 1.0f));
    }
}

