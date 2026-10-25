package top.mores.battlefield.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「还能不能再进一个人」的判定测试。
 * <p>
 * 重点覆盖 minPlayerNumber=2 的测试配置下「每人一局」的现象：
 * 总人数上限被配成 1 时，第二个人会被判满员而另开一局，两局各 1 人，谁都开不了局。
 */
class MatchCapacityTest {

    @Test
    void secondPlayerJoinsTheFirstMatchWhenCapIsRaisedToMinimum() {
        // 玩家 A 已在局内（攻方 1/1）：总上限被抬到 minPlayerNumber=2 之后，第二个玩家还有位置
        assertTrue(MatchCapacity.hasRoomForJoiner(1, 2, 1, 1, 0, 1));
    }

    @Test
    void capSmallerThanMinimumStillReportsFull() {
        // 旧配置（maxPlayerNumber=1 < minPlayerNumber=2）的原始行为：第一个人进来就满，第二个人另建一局
        assertFalse(MatchCapacity.hasRoomForJoiner(1, 1, 1, 1, 0, 1));
    }

    @Test
    void emptyMatchAcceptsPlayer() {
        assertTrue(MatchCapacity.hasRoomForJoiner(0, 32, 0, 16, 0, 16));
    }

    @Test
    void fullTeamsRejectEvenWhenTotalCapHasRoom() {
        // 32 人上限只进了 2 人，但两队各 1 人的容量都满了
        assertFalse(MatchCapacity.hasRoomForJoiner(2, 32, 1, 1, 1, 1));
    }

    @Test
    void oneTeamHavingRoomIsEnough() {
        assertTrue(MatchCapacity.hasRoomForJoiner(17, 32, 16, 16, 1, 16));
    }

    @Test
    void totalCapWinsOverTeamRoom() {
        assertFalse(MatchCapacity.hasRoomForJoiner(32, 32, 16, 16, 16, 16));
    }
}
