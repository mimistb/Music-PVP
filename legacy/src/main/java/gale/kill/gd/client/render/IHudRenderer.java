/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 */
package gale.kill.gd.client.render;

import gale.kill.gd.compat.GuiGraphics;

public interface IHudRenderer {
    public void render(GuiGraphics var1, float var2);

    public void trigger(TriggerContext var1);

    default public void resetPreview() {
    }

    public static final class TriggerContext {
        private final int type;
        private final int entityId;
        private final int comboCount;
        private final String extraData;
        private final float distance;
        private final float bonusMultiplier;
        private final float bonusScale;

        public TriggerContext(int type, int entityId, int comboCount, String extraData, float distance, float bonusMultiplier, float bonusScale) {
            this.type = type;
            this.entityId = entityId;
            this.comboCount = comboCount;
            this.extraData = extraData;
            this.distance = distance;
            this.bonusMultiplier = bonusMultiplier;
            this.bonusScale = bonusScale;
        }

        public int type() {
            return this.type;
        }

        public int entityId() {
            return this.entityId;
        }

        public int comboCount() {
            return this.comboCount;
        }

        public String extraData() {
            return this.extraData;
        }

        public float distance() {
            return this.distance;
        }

        public float bonusMultiplier() {
            return this.bonusMultiplier;
        }

        public float bonusScale() {
            return this.bonusScale;
        }

        public static TriggerContext of(int type, int entityId) {
            return new TriggerContext(type, entityId, 0, "", 0.0f, 0.0f, 0.0f);
        }

        public static TriggerContext of(int type, int entityId, int comboCount) {
            return new TriggerContext(type, entityId, comboCount, "", 0.0f, 0.0f, 0.0f);
        }

        public static TriggerContext of(int type, int entityId, int comboCount, String extraData) {
            return new TriggerContext(type, entityId, comboCount, extraData, 0.0f, 0.0f, 0.0f);
        }

        public static TriggerContext of(int type, int entityId, int comboCount, String extraData, float distance) {
            return new TriggerContext(type, entityId, comboCount, extraData, distance, 0.0f, 0.0f);
        }

        public static TriggerContext of(int type, String extraData) {
            return new TriggerContext(type, -1, 0, extraData, 0.0f, 0.0f, 0.0f);
        }
    }
}

