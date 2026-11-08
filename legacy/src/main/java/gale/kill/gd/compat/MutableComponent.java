/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.compat;

import gale.kill.gd.compat.Component;
import gale.kill.gd.compat.Style;
import java.util.function.UnaryOperator;

public final class MutableComponent
extends Component {
    public MutableComponent(String s) {
        super(s);
    }

    public MutableComponent append(String s) {
        this.siblings.add(Component.literal(s));
        return this;
    }

    public MutableComponent append(Component c) {
        this.siblings.add(c);
        return this;
    }

    public MutableComponent setStyle(Style s) {
        this.style = s;
        return this;
    }

    @Override
    public MutableComponent withStyle(UnaryOperator<Style> f) {
        this.style = (Style)f.apply(this.style);
        return this;
    }

    public MutableComponent withStyle(Style s) {
        return this.setStyle(s);
    }
}

