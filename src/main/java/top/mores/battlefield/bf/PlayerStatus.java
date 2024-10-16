package top.mores.battlefield.bf;

/**
 * PLAYER_STATUS
 *
 * <pre>
 *     - NULL = 没有参与游戏
 *     - WARM = 没有参与游戏，热身
 *     - LOUNGE = 在游戏等候区
 *     - READY = 在游戏等候区且已准备
 *     - FIGHT = 正在游戏中
 *     - WATCH = 处于观战
 *     - DEAD = 死亡或重生中
 *     - OFFLINE = 在游戏中离线
 * </pre>
 */
public enum PlayerStatus {
    NULL,
    WARM,
    LOUNGE,
    READY,
    FIGHT,
    WATCH,
    DEAD,
    OFFLINE
}
