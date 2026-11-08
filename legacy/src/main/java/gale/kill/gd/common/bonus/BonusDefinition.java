/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.common.bonus;

import gale.kill.gd.common.bonus.FactorType;
import gale.kill.gd.common.bonus.MergeBehavior;
import gale.kill.gd.common.bonus.StatCategory;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BonusDefinition {
    private final String id;
    private final int type;
    private final String scoreExpression;
    private final FactorType factor;
    private final String displayName;
    private final String description;
    private final float scoreCap;
    private final MergeBehavior mergeBehavior;
    private final boolean priorityKill;
    private final int iconKillType;
    private final String killFeedPayload;
    private final StatCategory statCategory;
    private final boolean disabledByDefault;
    private final Map<Integer, String> streakSubtitles;

    private BonusDefinition(Builder b) {
        this.id = b.id;
        this.type = b.type;
        this.scoreExpression = b.scoreExpression;
        this.factor = b.factor;
        this.displayName = b.displayName;
        this.description = b.description;
        this.scoreCap = b.scoreCap;
        this.mergeBehavior = b.mergeBehavior;
        this.priorityKill = b.priorityKill;
        this.iconKillType = b.iconKillType;
        this.killFeedPayload = b.killFeedPayload;
        this.statCategory = b.statCategory;
        this.disabledByDefault = b.disabledByDefault;
        this.streakSubtitles = b.streakSubtitles;
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

    public String scoreExpression() {
        return this.scoreExpression;
    }

    public FactorType factor() {
        return this.factor;
    }

    public String displayName() {
        return this.displayName;
    }

    public String description() {
        return this.description;
    }

    public String formatConfigKey() {
        return "format_" + this.id.toLowerCase();
    }

    public float scoreCap() {
        return this.scoreCap;
    }

    public MergeBehavior mergeBehavior() {
        return this.mergeBehavior;
    }

    public boolean priorityKill() {
        return this.priorityKill;
    }

    public int iconKillType() {
        return this.iconKillType;
    }

    public String killFeedPayload() {
        return this.killFeedPayload;
    }

    public StatCategory statCategory() {
        return this.statCategory;
    }

    public boolean disabledByDefault() {
        return this.disabledByDefault;
    }

    public String streakSubtitle(int combo) {
        return this.streakSubtitles.get(combo);
    }

    public static final class Builder {
        private String id;
        private int type = -1;
        private String scoreExpression = "0";
        private FactorType factor = FactorType.NONE;
        private String displayName;
        private String description;
        private float scoreCap = -1.0f;
        private MergeBehavior mergeBehavior = MergeBehavior.BY_TYPE_EXTRA;
        private boolean priorityKill = false;
        private int iconKillType = -1;
        private String killFeedPayload;
        private StatCategory statCategory = StatCategory.NONE;
        private boolean disabledByDefault = false;
        private final Map<Integer, String> streakSubtitles = new LinkedHashMap<Integer, String>();

        public Builder id(String v) {
            this.id = v;
            return this;
        }

        public Builder type(int v) {
            this.type = v;
            return this;
        }

        public Builder scoreExpression(String v) {
            this.scoreExpression = v;
            return this;
        }

        public Builder factor(FactorType v) {
            this.factor = v;
            return this;
        }

        public Builder displayName(String v) {
            this.displayName = v;
            return this;
        }

        public Builder description(String v) {
            this.description = v;
            return this;
        }

        public Builder scoreCap(float v) {
            this.scoreCap = v;
            return this;
        }

        public Builder mergeBehavior(MergeBehavior v) {
            this.mergeBehavior = v;
            return this;
        }

        public Builder priorityKill(boolean v) {
            this.priorityKill = v;
            return this;
        }

        public Builder iconKillType(int v) {
            this.iconKillType = v;
            return this;
        }

        public Builder killFeedPayload(String v) {
            this.killFeedPayload = v;
            return this;
        }

        public Builder statCategory(StatCategory v) {
            this.statCategory = v;
            return this;
        }

        public Builder disabledByDefault(boolean v) {
            this.disabledByDefault = v;
            return this;
        }

        public Builder streakSubtitle(int combo, String langKey) {
            this.streakSubtitles.put(combo, langKey);
            return this;
        }

        public BonusDefinition build() {
            if (this.id == null || this.id.trim().isEmpty()) {
                throw new IllegalStateException("BonusDefinition: id is required");
            }
            if (!this.id.equals(this.id.toUpperCase())) {
                throw new IllegalStateException("BonusDefinition: id must be uppercase, got: " + this.id);
            }
            if (this.type < 0) {
                throw new IllegalStateException("BonusDefinition[" + this.id + "]: type is required (>= 0)");
            }
            if (this.displayName == null || this.displayName.trim().isEmpty()) {
                throw new IllegalStateException("BonusDefinition[" + this.id + "]: displayName is required");
            }
            return new BonusDefinition(this);
        }
    }
}

