package top.mores.battlefield.team;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SquadScoreManager {
    private static final Map<UUID, Integer> SQUAD_SCORES = new HashMap<>();

    private SquadScoreManager() {
    }

    public static int getScore(UUID squadUuid) {
        if (squadUuid == null) return 0;
        return SQUAD_SCORES.getOrDefault(squadUuid, 0);
    }

    public static int getScore(ServerPlayer player) {
        return getScore(SquadManager.getSquadUuid(player));
    }

    public static void addScore(ServerPlayer player, int delta) {
        if (delta <= 0) return;
        UUID squadUuid = SquadManager.getSquadUuid(player);
        addScore(squadUuid, delta);
    }

    public static void addScore(UUID squadUuid, int delta) {
        if (squadUuid == null || delta <= 0) return;
        SQUAD_SCORES.merge(squadUuid, delta, Integer::sum);
    }

    public static void clearSquad(UUID squadUuid) {
        if (squadUuid == null) return;
        SQUAD_SCORES.remove(squadUuid);
    }

    public static void clearAll() {
        SQUAD_SCORES.clear();
    }
}