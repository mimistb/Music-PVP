package glidehud.core;

/**
 * Two-color accent palette entry. {@link #DEFAULT} is Glide's "Default" accent:
 * #AAFFA9 -> #11FFBD.
 */
public final class Accent {

    public static final Accent DEFAULT = new Accent("Default", Colors.rgb(170, 255, 169), Colors.rgb(17, 255, 189));

    public final String name;
    public final int color1;
    public final int color2;

    public Accent(String name, int color1, int color2) {
        this.name = name;
        this.color1 = color1;
        this.color2 = color2;
    }

    /** Current cycling interpolated color (opaque). */
    public int interpolate() {
        return interpolate(0);
    }

    /**
     * Same ping-pong hue cycle Glide uses: 15ms per step, 180ms per half cycle.
     * {@code index} is a per-element phase offset (Glide passes the text alpha here).
     */
    public int interpolate(int index) {
        long angle = (System.currentTimeMillis() / 15L + (long) index) % 360L;
        if (angle < 0L) {
            angle += 360L;
        }
        int a = (int) angle;
        a = (a >= 180 ? 360 - a : a) * 2;
        return Colors.lerpHue(color1, color2, a / 360.0F);
    }
}
