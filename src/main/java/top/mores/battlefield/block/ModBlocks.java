package top.mores.battlefield.block;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import top.mores.battlefield.Battlefield;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Battlefield.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Battlefield.MODID);

    public static final RegistryObject<Block> TACZ_AMMO_STATION =
            BLOCKS.register("tacz_ammo_station",
                    () -> new TaczAmmoStationBlock(
                            BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                    ));

    public static final RegistryObject<Item> TACZ_AMMO_STATION_ITEM =
            ITEMS.register("tacz_ammo_station",
                    () -> new BlockItem(TACZ_AMMO_STATION.get(), new Item.Properties()));

    public static final RegistryObject<Block> V1_MISSILE =
            BLOCKS.register("v1_missile",
                    () -> new Block(
                            BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                                    .noOcclusion()
                                    .strength(2.0F)
                    ));

    private ModBlocks() {
    }
}