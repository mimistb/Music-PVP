/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.compat;

public final class TextColor {
    private final int value;

    private TextColor(int v) {
        this.value = v;
    }

    public static TextColor fromRgb(int v) {
        return new TextColor(v);
    }

    public int getValue() {
        return this.value;
    }
}

