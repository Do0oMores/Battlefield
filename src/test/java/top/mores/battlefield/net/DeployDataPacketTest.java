package top.mores.battlefield.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import top.mores.battlefield.deploy.DeploymentData;
import top.mores.battlefield.deploy.DeploymentOption;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署数据包的编解码测试。
 * <p>
 * 重点不是"能编能解"，而是三件容易在实机上炸掉客户端的事：
 * 字段长度超限、可选点数量超限、以及两端读取上限不一致导致的错位。
 */
class DeployDataPacketTest {

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private static DeploymentOption option(String id, DeploymentOption.State state) {
        return new DeploymentOption(id, DeploymentOption.Kind.CAPTURE_POINT, state,
                "据点 " + id, "己方控制", 12.5, -30.25);
    }

    @Test
    void roundTripKeepsEveryField() {
        DeploymentData data = new DeploymentData("breakthrough", "突破", "area_alpha", "area_alpha-abc12345-3-1a2b",
                "战线 S1（1/2）", (byte) 0, true, "", 0,
                List.of(
                        new DeploymentOption("base", DeploymentOption.Kind.BASE, DeploymentOption.State.AVAILABLE,
                                "攻方主基地", "初始出生点", 100.0, 200.0),
                        option("A", DeploymentOption.State.CONTESTED),
                        new DeploymentOption("beacon:abc", DeploymentOption.Kind.BEACON,
                                DeploymentOption.State.UNAVAILABLE, "信标", "落点被阻挡", -8.0, 4.0)
                ));

        FriendlyByteBuf buf = buffer();
        S2CDeployDataPacket.encode(new S2CDeployDataPacket(true, data), buf);
        S2CDeployDataPacket decoded = S2CDeployDataPacket.decode(buf);

        assertTrue(decoded.showScreen());
        assertEquals("breakthrough", decoded.data().modeId());
        assertEquals("area_alpha", decoded.data().mapName());
        assertEquals("area_alpha-abc12345-3-1a2b", decoded.data().mapId());
        assertEquals("战线 S1（1/2）", decoded.data().sectorLabel());
        assertEquals(0, decoded.data().team());
        assertTrue(decoded.data().canDeploy());
        assertEquals(3, decoded.data().options().size());

        DeploymentOption third = decoded.data().options().get(2);
        assertEquals("beacon:abc", third.id());
        assertEquals(DeploymentOption.Kind.BEACON, third.kind());
        assertEquals(DeploymentOption.State.UNAVAILABLE, third.state());
        assertEquals(-8.0, third.x());
        assertEquals(4.0, third.z());
    }

    @Test
    void overlayLongStringsAreClampedNotDropped() {
        String longName = "很长的名字".repeat(40);
        DeploymentData data = new DeploymentData(longName, longName, longName, longName, longName,
                (byte) 1, false, longName, 12, List.of(
                new DeploymentOption(longName, DeploymentOption.Kind.BEACON, DeploymentOption.State.AVAILABLE,
                        longName, longName, 0, 0)));

        FriendlyByteBuf buf = buffer();
        S2CDeployDataPacket.encode(new S2CDeployDataPacket(false, data), buf);
        S2CDeployDataPacket decoded = S2CDeployDataPacket.decode(buf);

        assertFalse(decoded.showScreen());
        assertEquals(S2CDeployDataPacket.MAX_MODE_ID, decoded.data().modeId().length());
        assertEquals(S2CDeployDataPacket.MAX_REASON, decoded.data().denyReason().length());
        assertEquals(S2CDeployDataPacket.MAX_OPTION_ID, decoded.data().options().get(0).id().length());
    }

    @Test
    void optionCountIsCappedOnBothSides() {
        List<DeploymentOption> many = new ArrayList<>();
        for (int i = 0; i < S2CDeployDataPacket.MAX_OPTIONS + 20; i++) {
            many.add(option("p" + i, DeploymentOption.State.AVAILABLE));
        }
        DeploymentData data = new DeploymentData("breakthrough", "突破", "map", "map-1-3-1", "S1",
                (byte) 0, true, "", 0, many);

        FriendlyByteBuf buf = buffer();
        S2CDeployDataPacket.encode(new S2CDeployDataPacket(true, data), buf);
        S2CDeployDataPacket decoded = S2CDeployDataPacket.decode(buf);

        assertEquals(S2CDeployDataPacket.MAX_OPTIONS, decoded.data().options().size());
    }

    @Test
    void closedDataSurvivesRoundTrip() {
        FriendlyByteBuf buf = buffer();
        S2CDeployDataPacket.encode(new S2CDeployDataPacket(false, DeploymentData.CLOSED), buf);
        S2CDeployDataPacket decoded = S2CDeployDataPacket.decode(buf);

        assertFalse(decoded.showScreen());
        assertTrue(decoded.data().options().isEmpty());
        assertEquals("", decoded.data().modeId());
    }
}
