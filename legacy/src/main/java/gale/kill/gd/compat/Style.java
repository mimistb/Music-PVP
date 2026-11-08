/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.compat;

import gale.kill.gd.compat.TextColor;

public final class Style {
    public static final Style EMPTY = new Style(null, false);
    private final TextColor color;
    private final boolean bold;

    private Style(TextColor c, boolean b) {
        this.color = c;
        this.bold = b;
    }

    public TextColor getColor() {
        return this.color;
    }

    public Style withColor(TextColor c) {
        return new Style(c, this.bold);
    }

    public Style withColor(int c) {
        return this.withColor(TextColor.fromRgb(c));
    }

    public Style withBold(boolean b) {
        return new Style(this.color, b);
    }

    public boolean isBold() {
        return this.bold;
    }
}

