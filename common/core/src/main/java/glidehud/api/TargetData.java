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
    /** Opaque handle for the tracked entity itself (an EntityPlayer on 1.8.9); may be null. */
    public final Object entity;
    /** Distance from the local player in blocks; refreshed on every {@code getTarget()} call. */
    public float distance;
    /** Absorption hearts (golden absorption), refreshed on every {@code getTarget()} call. */
    public float absorption;
    /** Maximum health of the target (normally 20), refreshed on every {@code getTarget()} call. */
    public float maxHealth = 20.0F;
    /** Tab-list latency in milliseconds; -1 when it is not known. */
    public int ping = -1;

    public TargetData(String name, float health, float armor, Object head) {
        this(name, health, armor, head, null);
    }

    public TargetData(String name, float health, float armor, Object head, Object entity) {
        this.name = name;
        this.health = health;
        this.armor = armor;
        this.head = head;
        this.entity = entity;
    }
}
