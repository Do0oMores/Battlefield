package top.mores.battlefield.net;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class MedicalPacketTest {
    @Test void authorityAndAttemptIdentitySurviveStateRoundTrip() {
        var p=new S2CMedicalState(UUID.randomUUID(),33,true,true,0,32,5,14);
        var b=new FriendlyByteBuf(Unpooled.buffer()); S2CMedicalState.encode(p,b);
        assertEquals(p,S2CMedicalState.decode(b)); assertEquals(0,b.readableBytes()); b.release();
    }
    @Test void staleIntentionCannotAffectANewActionOrDeployment() {
        var s=new top.mores.battlefield.game.medical.MedicalState();
        s.deploy(top.mores.battlefield.game.medical.MedicalState.Role.ASSAULT,"match","ATTACKERS");
        var p=new C2SMedicalRequest(s.life,s.revision(),false); assertTrue(p.matches(s));
        s.begin(0,14,true); assertFalse(p.matches(s)); s.cancel();
        var next=new C2SMedicalRequest(s.life,s.revision(),false); assertTrue(next.matches(s));
        s.deploy(top.mores.battlefield.game.medical.MedicalState.Role.MEDIC,"match","ATTACKERS");
        assertFalse(next.matches(s));
    }
    @Test void intentionsHaveNoActorTargetInventoryOrCompletionPayload() {
        for(boolean give:new boolean[]{true,false}) {
            var p=new C2SMedicalRequest(UUID.randomUUID(),12,give);
            var b=new FriendlyByteBuf(Unpooled.buffer()); C2SMedicalRequest.encode(p,b);
            assertEquals(25,b.readableBytes()); assertEquals(p,C2SMedicalRequest.decode(b)); b.release();
        }
    }
}
