package top.mores.battlefield.server.entity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import top.mores.battlefield.Battlefield;

public class V1Entity {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Battlefield.MODID);

    public static final RegistryObject<EntityType<V1MissileEntity>> V1_MISSILE =
            ENTITY_TYPES.register("v1_missile", () ->
                    EntityType.Builder.<V1MissileEntity>of(V1MissileEntity::new, MobCategory.MISC)
                            .sized(0.9F, 0.9F)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .build(new ResourceLocation(Battlefield.MODID, "v1_missile").toString())
            );

    private V1Entity() {
    }
}
