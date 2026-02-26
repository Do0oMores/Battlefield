package top.mores.battlefield.client;

import top.mores.battlefield.net.S2CGameStatePacket;

import java.util.Collections;
import java.util.List;

public final class ClientGameState {
    private ClientGameState(){}

    public static int attackerTickets = 0;
    public static int defenderTickets = -1;
    public static List<S2CGameStatePacket.PointInfo> points = Collections.emptyList();

    public static void update(int atk, int def, List<S2CGameStatePacket.PointInfo> pts) {
        attackerTickets = atk;
        defenderTickets = def;
        points = pts;
    }
}
