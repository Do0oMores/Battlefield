package top.mores.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;

public final class VoiceManager {

    private static long lastPlayMs = 0;
    private static SoundEvent lastSound = null;

    public static void play(SoundEvent se) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        long now = System.currentTimeMillis();
        if (se == lastSound && now - lastPlayMs < 1200) return;
        lastSound = se;
        lastPlayMs = now;

        mc.execute(() -> mc.getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(se, 1.0f)
        ));
    }

    private VoiceManager() {
    }
}
