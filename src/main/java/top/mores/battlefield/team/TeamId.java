package top.mores.battlefield.team;

public enum TeamId {
    ATTACKERS,
    DEFENDERS,
    SPECTATOR;

    public static TeamId fromString(String s) {
        if (s == null) return SPECTATOR;
        return switch (s.toLowerCase()) {
            case "a", "attacker", "attackers" -> ATTACKERS;
            case "d", "defender", "defenders" -> DEFENDERS;
            case "spec", "spectator" -> SPECTATOR;
            default -> SPECTATOR;
        };
    }
}
