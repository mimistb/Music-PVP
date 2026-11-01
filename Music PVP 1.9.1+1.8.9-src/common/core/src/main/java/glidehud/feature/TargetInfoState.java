package glidehud.feature;

import glidehud.api.TargetData;
import glidehud.api.TargetInfoProvider;
import glidehud.core.Anim;
import glidehud.core.Colors;
import glidehud.core.EaseBackIn;

/**
 * Headless target HUD state: the tracked player, smoothed health / armor values
 * and the appear / disappear animation. The host draws it with its own UI style.
 *
 * <p>Usage: call {@link #update()} once per frame, then read {@link #visible()},
 * {@link #name()}, {@link #healthRatio()} and {@link #introAlpha()}.</p>
 */
public final class TargetInfoState {

    /** Supplies the player under the crosshair. */
    public TargetInfoProvider provider;

    private final Anim healthAnimation = new Anim();
    private final Anim armorAnimation = new Anim();
    private final EaseBackIn introAnimation = new EaseBackIn(320, 1.0, 2.0F);

    private TargetData target;
    private String name;
    private Object head;

    public TargetInfoState(TargetInfoProvider provider) {
        this.provider = provider;
        this.introAnimation.setDirection(EaseBackIn.Direction.BACKWARDS);
    }

    /** Advances all animations and latches the last visible name / head. Call once per frame. */
    public void update() {
        TargetData current = this.provider != null ? this.provider.getTarget() : null;
        this.target = current;
        this.introAnimation.setDirection(current == null ? EaseBackIn.Direction.BACKWARDS
                : EaseBackIn.Direction.FORWARDS);

        if (current != null) {
            this.name = current.name;
            this.head = current.head;
            this.healthAnimation.set(Math.min(current.health, 20.0F), 16.0);
            this.armorAnimation.set(Math.min(current.armor, 20.0F), 16.0);
        }
    }

    /** Latest target, or {@code null} once the provider stops returning one. */
    public TargetData target() {
        return this.target;
    }

    public boolean hasTarget() {
        return this.target != null;
    }

    /** Last known name; kept while the panel animates out. */
    public String name() {
        return this.name;
    }

    /** Last known head texture object; kept while the panel animates out. */
    public Object head() {
        return this.head;
    }

    /** Smoothed health, 0..20. */
    public float health() {
        return this.healthAnimation.get();
    }

    /** Smoothed armor, 0..20. */
    public float armor() {
        return this.armorAnimation.get();
    }

    /** Smoothed health as 0..1 (for an arc / bar). */
    public float healthRatio() {
        return this.healthAnimation.get() / 20.0F;
    }

    /** Smoothed armor as 0..1 (for an arc / bar). */
    public float armorRatio() {
        return this.armorAnimation.get() / 20.0F;
    }

    /** Fade of the whole panel, 0..1. */
    public float introAlpha() {
        return Colors.clamp01(this.introAnimation.getValueFloat());
    }

    /**
     * Glide's appear animation: the panel is scaled from 2x down to 1x while it fades
     * in, and back up to 2x while it fades out.
     */
    public float introScale() {
        return 2.0F - this.introAnimation.getValueFloat();
    }

    /** True while the panel should be drawn (stays true during the fade-out). */
    public boolean visible() {
        return this.name != null && this.head != null && introAlpha() > 0.0F;
    }
}
