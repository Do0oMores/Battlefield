package top.mores.battlefield;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import top.mores.battlefield.server.entity.BombEntity;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Battlefield.MODID);

    public static final RegistryObject<EntityType<BombEntity>> BOMB =
            ENTITIES.register("bomb",
                    () -> EntityType.Builder.<BombEntity>of(BombEntity::new, MobCategory.MISC)
                            .sized(0.25f, 0.25f)
                            .clientTrackingRange(128)
                            .updateInterval(1)
                            .build(new ResourceLocation(Battlefield.MODID, "bomb").toString()));

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
    }

    private ModEntities() {}
}
