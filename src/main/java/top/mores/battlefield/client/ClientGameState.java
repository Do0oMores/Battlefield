package top.mores.battlefield.client;

import top.mores.battlefield.net.S2CGameStatePacket;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ClientGameState {
    private ClientGameState(){}

    /** 0=ATTACKERS 1=DEFENDERS 2=SPECTATOR */
    public static byte myTeam = 2;

    public static int attackerTickets = 0;
    public static int defenderTickets = -1;
    public static List<S2CGameStatePacket.PointInfo> points = Collections.emptyList();
    private static final Map<String, Integer> lastProgressById = new HashMap<>();
    public static final Map<String, Integer> deltaProgressById = new HashMap<>();

    public static void update(byte myTeam0, int atk, int def, List<S2CGameStatePacket.PointInfo> pts) {
        myTeam = myTeam0;
        attackerTickets = atk;
        defenderTickets = def;
        points = pts;
        deltaProgressById.clear();
        for (S2CGameStatePacket.PointInfo p : pts) {
            Integer last = lastProgressById.put(p.id, p.progress);
            int dp = (last == null) ? 0 : (p.progress - last);
            deltaProgressById.put(p.id, dp);
        }
    }
}
