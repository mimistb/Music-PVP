package glidehud.feature;

import glidehud.api.Key;
import glidehud.api.KeyStateProvider;
import glidehud.core.Anim;

/**
 * Headless keystroke state: which keys are held and how far the highlight
 * animation has progressed. Nothing is drawn here, the host renders it with its
 * own UI style.
 *
 * <p>Usage: call {@link #update()} once per frame, then read
 * {@link #visibleKeys()} / {@link #label(Key)} / {@link #highlight(Key)}.</p>
 */
public final class KeystrokesState {

    /** Glide "Space" option: include the jump/space key. */
    public boolean showSpace = true;

    /** Glide "Unmarked" option: hide key labels. */
    public boolean unmarked = false;

    /** Supplies the bound key names and live key states. */
    public KeyStateProvider keys;

    private final Anim[] animations = new Anim[Key.values().length];

    public KeystrokesState(KeyStateProvider keys) {
        this.keys = keys;
        for (int i = 0; i < this.animations.length; i++) {
            this.animations[i] = new Anim();
        }
    }

    /** Advances all highlight animations. Call once per frame before reading state. */
    public void update() {
        boolean guiOpen = isGuiOpen();
        for (Key key : Key.values()) {
            boolean down = !guiOpen && this.keys != null && this.keys.isDown(key);
            this.animations[key.ordinal()].set(down ? 1.0F : 0.0F, 16.0);
        }
    }

    public boolean isGuiOpen() {
        return this.keys != null && this.keys.isGuiOpen();
    }

    /** True when the key is held and no GUI is open (Glide releases keys while a screen is open). */
    public boolean isDown(Key key) {
        return key != null && !isGuiOpen() && this.keys != null && this.keys.isDown(key);
    }

    /** 0..1 highlight animation value; Glide scales the key box by this amount. */
    public float highlight(Key key) {
        return key == null ? 0.0F : this.animations[key.ordinal()].get();
    }

    /** Bound key name, or {@code null} when labels are hidden or no provider is set. */
    public String label(Key key) {
        if (this.unmarked || key == null || this.keys == null) {
            return null;
        }
        return this.keys.keyName(key);
    }

    /** Whether the host should draw this key (the space bar can be hidden). */
    public boolean isVisible(Key key) {
        return key != null && (key != Key.JUMP || this.showSpace);
    }
}
