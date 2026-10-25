package top.mores.battlefield.deploy;

import top.mores.battlefield.team.TeamId;

import java.util.ArrayList;
import java.util.List;

/**
 * 突破模式的部署规则。
 * <p>
 * 规则来源就是现有 {@code SectorManager} 的据点模型，没有新增任何服务端状态：
 * <ul>
 *   <li>主出生点：本方基地，恒可用；</li>
 *   <li>据点：只看<b>当前战线</b>的据点——战线推进后旧据点自然从列表里消失，
 *       这正好满足「阶段切换后旧出生点不能继续使用」，不需要额外的失效表；</li>
 *   <li>据点归属：己方控制才能部署。点内有敌人时标为「交战中」，仍然可部署但给出风险提示；</li>
 *   <li>小队信标：复用 {@code BeaconRespawnLogic} 已经有的是否可用判定。</li>
 * </ul>
 * 队友个体部署（点名某名玩家）本工程没有实现——没有「以某个玩家为锚点、校验其存活与合法性」的
 * 服务端逻辑，因此这里不提供该选项，避免出现点了没反应的假入口。
 */
public final class BreakthroughDeploymentRules implements DeploymentRules {

    public static final String MODE_ID = "breakthrough";
    public static final BreakthroughDeploymentRules INSTANCE = new BreakthroughDeploymentRules();

    static final String BASE_ID = "base";
    static final String POINT_PREFIX = "point:";
    static final String BEACON_PREFIX = "beacon:";

    private BreakthroughDeploymentRules() {
    }

    @Override
    public String modeId() {
        return MODE_ID;
    }

    @Override
    public String displayName() {
        return "突破";
    }

    @Override
    public List<DeploymentTarget> targets(DeploymentContext ctx) {
        List<DeploymentTarget> targets = new ArrayList<>();

        if (ctx.hasBase()) {
            targets.add(new DeploymentTarget(
                    new DeploymentOption(BASE_ID, DeploymentOption.Kind.BASE, DeploymentOption.State.AVAILABLE,
                            baseName(ctx.team()), "初始出生点", ctx.baseX(), ctx.baseZ()),
                    ctx.baseX(), ctx.baseY(), ctx.baseZ(), 0.0f));
        }

        for (DeploymentContext.PointState point : ctx.points()) {
            targets.add(pointTarget(ctx.team(), point));
        }

        for (DeploymentContext.BeaconState beacon : ctx.beacons()) {
            DeploymentOption.State state = beacon.blocked()
                    ? DeploymentOption.State.UNAVAILABLE
                    : DeploymentOption.State.AVAILABLE;
            String detail = beacon.blocked() ? "落点被阻挡" : "小队复活信标";
            targets.add(new DeploymentTarget(
                    new DeploymentOption(BEACON_PREFIX + beacon.id(), DeploymentOption.Kind.BEACON, state,
                            beacon.name(), detail, beacon.x(), beacon.z()),
                    beacon.x(), beacon.y(), beacon.z(), 0.0f));
        }

        return targets;
    }

    private DeploymentTarget pointTarget(TeamId myTeam, DeploymentContext.PointState point) {
        TeamId owner = point.owner();
        int friends;
        int enemies;
        if (myTeam == TeamId.ATTACKERS) {
            friends = point.attackersIn();
            enemies = point.defendersIn();
        } else {
            friends = point.defendersIn();
            enemies = point.attackersIn();
        }

        DeploymentOption.State state;
        String detail;
        if (owner == myTeam) {
            if (enemies > 0) {
                state = DeploymentOption.State.CONTESTED;
                detail = "交战中 · 敌 " + enemies + " 人";
            } else {
                state = DeploymentOption.State.AVAILABLE;
                detail = friends > 0 ? "己方控制 · 我方 " + friends + " 人" : "己方控制";
            }
        } else if (owner == TeamId.ATTACKERS || owner == TeamId.DEFENDERS) {
            state = DeploymentOption.State.UNAVAILABLE;
            detail = "敌方控制，无法部署";
        } else {
            state = DeploymentOption.State.UNAVAILABLE;
            detail = "中立据点，尚不可部署";
        }

        return new DeploymentTarget(
                new DeploymentOption(POINT_PREFIX + point.id(), DeploymentOption.Kind.CAPTURE_POINT, state,
                        "据点 " + point.id(), detail, point.x(), point.z()),
                point.x(), point.y(), point.z(), 0.0f);
    }

    private static String baseName(TeamId team) {
        return team == TeamId.ATTACKERS ? "攻方主基地" : "守方主基地";
    }
}
