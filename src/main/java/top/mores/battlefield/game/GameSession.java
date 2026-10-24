package top.mores.battlefield.game;

import net.minecraft.server.level.ServerLevel;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.config.BattlefieldServerConfig;

import java.util.List;

public class GameSession {
    public static final int DEFAULT_MATCH_DURATION_TICKS = 20 * 60 * 20; // 20 分钟

    public final ServerLevel level;
    public List<Sector> sectors;
    public int currentSectorIndex = 0;

    public int attackerTickets = 300;
    public int addMilitary = 0;
    public int defenderTickets = 0;
    public long startGameTick = 0;
    public int matchDurationTicks = DEFAULT_MATCH_DURATION_TICKS;

    /**
     * 下一次发放「据点驻留得分」的 gameTime。
     * <p>
     * 原实现用 {@code gameTime % scoreAwardIntervalTicks == 0} 判断，而占点循环只在
     * {@code tickCounter % captureIntervalTicks == 0} 时执行。两套时钟存在固定偏移，
     * 偏移不是 captureIntervalTicks 的整数倍时两者永远不会在同一 tick 相交，
     * 结果是约 9/10 的对局占点分一次都发不出去。
     * <p>
     * 改成"记录下一次到期时刻、到期即发并顺延"，调度与比较都用 gameTime，
     * 不再依赖另一个计数器的相位。
     */
    private long nextScoreAwardTick = Long.MIN_VALUE;

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

    public GameSession(ServerLevel serverLevel, List<Sector> sectors, int attackerTickets, int addMilitary, int matchMinutes) {
        this.level = serverLevel;
        this.sectors = sectors;
        this.attackerTickets = Math.max(1, attackerTickets);
        this.addMilitary = Math.max(0, addMilitary);
        this.matchDurationTicks = Math.max(1, matchMinutes) * 60 * 20;
        this.startGameTick = serverLevel.getGameTime();
        this.nextScoreAwardTick = this.startGameTick + scoreAwardInterval();
    }

    private static int scoreAwardInterval() {
        return Math.max(1, BattlefieldServerConfig.get().scoreAwardIntervalTicks);
    }

    /**
     * 本 tick 是否应该发放一次据点驻留得分。
     * <p>
     * 对同一个 tick 内的多个据点只返回一次 true（由 {@link top.mores.battlefield.game.SectorManager}
     * 在 tick 开头取一次后传给所有据点），保持"所有据点同时结算"的原有语义。
     */
    public boolean isScoreAwardDue() {
        long now = level.getGameTime();
        if (now < nextScoreAwardTick) return false;
        nextScoreAwardTick = now + scoreAwardInterval();
        return true;
    }

    public int getRemainingTicks() {
        long passed = Math.max(0L, level.getGameTime() - startGameTick);
        return Math.max(0, matchDurationTicks - (int) passed);
    }

    public void setSectors(List<Sector> newSectors) {
        this.sectors = newSectors;

        if (currentSectorIndex < 0) currentSectorIndex = 0;
        if (newSectors == null || newSectors.isEmpty()) {
            currentSectorIndex = 0;
            return;
        }
        if (currentSectorIndex >= newSectors.size()) currentSectorIndex = newSectors.size() - 1;
    }
}
