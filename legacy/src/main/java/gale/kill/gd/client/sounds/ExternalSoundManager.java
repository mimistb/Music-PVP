/*
 * Decompiled with CFR 0.0.9 (FabricMC cc05e23f).
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.audio.ISound
 *  net.minecraft.client.audio.ISound$AttenuationType
 *  net.minecraft.client.audio.PositionedSound
 *  net.minecraft.util.ResourceLocation
 */
package gale.kill.gd.client.sounds;

import gale.kill.gd.GdKillEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSound;
import net.minecraft.util.ResourceLocation;

public final class ExternalSoundManager {
    public static final String SLOT_BF1_DEFAULT = "bf1_default";
    public static final String SLOT_BF1_HEADSHOT = "bf1_headshot";
    public static boolean enabled = true;
    public static float volume = 0.7f;

    public static void playConfiguredSound(String preset, String slot) {
        GdKillEffect.queueSound(slot);
    }

    public static ISound play(String name) {
        if (!enabled || volume <= 0.0f || name == null || name.isEmpty()) {
            return null;
        }
        Cue cue = new Cue(new ResourceLocation("gd656killicon", name));
        Minecraft.getMinecraft().getSoundHandler().playSound((ISound)cue);
        return cue;
    }

    private static final class Cue
    extends PositionedSound {
        Cue(ResourceLocation r) {
            super(r);
            this.volume = volume;
            this.pitch = 1.0f;
            this.attenuationType = ISound.AttenuationType.NONE;
        }
    }
}

