package glidehud.api;

/** Supplies the entity currently under the crosshair, or {@code null}. */
public interface TargetInfoProvider {

    TargetData getTarget();

    /** Optional: called once per tick by the host, if the provider needs polling. */
    default void update() {
    }
}
