package glidehud.core;

/**
 * ARGB color helpers. Ported from Glide's ColorUtils / MathUtils so the interpolated
 * accent colors match the original client exactly.
 */
public final class Colors {

    private Colors() {
    }

    public static int argb(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static int rgb(int r, int g, int b) {
        return argb(255, r, g, b);
    }

    public static int alpha(int color) {
        return (color >>> 24) & 0xFF;
    }

    public static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    public static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    public static int blue(int color) {
        return color & 0xFF;
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    public static int mulAlpha(int color, float factor) {
        return withAlpha(color, (int) (alpha(color) * clamp01(factor)));
    }

    /** Straight per-channel ARGB interpolation (used for the animated gradients). */
    public static int lerp(int color1, int color2, float amount) {
        amount = clamp01(amount);
        int a = (int) (alpha(color1) + (alpha(color2) - alpha(color1)) * amount);
        int r = (int) (red(color1) + (red(color2) - red(color1)) * amount);
        int g = (int) (green(color1) + (green(color2) - green(color1)) * amount);
        int b = (int) (blue(color1) + (blue(color2) - blue(color1)) * amount);
        return argb(a, r, g, b);
    }

    /** HSB interpolation, identical to Glide ColorUtils.interpolateColorHue. */
    public static int lerpHue(int color1, int color2, float amount) {
        amount = clamp01(amount);
        float[] hsb1 = java.awt.Color.RGBtoHSB(red(color1), green(color1), blue(color1), null);
        float[] hsb2 = java.awt.Color.RGBtoHSB(red(color2), green(color2), blue(color2), null);
        float h = interpolateFloat(hsb1[0], hsb2[0], amount);
        float s = interpolateFloat(hsb1[1], hsb2[1], amount);
        float b = interpolateFloat(hsb1[2], hsb2[2], amount);
        int rgb = java.awt.Color.HSBtoRGB(h, s, b) & 0xFFFFFF;
        int a = interpolateInt(alpha(color1), alpha(color2), amount);
        return (a << 24) | rgb;
    }

    public static float clamp01(float value) {
        return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    public static double interpolate(double oldValue, double newValue, double amount) {
        return oldValue + (newValue - oldValue) * amount;
    }

    public static float interpolateFloat(float oldValue, float newValue, double amount) {
        return (float) interpolate(oldValue, newValue, amount);
    }

    public static int interpolateInt(int oldValue, int newValue, double amount) {
        return (int) interpolate(oldValue, newValue, amount);
    }
}
