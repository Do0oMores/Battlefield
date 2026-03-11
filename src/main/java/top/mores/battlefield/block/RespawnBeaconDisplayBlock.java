package top.mores.battlefield.block;


import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public class RespawnBeaconDisplayBlock extends Block {
    public RespawnBeaconDisplayBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(1.0f)
                .noOcclusion());
    }
}
