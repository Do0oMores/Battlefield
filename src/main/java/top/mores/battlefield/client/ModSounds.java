package top.mores.battlefield.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import top.mores.battlefield.Battlefield;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Battlefield.MODID);

    public static final RegistryObject<SoundEvent> VOICE_MOBILIZE =
            SOUND_EVENTS.register("voice.mobilize",
                    () -> SoundEvent.createVariableRangeEvent(id("voice.mobilize")));

    public static final RegistryObject<SoundEvent> VOICE_POINT_LOST =
            SOUND_EVENTS.register("voice.point_lost",
                    () -> SoundEvent.createVariableRangeEvent(id("voice.point_lost")));

    public static final RegistryObject<SoundEvent> VOICE_POINT_CAPTURED =
            SOUND_EVENTS.register("voice.point_captured",
                    () -> SoundEvent.createVariableRangeEvent(id("voice.point_captured")));

    public static final RegistryObject<SoundEvent> VOICE_SECTOR_PUSH =
            SOUND_EVENTS.register("voice.sector_push",
                    () -> SoundEvent.createVariableRangeEvent(id("voice.sector_push")));

    public static final RegistryObject<SoundEvent> VOICE_VICTORY =
            SOUND_EVENTS.register("voice.victory",
                    () -> SoundEvent.createVariableRangeEvent(id("voice.victory")));

    public static final RegistryObject<SoundEvent> VOICE_DEFEAT =
            SOUND_EVENTS.register("voice.defeat",
                    () -> SoundEvent.createVariableRangeEvent(id("voice.defeat")));

    private static ResourceLocation id(String path) {
        return new ResourceLocation(Battlefield.MODID, path);
    }

    private ModSounds() {}
}
