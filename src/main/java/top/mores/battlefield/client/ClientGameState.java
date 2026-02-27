package top.mores.battlefield.client;

import net.minecraft.client.Minecraft;
import top.mores.battlefield.net.S2CGameStatePacket;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ClientGameState {
    private ClientGameState() {
    }

    /**
     * 0=ATTACKERS 1=DEFENDERS 2=SPECTATOR
     */
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

            if (last == null) continue;

            // 点位被攻方占满：
            // - 攻方听到“占领点”
            // - 守方听到“失去点位”
            final int CAP_T = 99;
            if (last < CAP_T && p.progress >= CAP_T) {
                Minecraft.getInstance().player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("CAPTURE TRIGGER: A" + p.id + " prog=" + p.progress + " last=" + last)
                );
                if (myTeam == 0) VoiceManager.play(ModSounds.VOICE_POINT_CAPTURED.get());
                else if (myTeam == 1) VoiceManager.play(ModSounds.VOICE_POINT_LOST_A.get());
            }

// 守方占满：- 守方听“占领点” - 攻方听“失去点位”
            if (last > -CAP_T && p.progress <= -CAP_T) {
                Minecraft.getInstance().player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("CAPTURE TRIGGER: B" + p.id + " prog=" + p.progress + " last=" + last)
                );
                if (myTeam == 1) VoiceManager.play(ModSounds.VOICE_POINT_CAPTURED.get());
                else if (myTeam == 0) VoiceManager.play(ModSounds.VOICE_POINT_LOST_A.get());
            }
        }
    }
}
