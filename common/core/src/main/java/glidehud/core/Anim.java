package glidehud.core;

/**
 * Port of Glide's SimpleAnimation (the easing used by keystrokes / target HUD).
 * Values are advanced once per call, based on wall clock time.
 */
public final class Anim {

    private float value;
    private long lastMs;

    public Anim() {
        this(0.0F);
    }

    public Anim(float initialValue) {
        this.value = initialValue;
        this.lastMs = System.currentTimeMillis();
    }

    /**
     * @param target target value
     * @param speed  animation speed, Glide caps it at 28
     */
    public void set(float target, double speed) {
        long now = System.currentTimeMillis();
        long delta = now - this.lastMs;
        this.lastMs = now;

        if (speed > 28.0) {
            speed = 28.0;
        }
        double deltaValue = 0.0;
        if (speed != 0.0) {
            deltaValue = (Math.abs(target - this.value) * 0.35F) / (10.0 / speed);
        }
        this.value = compensate(target, this.value, deltaValue, delta);
    }

    /** Exact port of Glide AnimationUtils.calculateCompensation. */
    private static float compensate(float target, float current, double speed, long delta) {
        float diff = current - target;
        double add = delta * (speed / 50.0);
        if (diff > speed) {
            current = current - add > target ? (float) (current - add) : target;
        } else if (diff < -speed) {
            current = current + add < target ? (float) (current + add) : target;
        } else if (Math.abs(current - target) < 0.03) {
            current = target;
        }
        return current;
    }

    public float get() {
        return this.value;
    }

    public void setValue(float value) {
        this.value = value;
    }

    public void reset() {
        this.value = 0.0F;
        this.lastMs = System.currentTimeMillis();
    }
}
