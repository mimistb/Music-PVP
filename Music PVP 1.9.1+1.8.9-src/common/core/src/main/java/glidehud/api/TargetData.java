package glidehud.api;

/** Immutable snapshot of the current target for the target HUD module. */
public final class TargetData {

    public final String name;
    /** Health in half-hearts (0..20), clamped by the module. */
    public final float health;
    /** Armor points (0..20), clamped by the module. */
    public final float armor;
    /** Opaque handle for {@link Render2D#playerHead}; a skin ResourceLocation on 1.8.9. */
    public final Object head;

    public TargetData(String name, float health, float armor, Object head) {
        this.name = name;
        this.health = health;
        this.armor = armor;
        this.head = head;
    }
}
