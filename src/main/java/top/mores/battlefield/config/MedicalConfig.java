package top.mores.battlefield.config;

public record MedicalConfig(int useTicks, int throwCooldownTicks, double range, double speed,
                            int lifetimeTicks, double aimDegrees, double turnDegrees, int healTicks) {
    public static MedicalConfig defaults() { return new MedicalConfig(14, 20, 12, 0.65, 40, 20, 12, 40); }
    static MedicalConfig from(BattlefieldServerConfig.Root root) {
        var d = defaults();
        return new MedicalConfig(integer(root.medicalUseTicks, 1, 100, d.useTicks),
                integer(root.medicalThrowCooldownTicks, 1, 1200, d.throwCooldownTicks),
                number(root.medicalRange, 1, 32, d.range), number(root.medicalSpeed, 0.1, 2, d.speed),
                integer(root.medicalLifetimeTicks, 5, 200, d.lifetimeTicks),
                number(root.medicalAimDegrees, 1, 45, d.aimDegrees), number(root.medicalTurnDegrees, 1, 30, d.turnDegrees),
                integer(root.medicalHealTicks, 1, 200, d.healTicks));
    }
    private static int integer(Integer n, int min, int max, int fallback) { return n != null && n >= min && n <= max ? n : fallback; }
    private static double number(Double n, double min, double max, double fallback) {
        return n != null && Double.isFinite(n) && n >= min && n <= max ? n : fallback;
    }
}
