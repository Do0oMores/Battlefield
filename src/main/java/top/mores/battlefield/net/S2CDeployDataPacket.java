package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.net.BattlefieldClientPackets;
import top.mores.battlefield.deploy.DeploymentData;
import top.mores.battlefield.deploy.DeploymentOption;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端的部署界面数据。
 * <p>
 * 替代原先"只注册从不发送"的 {@code S2COpenDeployScreenPacket}：界面需要的数据一次性带过来，
 * 客户端收到后要么更新已打开的界面，要么打开界面（{@code showScreen=true}）。
 * 字段长度全部有上限，编码端 clamp、解码端按同一上限读取，避免配置/数据异常时客户端解包掉线。
 */
public final class S2CDeployDataPacket {

    public static final int MAX_MODE_ID = 24;
    public static final int MAX_MODE_NAME = 24;
    public static final int MAX_MAP_NAME = 32;
    public static final int MAX_MAP_ID = 48;
    public static final int MAX_SECTOR_LABEL = 48;
    public static final int MAX_REASON = 96;
    public static final int MAX_OPTION_ID = 48;
    public static final int MAX_OPTION_NAME = 32;
    public static final int MAX_OPTION_DETAIL = 48;
    public static final int MAX_OPTIONS = 64;

    private final boolean showScreen;
    private final DeploymentData data;

    public S2CDeployDataPacket(boolean showScreen, DeploymentData data) {
        this.showScreen = showScreen;
        this.data = data == null ? DeploymentData.CLOSED : data;
    }

    public boolean showScreen() {
        return showScreen;
    }

    public DeploymentData data() {
        return data;
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static void encode(S2CDeployDataPacket msg, FriendlyByteBuf buf) {
        DeploymentData d = msg.data;
        buf.writeBoolean(msg.showScreen);
        buf.writeUtf(clamp(d.modeId(), MAX_MODE_ID), MAX_MODE_ID);
        buf.writeUtf(clamp(d.modeName(), MAX_MODE_NAME), MAX_MODE_NAME);
        buf.writeUtf(clamp(d.mapName(), MAX_MAP_NAME), MAX_MAP_NAME);
        buf.writeUtf(clamp(d.mapId(), MAX_MAP_ID), MAX_MAP_ID);
        buf.writeUtf(clamp(d.sectorLabel(), MAX_SECTOR_LABEL), MAX_SECTOR_LABEL);
        buf.writeByte(d.team());
        buf.writeBoolean(d.canDeploy());
        buf.writeUtf(clamp(d.denyReason(), MAX_REASON), MAX_REASON);
        buf.writeVarInt(Math.max(0, d.waitTicks()));

        int size = Math.min(d.options().size(), MAX_OPTIONS);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            DeploymentOption o = d.options().get(i);
            buf.writeUtf(clamp(o.id(), MAX_OPTION_ID), MAX_OPTION_ID);
            buf.writeByte(o.kind().ordinal());
            buf.writeByte(o.state().ordinal());
            buf.writeUtf(clamp(o.name(), MAX_OPTION_NAME), MAX_OPTION_NAME);
            buf.writeUtf(clamp(o.detail(), MAX_OPTION_DETAIL), MAX_OPTION_DETAIL);
            buf.writeDouble(o.x());
            buf.writeDouble(o.z());
        }
    }

    public static S2CDeployDataPacket decode(FriendlyByteBuf buf) {
        boolean show = buf.readBoolean();
        String modeId = buf.readUtf(MAX_MODE_ID);
        String modeName = buf.readUtf(MAX_MODE_NAME);
        String mapName = buf.readUtf(MAX_MAP_NAME);
        String mapId = buf.readUtf(MAX_MAP_ID);
        String sector = buf.readUtf(MAX_SECTOR_LABEL);
        byte team = buf.readByte();
        boolean canDeploy = buf.readBoolean();
        String reason = buf.readUtf(MAX_REASON);
        int waitTicks = Math.max(0, buf.readVarInt());

        int n = Math.min(Math.max(0, buf.readVarInt()), MAX_OPTIONS);
        List<DeploymentOption> options = new ArrayList<>(n);
        DeploymentOption.Kind[] kinds = DeploymentOption.Kind.values();
        DeploymentOption.State[] states = DeploymentOption.State.values();
        for (int i = 0; i < n; i++) {
            String id = buf.readUtf(MAX_OPTION_ID);
            byte kindIdx = buf.readByte();
            byte stateIdx = buf.readByte();
            String name = buf.readUtf(MAX_OPTION_NAME);
            String detail = buf.readUtf(MAX_OPTION_DETAIL);
            double x = buf.readDouble();
            double z = buf.readDouble();

            DeploymentOption.Kind kind = kinds[Math.floorMod((int) kindIdx, kinds.length)];
            DeploymentOption.State state = states[Math.floorMod((int) stateIdx, states.length)];
            options.add(new DeploymentOption(id, kind, state, name, detail, x, z));
        }

        return new S2CDeployDataPacket(show,
                new DeploymentData(modeId, modeName, mapName, mapId, sector, team, canDeploy, reason, waitTicks, options));
    }

    public static void handle(S2CDeployDataPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> BattlefieldClientPackets.handleDeployData(msg)));
        ctx.setPacketHandled(true);
    }
}
