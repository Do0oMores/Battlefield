package top.mores.battlefield.game;

import net.minecraft.server.level.ServerLevel;
import top.mores.battlefield.breakthrough.Sector;

import java.util.List;

public class GameSession {
    public final ServerLevel level;
    public final List<Sector> sectors;
    public int currentSectorIndex = 0;

    public int attackerTickets = 300;

    // 可选比分
    public int attackerScore = 0;
    public int defenderScore = 0;
    public boolean running = true;

    public Sector currentSector() {
        if (currentSectorIndex < 0) currentSectorIndex = 0;
        if (currentSectorIndex >= sectors.size()) return null;
        return sectors.get(currentSectorIndex);
    }

    public List<Sector> getSectors() {
        return sectors;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public GameSession(ServerLevel serverLevel, List<Sector> sectors) {
        this.level = serverLevel;
        this.sectors = sectors;
    }
}
