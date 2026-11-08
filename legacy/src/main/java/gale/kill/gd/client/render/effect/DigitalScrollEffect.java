/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.client.render.effect;

import gale.kill.gd.compat.Mth;

public class DigitalScrollEffect {
    private float animationDuration = 1.25f;
    private float animationRefreshRate = 0.01f;
    private Easing easing = Easing.QUINTIC_OUT;
    private float startValue = 0.0f;
    private float targetValue = 0.0f;
    private float currentValue = 0.0f;
    private long startTime = -1L;
    private long lastUpdateTime = -1L;
    private boolean isAnimating = false;

    public DigitalScrollEffect() {
    }

    public DigitalScrollEffect(float animationDuration, float animationRefreshRate, Easing easing) {
        this.animationDuration = animationDuration;
        this.animationRefreshRate = animationRefreshRate;
        this.easing = easing;
    }

    public void setAnimationDuration(float duration) {
        if (duration > 0.0f) {
            this.animationDuration = duration;
        }
    }

    public void setAnimationRefreshRate(float refreshRate) {
        if (refreshRate > 0.0f) {
            this.animationRefreshRate = refreshRate;
        }
    }

    public void setEasing(Easing easing) {
        this.easing = easing;
    }

    public float getAnimationDuration() {
        return this.animationDuration;
    }

    public float getAnimationRefreshRate() {
        return this.animationRefreshRate;
    }

    public Easing getEasing() {
        return this.easing;
    }

    public void startAnimation(float targetValue) {
        this.startAnimation(this.currentValue, targetValue);
    }

    public void startAnimation(float startValue, float targetValue) {
        this.startValue = startValue;
        this.targetValue = targetValue;
        this.currentValue = startValue;
        this.lastUpdateTime = this.startTime = System.currentTimeMillis();
        this.isAnimating = true;
    }

    public void stopAnimation() {
        if (this.isAnimating) {
            this.currentValue = this.targetValue;
            this.isAnimating = false;
            this.startTime = -1L;
            this.lastUpdateTime = -1L;
        }
    }

    public boolean isAnimating() {
        return this.isAnimating;
    }

    public float getCurrentValue() {
        return this.currentValue;
    }

    public float getTargetValue() {
        return this.targetValue;
    }

    public void setTargetValue(float targetValue) {
        this.targetValue = targetValue;
        if (!this.isAnimating) {
            this.startAnimation(this.currentValue, targetValue);
        }
    }

    public float getStartValue() {
        return this.startValue;
    }

    public float update(long currentTime) {
        if (!this.isAnimating || this.startTime < 0L || this.animationDuration <= 0.0f) {
            return this.currentValue;
        }
        if (this.lastUpdateTime > 0L && (float)(currentTime - this.lastUpdateTime) < this.animationRefreshRate * 1000.0f) {
            return this.currentValue;
        }
        float elapsedSeconds = (float)(currentTime - this.startTime) / 1000.0f;
        float progress = Math.min(elapsedSeconds / this.animationDuration, 1.0f);
        float easedProgress = this.applyEasing(progress);
        this.currentValue = Mth.lerp(easedProgress, this.startValue, this.targetValue);
        if (progress >= 1.0f) {
            this.currentValue = this.targetValue;
            this.isAnimating = false;
        }
        this.lastUpdateTime = currentTime;
        return this.currentValue;
    }

    public float update() {
        return this.update(System.currentTimeMillis());
    }

    public int getRoundedValue() {
        return Math.round(this.currentValue);
    }

    private float applyEasing(float progress) {
        if (progress <= 0.0f) {
            return 0.0f;
        }
        if (progress >= 1.0f) {
            return 1.0f;
        }
        switch (this.easing) {
            case CUBIC_OUT: {
                float t = 1.0f - progress;
                return 1.0f - t * t * t;
            }
            case LINEAR: {
                return progress;
            }
        }
        float t2 = 1.0f - progress;
        return 1.0f - t2 * t2 * t2 * t2 * t2;
    }

    public void reset() {
        this.startValue = 0.0f;
        this.targetValue = 0.0f;
        this.currentValue = 0.0f;
        this.startTime = -1L;
        this.lastUpdateTime = -1L;
        this.isAnimating = false;
    }

    public static enum Easing {
        QUINTIC_OUT,
        CUBIC_OUT,
        LINEAR;

    }
}

