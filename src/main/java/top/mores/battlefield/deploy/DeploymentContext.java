package top.mores.battlefield.deploy;

import top.mores.battlefield.team.TeamId;

import java.util.List;

/**
 * 交给 {@link DeploymentRules} 的只读快照。
 * <p>
 * 刻意只放基本类型和 {@link TeamId}，不引用 {@code ServerPlayer} / {@code ServerLevel} /
 * {@code CapturePoint}。这样模式规则是纯函数：既能被单元测试直接构造，也不会顺手改到对局状态。
 * 谁把游戏状态翻译成这份快照，谁就对"取数时机"负责（目前是 {@code BattlefieldGameManager}）。
 */
public record DeploymentContext(TeamId team,
                                int sectorIndex,
                                int sectorCount,
                                String sectorId,
                                List<PointState> points,
                                List<BeaconState> beacons,
                                boolean hasBase,
                                double baseX,
                                double baseY,
                                double baseZ) {

    /** 当前战线上的一个据点。{@code attackersIn/defendersIn} 是上一次占点结算时点内的人数。 */
    public record PointState(String id, double x, double y, double z, double radius,
                             TeamId owner, int progress, int attackersIn, int defendersIn) {
    }

    /**
     * 一个可用的小队信标。
     *
     * @param blocked 落点被方块/液体堵住，或实体已不在服务端世界
     */
    public record BeaconState(String id, String name, double x, double y, double z, boolean blocked) {
    }
}
