package top.mores.battlefield.client.deploy;

import top.mores.battlefield.client.ClientGameState;
import top.mores.battlefield.deploy.DeploymentData;

import java.util.ArrayList;
import java.util.List;

/**
 * 顶部战局信息栏的模板。
 * <p>
 * 不同模式显示的字段本来就不一样（突破看兵力，团队死斗看比分，突袭看阶段与目标），
 * 所以模板按 {@code modeId} 分派，而不是把所有模式硬塞进同一个记分板。
 * 目前只有突破模式落地，其余分支留作扩展点——加模式时在这里加一个 case 即可，
 * 不需要动界面布局。
 */
public final class DeployHeaderModel {

    public record Stat(String label, String value, int color) {
    }

    private final String mode;
    private final String map;
    private final String sector;
    private final List<Stat> stats;

    private DeployHeaderModel(String mode, String map, String sector, List<Stat> stats) {
        this.mode = mode;
        this.map = map;
        this.sector = sector;
        this.stats = stats;
    }

    public String mode() {
        return mode;
    }

    public String map() {
        return map;
    }

    public String sector() {
        return sector;
    }

    public List<Stat> stats() {
        return stats;
    }

    public static DeployHeaderModel build(DeploymentData data) {
        String mode = "breakthrough".equals(data.modeId()) ? DeployText.tr("breakthrough")
                : data.modeName() == null || data.modeName().isBlank() ? DeployText.tr("unknown_mode") : data.modeName();
        String map = data.mapName() == null || data.mapName().isBlank() ? DeployText.tr("unknown_map") : data.mapName();
        String sector = data.sectorLabel() == null ? "" : data.sectorLabel();
        if (sector.startsWith("战线 ")) sector = DeployText.tr("sector", sector.substring(3));

        List<Stat> stats = new ArrayList<>();
        if ("breakthrough".equals(data.modeId())) {
            // 突破：守方兵力无限（当前规则），不再显示一个恒为 0 的数字
            stats.add(new Stat(DeployText.tr("attacker_tickets"), String.valueOf(ClientGameState.attackerTickets), DeployTheme.ATTACKER));
            stats.add(new Stat(DeployText.tr("defender_tickets"), "∞", DeployTheme.DEFENDER));
            stats.add(new Stat(DeployText.tr("phase"), phaseLabel(), DeployTheme.TEXT));
            stats.add(new Stat(DeployText.tr("remaining"), timeLabel(), DeployTheme.TEXT));
        } else {
            // 未实现的模式不编造计分方式
            stats.add(new Stat(DeployText.tr("match"), ClientGameState.inBattle ? DeployText.tr("phase.running") : DeployText.tr("phase.absent"), DeployTheme.TEXT));
            stats.add(new Stat(DeployText.tr("phase"), phaseLabel(), DeployTheme.TEXT_MUTED));
            stats.add(new Stat(DeployText.tr("remaining"), timeLabel(), DeployTheme.TEXT_MUTED));
        }
        return new DeployHeaderModel(mode, map, sector, stats);
    }

    private static String phaseLabel() {
        return switch (ClientGameState.phase) {
            case 0 -> DeployText.tr("phase.waiting");
            case 1 -> DeployText.tr("phase.countdown");
            case 2 -> DeployText.tr("phase.running");
            case 3 -> DeployText.tr("phase.ending");
            default -> DeployText.tr("phase.unknown");
        };
    }

    private static String timeLabel() {
        int ticks = ClientGameState.remainingTimeTicks;
        if (ticks <= 0) return "--:--";
        int seconds = ticks / 20;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
