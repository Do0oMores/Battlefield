package top.mores.battlefield.team;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import top.mores.battlefield.game.medical.MedicalState;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class MedicalSavedDataTest {
    @Test void diskRoundTripRetainsConsumedReserveRoleAndLifeButNotTemporaryActions() {
        var id=UUID.randomUUID(); var data=new TeamSavedData(); var s=data.medical(id);
        s.deploy(MedicalState.Role.SUPPORT,"match#1","ATTACKERS"); var a=s.begin(0,14,true); s.complete(a,14,true,true);
        var restored=TeamSavedData.load(data.save(new CompoundTag())).medical(id);
        assertEquals(0,restored.reserve()); assertEquals(s.life,restored.life); assertEquals(s.role,restored.role);
        assertEquals("match#1",restored.match); assertTrue(restored.deployed); assertNull(restored.action());
    }
}
