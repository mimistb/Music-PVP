/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.client.resources.I18n
 */
package gale.kill.gd.compat;

import gale.kill.gd.compat.MutableComponent;
import gale.kill.gd.compat.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import net.minecraft.client.resources.I18n;

public class Component {
    protected String text;
    protected Style style = Style.EMPTY;
    protected final List<Component> siblings = new ArrayList<Component>();

    protected Component(String s) {
        this.text = s;
    }

    public static MutableComponent literal(String s) {
        return new MutableComponent(s);
    }

    public static MutableComponent empty() {
        return Component.literal("");
    }

    public static MutableComponent translatable(String key, Object ... args) {
        return Component.literal(I18n.format((String)key, (Object[])args));
    }

    public String getString() {
        StringBuilder s = new StringBuilder(this.text);
        for (Component c : this.siblings) {
            s.append(c.getString());
        }
        return s.toString();
    }

    public List<Component> getSiblings() {
        return this.siblings;
    }

    public Style getStyle() {
        return this.style;
    }

    public MutableComponent copy() {
        MutableComponent c = new MutableComponent(this.text);
        c.style = this.style;
        for (Component s : this.siblings) {
            c.siblings.add(s.copy());
        }
        return c;
    }

    public String formatted() {
        StringBuilder s = new StringBuilder(this.style.isBold() ? "\u00a7l" : "").append(this.text);
        for (Component c : this.siblings) {
            s.append(c.formatted());
        }
        return s.toString();
    }

    public MutableComponent withStyle(UnaryOperator<Style> f) {
        MutableComponent c = this.copy();
        c.style = (Style)f.apply(this.style);
        return c;
    }
}

