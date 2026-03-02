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

    public static final RegistryObject<SoundEvent> VOICE_POINT_LOST =
            SOUND_EVENTS.register("voice.point_lost",
                    () -> SoundEvent.createVariableRangeEvent(id("voice.point_lost")));

    public static final RegistryObject<SoundEvent> VOICE_POINT_CAPTURED =
            SOUND_EVENTS.register("voice.point_captured",
                    () -> SoundEvent.createVariableRangeEvent(id("voice.point_captured")));

    public static final RegistryObject<SoundEvent> VOICE_RETURN_AREA =
            SOUND_EVENTS.register("voice.return_area",
                    () -> SoundEvent.createVariableRangeEvent(id("voice.return_area")));

    public static final RegistryObject<SoundEvent> VOICE_ATTACK_PUSH =
            SOUND_EVENTS.register("voice.attack_push",
                    () -> SoundEvent.createVariableRangeEvent(id("voice.attack_push")));

    public static final RegistryObject<SoundEvent> VOICE_DEFEND_PUSH =
            SOUND_EVENTS.register("voice.defend_push",
                    () -> SoundEvent.createVariableRangeEvent(id("voice.defend_push")));

    public static final RegistryObject<SoundEvent> VOICE_ATTACK_NEXT_SECTOR =
            SOUND_EVENTS.register("voice.attack_next_sector",
                    () -> SoundEvent.createVariableRangeEvent(id("voice.attack_next_sector")));

    public static final RegistryObject<SoundEvent> VOICE_FINAL_OBJECTIVE =
            SOUND_EVENTS.register("voice.final_objective",
                    () -> SoundEvent.createVariableRangeEvent(id("voice.final_objective")));

    public static final RegistryObject<SoundEvent> VOICE_HALF_WAY =
            SOUND_EVENTS.register("voice.half_way",
                    () -> SoundEvent.createVariableRangeEvent(id("voice.half_way")));

    private static ResourceLocation id(String path) {
        return new ResourceLocation(Battlefield.MODID, path);
    }

    private ModSounds() {
    }
}
