/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 */
package gale.kill.gd.client.render.effect;

import com.google.gson.JsonObject;

public final class ValorantMotion {
    private final float entryY;
    private final float entryDuration;
    private final float bounceDistance;
    private final float bounceStart;
    private final float bouncePeak;
    private final float bounceHold;
    private final float bounceEnd;
    private final float spinStart;
    private final float spin180;
    private final float spin360;
    private final float angle180;
    private final float angle360;
    private final float fade;
    private final float fiveFade;
    private final float frameDelay;
    private final float frameDuration;
    private final float frameInitial;
    private final float emblemDuration;
    private final float emblemInitial;
    private final long duration;
    private final boolean foxFan;
    private final float emblemPeak;
    private final float emblemPeakTime;
    private final float barDelay;
    private final float barOrientationScale;
    private final float barSixOrientationScale;
    private final float barSixRadiusOffset;
    private final float barSixOrientationOrigin;
    private static final float[][] FOX_FAN = new float[][]{{0.0f}, {-28.0f, 28.0f}, {0.0f, -55.0f, 55.0f}, {-28.0f, 28.0f, -80.0f, 80.0f}, {0.0f, -40.0f, 40.0f, -80.0f, 80.0f}, {-55.0f, 55.0f, -90.0f, 90.0f, -125.0f, 125.0f}};

    public ValorantMotion(JsonObject c) {
        this.entryY = ValorantMotion.number(c, "icon_entry_offset_y", -16.0f);
        this.entryDuration = ValorantMotion.millis(c, "icon_entry_duration", 0.1f);
        this.bounceDistance = ValorantMotion.number(c, "bar_bounce_distance", 9.0f);
        this.bounceStart = ValorantMotion.millis(c, "bar_bounce_start", 0.02f);
        this.bouncePeak = Math.max(this.bounceStart + 1.0f, ValorantMotion.millis(c, "bar_bounce_peak", 0.18f));
        this.bounceHold = Math.max(this.bouncePeak, ValorantMotion.millis(c, "bar_bounce_hold", 0.27f));
        this.bounceEnd = Math.max(this.bounceHold + 1.0f, ValorantMotion.millis(c, "bar_bounce_end", 0.62f));
        this.spinStart = ValorantMotion.millis(c, "bar_spin_start", 0.75f);
        this.spin180 = Math.max(1.0f, ValorantMotion.millis(c, "bar_spin_duration", 0.7f));
        this.spin360 = Math.max(1.0f, ValorantMotion.millis(c, "bar_spin_duration_five", 1.0f));
        this.angle180 = ValorantMotion.number(c, "bar_spin_degrees", 180.0f);
        this.angle360 = ValorantMotion.number(c, "bar_spin_degrees_five", 360.0f);
        this.fade = Math.max(1.0f, ValorantMotion.millis(c, "icon_fade_duration", 0.22f));
        this.fiveFade = ValorantMotion.millis(c, "five_plus_fade_in_duration", 0.3f);
        this.duration = Math.max(300, Math.round(ValorantMotion.millis(c, "icon_display_duration", 2.6f)));
        this.frameDelay = ValorantMotion.millis(c, "frame_entry_delay", 0.0f);
        this.frameDuration = ValorantMotion.millis(c, "frame_entry_duration", 0.0f);
        this.frameInitial = Math.max(0.05f, ValorantMotion.number(c, "frame_entry_initial_scale", 1.0f));
        this.emblemDuration = ValorantMotion.millis(c, "emblem_entry_duration", 0.0f);
        this.emblemInitial = Math.max(0.05f, ValorantMotion.number(c, "emblem_entry_initial_scale", 1.0f));
        this.emblemPeak = Math.max(0.05f, ValorantMotion.number(c, "emblem_entry_peak_scale", this.emblemInitial));
        this.emblemPeakTime = Math.min(Math.max(0.0f, this.emblemDuration - 1.0f), ValorantMotion.millis(c, "emblem_entry_peak_time", 0.0f));
        this.barDelay = ValorantMotion.millis(c, "bar_entry_delay", 0.0f);
        this.barOrientationScale = ValorantMotion.number(c, "bar_orientation_scale", 1.0f);
        this.barSixOrientationScale = ValorantMotion.number(c, "bar_six_orientation_scale", this.barOrientationScale);
        this.barSixRadiusOffset = ValorantMotion.number(c, "bar_six_radius_offset", Float.NaN);
        this.barSixOrientationOrigin = ValorantMotion.number(c, "bar_six_orientation_origin", 0.0f);
        this.foxFan = c != null && c.has("bar_layout") && "fox_fan".equals(c.get("bar_layout").getAsString());
    }

    public long duration() {
        return this.duration;
    }

    public float entryY(long elapsed) {
        return this.entryDuration <= 0.0f || (float)elapsed >= this.entryDuration ? 0.0f : this.entryY * (1.0f - ValorantMotion.cubic((float)elapsed / this.entryDuration));
    }

    public float alpha(long elapsed, long totalDuration, int combo) {
        float alpha;
        float f = alpha = combo >= 5 && this.fiveFade > 0.0f ? ValorantMotion.quad((float)elapsed / this.fiveFade) : 1.0f;
        if ((float)totalDuration > this.fade && (float)elapsed > (float)totalDuration - this.fade) {
            alpha *= 1.0f - ValorantMotion.clamp(((float)elapsed - ((float)totalDuration - this.fade)) / this.fade);
        }
        return alpha;
    }

    public float barTravel(long elapsed) {
        if ((float)elapsed < this.bounceStart || (float)elapsed >= this.bounceEnd) {
            return 0.0f;
        }
        if ((float)elapsed <= this.bouncePeak) {
            float p = ((float)elapsed - this.bounceStart) / (this.bouncePeak - this.bounceStart);
            return this.bounceDistance * (ValorantMotion.quad(p) * 0.8f + ValorantMotion.cubic(p) * 0.2f);
        }
        if ((float)elapsed <= this.bounceHold) {
            return this.bounceDistance;
        }
        float p = ValorantMotion.clamp(((float)elapsed - this.bounceHold) / (this.bounceEnd - this.bounceHold));
        return this.bounceDistance * (1.0f - p * p * p * p);
    }

    public float barRotation(long elapsed, int combo, float direction) {
        if (combo <= 1 || (float)elapsed <= this.spinStart) {
            return 0.0f;
        }
        return (combo >= 5 ? this.angle360 : this.angle180) * direction * ValorantMotion.cubic(((float)elapsed - this.spinStart) / (combo >= 5 ? this.spin360 : this.spin180));
    }

    public float frameAlpha(long elapsed) {
        if ((float)elapsed < this.frameDelay) {
            return 0.0f;
        }
        return this.frameDuration <= 0.0f ? 1.0f : ValorantMotion.cubic(((float)elapsed - this.frameDelay) / this.frameDuration);
    }

    public float frameScale(long elapsed) {
        float p = this.frameDuration <= 0.0f ? 1.0f : ValorantMotion.cubic(((float)elapsed - this.frameDelay) / this.frameDuration);
        return this.frameInitial + (1.0f - this.frameInitial) * p;
    }

    public float emblemScale(long elapsed) {
        if (this.emblemPeakTime > 0.0f) {
            if ((float)elapsed <= this.emblemPeakTime) {
                return this.emblemInitial + (this.emblemPeak - this.emblemInitial) * ValorantMotion.cubic((float)elapsed / this.emblemPeakTime);
            }
            return this.emblemPeak + (1.0f - this.emblemPeak) * ValorantMotion.cubic(((float)elapsed - this.emblemPeakTime) / (this.emblemDuration - this.emblemPeakTime));
        }
        float p = this.emblemDuration <= 0.0f ? 1.0f : ValorantMotion.cubic((float)elapsed / this.emblemDuration);
        return this.emblemInitial + (1.0f - this.emblemInitial) * p;
    }

    public boolean usesFoxFan() {
        return this.foxFan;
    }

    public float barOrientationOffset(float layoutAngle) {
        return this.barOrientationOffset(layoutAngle, 1);
    }

    public float barOrientationOffset(float layoutAngle, int combo) {
        float scale = this.foxFan && combo >= 6 ? this.barSixOrientationScale : this.barOrientationScale;
        float origin = this.foxFan && combo >= 6 ? Math.signum(layoutAngle) * this.barSixOrientationOrigin : 0.0f;
        return (layoutAngle - origin) * scale - layoutAngle;
    }

    public float barRadiusOffset(int combo, float fallback) {
        return this.foxFan && combo >= 6 && !Float.isNaN(this.barSixRadiusOffset) ? this.barSixRadiusOffset : fallback;
    }

    public float[] barLayout(int combo, float[] fallback) {
        return this.foxFan ? FOX_FAN[Math.max(0, Math.min(5, combo - 1))] : fallback;
    }

    public long barElapsed(long elapsed) {
        return Math.max(0L, elapsed - (long)Math.round(this.barDelay));
    }

    public float barAlpha(long elapsed) {
        return this.barDelay <= 0.0f ? 1.0f : ValorantMotion.cubic(((float)elapsed - this.barDelay) / 100.0f);
    }

    private static float number(JsonObject c, String key, float fallback) {
        return c != null && c.has(key) ? c.get(key).getAsFloat() : fallback;
    }

    private static float millis(JsonObject c, String key, float fallback) {
        return Math.max(0, Math.round(ValorantMotion.number(c, key, fallback) * 1000.0f));
    }

    private static float clamp(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    private static float cubic(float p) {
        float v = 1.0f - ValorantMotion.clamp(p);
        return 1.0f - v * v * v;
    }

    private static float quad(float p) {
        float v = 1.0f - ValorantMotion.clamp(p);
        return 1.0f - v * v;
    }
}

