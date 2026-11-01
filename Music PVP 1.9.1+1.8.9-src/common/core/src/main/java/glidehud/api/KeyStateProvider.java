package glidehud.api;

/** Supplies key state and key names for the keystrokes module. */
public interface KeyStateProvider {

    /** True when any GUI screen is open; Glide treats all keys as released then. */
    boolean isGuiOpen();

    boolean isDown(Key key);

    /** Display name of the bound key, e.g. "W" or "SPACE". */
    String keyName(Key key);
}
