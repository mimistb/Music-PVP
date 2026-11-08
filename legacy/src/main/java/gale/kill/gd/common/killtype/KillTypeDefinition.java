/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.common.killtype;

public final class KillTypeDefinition {
    private final String id;
    private final int type;
    private final String displayName;
    private final String formatKey;
    private final String placeholderColorKey;
    private final String emphasisColorKey;
    private final String enableKey;
    private final String textureKey;
    private final String bf1TextureKey;
    private final String scrollingSoundSlotId;
    private final String ringEnableKey;

    private KillTypeDefinition(Builder b) {
        this.id = b.id;
        this.type = b.type;
        this.displayName = b.displayName;
        this.formatKey = b.formatKey;
        this.placeholderColorKey = b.placeholderColorKey;
        this.emphasisColorKey = b.emphasisColorKey;
        this.enableKey = b.enableKey;
        this.textureKey = b.textureKey;
        this.bf1TextureKey = b.bf1TextureKey;
        this.scrollingSoundSlotId = b.scrollingSoundSlotId;
        this.ringEnableKey = b.ringEnableKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() {
        return this.id;
    }

    public int type() {
        return this.type;
    }

    public String displayName() {
        return this.displayName;
    }

    public String formatKey() {
        return this.formatKey;
    }

    public String placeholderColorKey() {
        return this.placeholderColorKey;
    }

    public String emphasisColorKey() {
        return this.emphasisColorKey;
    }

    public String enableKey() {
        return this.enableKey;
    }

    public String textureKey() {
        return this.textureKey;
    }

    public String bf1TextureKey() {
        return this.bf1TextureKey;
    }

    public String scrollingSoundSlotId() {
        return this.scrollingSoundSlotId;
    }

    public String ringEnableKey() {
        return this.ringEnableKey;
    }

    public static final class Builder {
        private String id;
        private int type = -1;
        private String displayName;
        private String formatKey;
        private String placeholderColorKey;
        private String emphasisColorKey;
        private String enableKey;
        private String textureKey;
        private String bf1TextureKey;
        private String scrollingSoundSlotId;
        private String ringEnableKey;

        public Builder id(String v) {
            this.id = v;
            return this;
        }

        public Builder type(int v) {
            this.type = v;
            return this;
        }

        public Builder displayName(String v) {
            this.displayName = v;
            return this;
        }

        public Builder formatKey(String v) {
            this.formatKey = v;
            return this;
        }

        public Builder placeholderColorKey(String v) {
            this.placeholderColorKey = v;
            return this;
        }

        public Builder emphasisColorKey(String v) {
            this.emphasisColorKey = v;
            return this;
        }

        public Builder enableKey(String v) {
            this.enableKey = v;
            return this;
        }

        public Builder textureKey(String v) {
            this.textureKey = v;
            return this;
        }

        public Builder bf1TextureKey(String v) {
            this.bf1TextureKey = v;
            return this;
        }

        public Builder scrollingSoundSlotId(String v) {
            this.scrollingSoundSlotId = v;
            return this;
        }

        public Builder ringEnableKey(String v) {
            this.ringEnableKey = v;
            return this;
        }

        public KillTypeDefinition build() {
            if (this.id == null || this.id.trim().isEmpty() || !this.id.equals(this.id.toUpperCase())) {
                throw new IllegalStateException("KillTypeDefinition: id must be non-blank uppercase, got: " + this.id);
            }
            if (this.type < 0) {
                throw new IllegalStateException("KillTypeDefinition[" + this.id + "]: type is required");
            }
            if (this.displayName == null || this.displayName.trim().isEmpty() || this.formatKey == null || this.placeholderColorKey == null || this.emphasisColorKey == null || this.textureKey == null || this.bf1TextureKey == null || this.scrollingSoundSlotId == null) {
                throw new IllegalStateException("KillTypeDefinition[" + this.id + "]: displayName/formatKey/colorKeys/textureKeys/soundSlotId are required");
            }
            return new KillTypeDefinition(this);
        }
    }
}

