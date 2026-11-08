package top.mores.battlefield.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import top.mores.battlefield.protocol.SoldierClass;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LoadoutPacketTest {
    @Test void fourClassRequestPreservesDeploymentIdentityAndHasNoInventoryOrActorUuid() {
        for(var type:SoldierClass.values()) {
            var input=new C2SDeployRequest("point:A",type,UUID.randomUUID()); var buffer=new FriendlyByteBuf(Unpooled.buffer());
            C2SDeployRequest.encode(input,buffer); assertEquals(input,C2SDeployRequest.decode(buffer));
        }
        assertEquals(List.of("optionId","soldierClass","requestId"),Arrays.stream(C2SDeployRequest.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
    }
    @Test void invalidSoldierClassIsRejectedDuringDecode() {
        var buffer=new FriendlyByteBuf(Unpooled.buffer()); buffer.writeUtf("base",48); buffer.writeUtf("ADMIN",16); buffer.writeUUID(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,()->C2SDeployRequest.decode(buffer));
    }
    @Test void oversizedPreviewCountIsRejectedBeforeAllocation() {
        var buffer=new FriendlyByteBuf(Unpooled.buffer()); buffer.writeUtf("ASSAULT",16); buffer.writeVarInt(Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,()->S2CBackpackPreview.decode(buffer));
    }
    @Test void resultIsBoundToRequestIdAndMessagesAreBounded() {
        UUID request=UUID.randomUUID(); var input=new S2CDeployResultPacket(request,false,"x".repeat(300)); var buffer=new FriendlyByteBuf(Unpooled.buffer());
        S2CDeployResultPacket.encode(input,buffer); var output=S2CDeployResultPacket.decode(buffer);
        assertEquals(request,output.requestId()); assertFalse(output.ok()); assertEquals(96,output.message().length());
    }
}
