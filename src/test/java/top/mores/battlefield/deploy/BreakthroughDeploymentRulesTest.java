package top.mores.battlefield.deploy;

import org.junit.jupiter.api.Test;
import top.mores.battlefield.team.TeamId;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 突破模式部署规则的纯逻辑测试。
 * <p>
 * 这些用例覆盖的都是"服务端凭什么给某个位置发可部署/不可部署"的判定，
 * 与界面、网络、Minecraft 运行时无关——规则类刻意不引用任何 MC 类型就是为了能这样测。
 */
class BreakthroughDeploymentRulesTest {

    private static final DeploymentRules RULES = BreakthroughDeploymentRules.INSTANCE;

    private static DeploymentContext context(TeamId team, List<DeploymentContext.PointState> points,
                                             List<DeploymentContext.BeaconState> beacons, boolean hasBase) {
        return new DeploymentContext(team, 0, 2, "S1", points, beacons,
                hasBase, 100, 64, 100);
    }

    private static DeploymentContext.PointState point(String id, TeamId owner, int progress, int atkIn, int defIn) {
        return new DeploymentContext.PointState(id, 0, 64, 0, 6, owner, progress, atkIn, defIn);
    }

    private static DeploymentOption option(DeploymentContext ctx, String id) {
        for (DeploymentTarget target : RULES.targets(ctx)) {
            if (target.id().equals(id)) return target.option();
        }
        return null;
    }

    @Test
    void attackersCanDeployOnOwnPointsOnly() {
        DeploymentContext ctx = context(TeamId.ATTACKERS, List.of(
                point("A", TeamId.ATTACKERS, 100, 2, 0),
                point("B", TeamId.DEFENDERS, -100, 0, 3)
        ), List.of(), true);

        DeploymentOption a = option(ctx, "point:A");
        DeploymentOption b = option(ctx, "point:B");

        assertNotNull(a);
        assertEquals(DeploymentOption.State.AVAILABLE, a.state());
        assertEquals(DeploymentOption.Kind.CAPTURE_POINT, a.kind());

        assertNotNull(b);
        assertEquals(DeploymentOption.State.UNAVAILABLE, b.state());
        assertFalse(b.detail().isBlank(), "不可用位置必须给出原因");
    }

    @Test
    void defendersMirrorTheSameRule() {
        DeploymentContext ctx = context(TeamId.DEFENDERS, List.of(
                point("A", TeamId.ATTACKERS, 100, 2, 0),
                point("B", TeamId.DEFENDERS, -100, 0, 3)
        ), List.of(), true);

        assertEquals(DeploymentOption.State.UNAVAILABLE, option(ctx, "point:A").state());
        assertEquals(DeploymentOption.State.AVAILABLE, option(ctx, "point:B").state());
    }

    @Test
    void contestedOwnPointStaysDeployableButWarned() {
        DeploymentContext ctx = context(TeamId.DEFENDERS,
                List.of(point("A", TeamId.DEFENDERS, -40, 3, 1)), List.of(), true);

        DeploymentOption a = option(ctx, "point:A");
        assertEquals(DeploymentOption.State.CONTESTED, a.state());
        assertTrue(a.selectable(), "交战中仍然可以部署");
        assertTrue(a.detail().contains("3"), "应当带上点内敌人数：" + a.detail());
    }

    @Test
    void baseComesFirstAndBeaconsFollow() {
        DeploymentContext ctx = context(TeamId.ATTACKERS, List.of(),
                List.of(new DeploymentContext.BeaconState("uuid-1", "信标 - Alice", 10, 70, 10, false)), true);

        List<DeploymentTarget> targets = RULES.targets(ctx);
        assertEquals(2, targets.size()); // 基地 + 信标
        assertEquals("base", targets.get(0).id());
        assertEquals(DeploymentOption.Kind.BEACON, targets.get(1).option().kind());
        assertEquals(DeploymentOption.State.AVAILABLE, targets.get(1).option().state());
    }

    @Test
    void blockedBeaconIsUnavailableWithReason() {
        DeploymentContext ctx = context(TeamId.ATTACKERS, List.of(),
                List.of(new DeploymentContext.BeaconState("uuid-2", "信标", 10, 70, 10, true)), true);

        DeploymentOption beacon = option(ctx, "beacon:uuid-2");
        assertNotNull(beacon);
        assertEquals(DeploymentOption.State.UNAVAILABLE, beacon.state());
        assertFalse(beacon.detail().isBlank());
        assertNull(DeploymentRules.find(RULES.targets(ctx), "beacon:uuid-2"),
                "不可用的信标不能被解析成部署目标");
    }

    @Test
    void noPointsAndNoBaseDoesNotProduceInventedOptions() {
        DeploymentContext empty = context(TeamId.DEFENDERS, List.of(), List.of(), false);
        assertTrue(RULES.targets(empty).isEmpty());

        // 空战线但有基地：只应出现基地一个选项
        DeploymentContext baseOnly = context(TeamId.DEFENDERS, List.of(), List.of(), true);
        assertEquals(1, RULES.targets(baseOnly).size());
    }

    @Test
    void findOnlyMatchesDeployableTargets() {
        DeploymentContext ctx = context(TeamId.ATTACKERS, List.of(
                point("A", TeamId.ATTACKERS, 100, 0, 0),
                point("B", TeamId.DEFENDERS, -100, 0, 0)
        ), List.of(), true);

        List<DeploymentTarget> targets = RULES.targets(ctx);
        assertNotNull(DeploymentRules.find(targets, "point:A"));
        assertNull(DeploymentRules.find(targets, "point:B"));
        assertNull(DeploymentRules.find(targets, "point:ZZZ"), "未知 id 不能被当成合法落点");
        assertNull(DeploymentRules.find(targets, null));
    }

    @Test
    void unknownModeFallsBackToBreakthroughInsteadOfCrashing() {
        assertEquals(BreakthroughDeploymentRules.MODE_ID, DeploymentRules.of("conquest_not_implemented").modeId());
        assertEquals(BreakthroughDeploymentRules.MODE_ID, DeploymentRules.of(null).modeId());
        assertTrue(DeploymentRules.Registry.knows(BreakthroughDeploymentRules.MODE_ID));
    }
}
