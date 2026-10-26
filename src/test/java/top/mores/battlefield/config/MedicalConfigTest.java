package top.mores.battlefield.config;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MedicalConfigTest {
    @Test void defaultsHaveTheRequiredFourteenTickDeadline() {
        assertEquals(MedicalConfig.defaults(),MedicalConfig.from(new BattlefieldServerConfig.Root()));
        assertEquals(14,MedicalConfig.defaults().useTicks());
        // 回血窗口默认 40 tick：100 血 / 40 tick = 2.5 血每 tick，逐步补满而不是一次加满
        assertEquals(40,MedicalConfig.defaults().healTicks());
    }
    @Test void invalidAndNonFiniteValuesFallBack() {
        var root=new BattlefieldServerConfig.Root(); root.medicalUseTicks=0; root.medicalRange=Double.NaN;
        root.medicalSpeed=Double.POSITIVE_INFINITY; root.medicalTurnDegrees=180.0; root.medicalAimDegrees=-1.0;
        root.medicalLifetimeTicks=9999; root.medicalThrowCooldownTicks=-1; root.medicalHealTicks=0;
        assertEquals(MedicalConfig.defaults(),MedicalConfig.from(root));
    }
    @Test void limitsAreAccepted() {
        var root=new BattlefieldServerConfig.Root(); root.medicalUseTicks=100; root.medicalRange=32.0;
        root.medicalSpeed=2.0; root.medicalTurnDegrees=30.0; root.medicalAimDegrees=45.0;
        root.medicalLifetimeTicks=200; root.medicalThrowCooldownTicks=1200; root.medicalHealTicks=200;
        assertEquals(new MedicalConfig(100,1200,32,2,200,45,30,200),MedicalConfig.from(root));
    }
}
