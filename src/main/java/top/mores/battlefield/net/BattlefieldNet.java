package top.mores.battlefield.net;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import top.mores.battlefield.Battlefield;

public final class BattlefieldNet {
    private BattlefieldNet() {
    }

    private static final String PROTOCOL = "1";
    public static final SimpleChannel CH = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Battlefield.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals
    );

    private static int id = 0;

    public static void init() {
        CH.registerMessage(
                id++,
                S2CGameStatePacket.class,
                S2CGameStatePacket::encode,
                S2CGameStatePacket::decode,
                S2CGameStatePacket::handle
        );
    }
}
