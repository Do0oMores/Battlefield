package top.mores.battlefield.game;

/**
 * 「这一局还能不能再进一个人」的判定。
 * <p>
 * 刻意不引用任何 Minecraft 类型，方便直接单测（与 {@code deploy} 下的规则类同一套路）。
 * <p>
 * 只比较总人数上限是不够的：真正卡人的是两队各自的容量。攻守两队各 1 人时，即便总人数上限配成 1，
 * 第二个人也会被判定为「对局已满」而去另开一局，而新局同样只装得下一个人，
 * {@code minPlayerNumber} 于是永远凑不齐，谁都开不了局。所以这里同时要求总人数没满、且至少一队还有空位。
 */
public final class MatchCapacity {
    private MatchCapacity() {
    }

    /**
     * @param participants 已在册的参与者人数（含掉线但未结算的）
     * @param maxPlayers   本局总人数上限（{@code maxPlayerNumber}）
     * @param attackers    在册攻方人数
     * @param attackCap    攻方容量（{@code attackNumber}）
     * @param defenders    在册守方人数
     * @param defendCap    守方容量（{@code defendNumber}）
     * @return 还能再进一个人则为 true
     */
    public static boolean hasRoomForJoiner(int participants, int maxPlayers,
                                           int attackers, int attackCap,
                                           int defenders, int defendCap) {
        if (participants >= maxPlayers) return false;
        return attackers < attackCap || defenders < defendCap;
    }
}
