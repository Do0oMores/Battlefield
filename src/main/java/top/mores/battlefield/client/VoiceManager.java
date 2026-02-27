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

        mc.execute(() -> {
            // 用 VOICE 通道播放，而不是 UI
            var inst = new net.minecraft.client.resources.sounds.SimpleSoundInstance(
                    se.getLocation(),
                    net.minecraft.sounds.SoundSource.VOICE,
                    1.0f,  // volume
                    1.0f,  // pitch
                    net.minecraft.util.RandomSource.create(),
                    false, // looping
                    0,     // delay
                    net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE,
                    0.0, 0.0, 0.0,
                    true
            );
            mc.getSoundManager().play(inst);
        });
    }

    private VoiceManager() {}
}
