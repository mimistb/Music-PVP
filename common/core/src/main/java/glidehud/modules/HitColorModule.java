package glidehud.modules;

import glidehud.core.Accent;
import glidehud.core.Colors;

/**
 * Port of Glide's HitColorMod: recolors the red damage flash that is rendered over
 * hurt entities.
 *
 * <p>Glide injects at the head of
 * {@code RendererLivingEntity.setBrightness} (1.8.9: {@code func_177092_a}) and
 * replaces the four constants of the hurt-flash branch:</p>
 *
 * <pre>
 * GlStateManager.color(1.0F, 0.0F, 0.0F, 0.3F);
 *                     ^red   ^green ^blue  ^alpha   (original)
 * </pre>
 *
 * <p>Host integration (Mixin style, 1.8.9 SRG names):</p>
 *
 * <pre>
 * &#64;Inject(method = "func_177092_a", at = &#64;At("HEAD"))
 * private void glide$applyHitColor(EntityLivingBase entity, float partialTicks,
 *         boolean combineTextures, CallbackInfoReturnable&lt;Boolean&gt; cir) {
 *     float[] rgba = hitColorModule.rgba();   // call update() once per frame
 *     this.red = rgba[0]; this.green = rgba[1]; this.blue = rgba[2]; this.alpha = rgba[3];
 * }
 * &#64;ModifyConstant(method = "func_177092_a", constant = &#64;Constant(floatValue = 1.0F, ordinal = 0))
 * private float glide$red(float original) { return this.red; }   // 0.0F ord 0/1 = green/blue, 0.3F = alpha
 * </pre>
 *
 * <p>When disabled the values are the vanilla 1, 0, 0, 0.3 so the hurt flash is
 * untouched.</p>
 */
public final class HitColorModule {

    /** Master switch. */
    public boolean enabled = false;

    /** Use {@link #color} instead of the current accent color. */
    public boolean customColor = false;

    /** Flash color (alpha ignored, {@link #alpha} is used). */
    public int color = Colors.argb(255, 255, 0, 0);

    /** Flash opacity 0..1, Glide's default setting is 0.45. */
    public float alpha = 0.45F;

    public Accent accent = Accent.DEFAULT;

    private final float[] rgba = {1.0F, 0.0F, 0.0F, 0.3F};

    /** Recomputes {@link #rgba()}; call once per frame, or right before the hook reads it. */
    public void update() {
        if (!this.enabled) {
            this.rgba[0] = 1.0F;
            this.rgba[1] = 0.0F;
            this.rgba[2] = 0.0F;
            this.rgba[3] = 0.3F;
            return;
        }
        int value = this.customColor ? this.color : this.accent.interpolate();
        this.rgba[0] = Colors.red(value) / 255.0F;
        this.rgba[1] = Colors.green(value) / 255.0F;
        this.rgba[2] = Colors.blue(value) / 255.0F;
        this.rgba[3] = Colors.clamp01(this.alpha);
    }

    /** Live {red, green, blue, alpha} in 0..1, consumed by the renderer hook. */
    public float[] rgba() {
        return this.rgba;
    }

    /** Copies the current values into {@code out} and returns it. */
    public float[] rgba(float[] out) {
        System.arraycopy(this.rgba, 0, out, 0, 4);
        return out;
    }
}
