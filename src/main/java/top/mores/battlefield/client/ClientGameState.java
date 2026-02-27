package top.mores.battlefield.client;

import net.minecraft.client.Minecraft;
import top.mores.battlefield.net.S2CGameStatePacket;
import top.mores.battlefield.net.S2CSectorAreaPacket;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ClientGameState {
    private ClientGameState() {
    }

    public static boolean inBattle = false;

    /**
     * 0=ATTACKERS 1=DEFENDERS 2=SPECTATOR
     */
    public static byte myTeam = 2;

    public static int attackerTickets = 0;
    public static int defenderTickets = -1;
    public static List<S2CGameStatePacket.PointInfo> points = Collections.emptyList();
    private static final Map<String, Integer> lastProgressById = new HashMap<>();
    public static final Map<String, Integer> deltaProgressById = new HashMap<>();
    public static int sectorIndex = 0;

    // 2D 可活动区域（固定圈）
    public static List<S2CSectorAreaPacket.AreaCircle> attackerAreas = Collections.emptyList();
    public static List<S2CSectorAreaPacket.AreaCircle> defenderAreas = Collections.emptyList();

    public static void update(boolean inBattle0, byte myTeam0, int atk, int def, List<S2CGameStatePacket.PointInfo> pts) {
        inBattle = inBattle0;

        if (!inBattle) {
            reset();
            return;
        }

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

            final int CAP_T = 99;
            if (last < CAP_T && p.progress >= CAP_T) {
                Minecraft.getInstance().player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("CAPTURE TRIGGER: A" + p.id + " prog=" + p.progress + " last=" + last)
                );
                if (myTeam == 0) VoiceManager.play(ModSounds.VOICE_POINT_CAPTURED.get());
                else if (myTeam == 1) VoiceManager.play(ModSounds.VOICE_POINT_LOST.get());
            }

            if (last > -CAP_T && p.progress <= -CAP_T) {
                Minecraft.getInstance().player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("CAPTURE TRIGGER: B" + p.id + " prog=" + p.progress + " last=" + last)
                );
                if (myTeam == 1) VoiceManager.play(ModSounds.VOICE_POINT_CAPTURED.get());
                else if (myTeam == 0) VoiceManager.play(ModSounds.VOICE_POINT_LOST.get());
            }
        }
    }

    public static void updateAreas(int newSectorIndex,
                                   List<S2CSectorAreaPacket.AreaCircle> atk,
                                   List<S2CSectorAreaPacket.AreaCircle> def) {

        boolean changed = (newSectorIndex != sectorIndex);
        sectorIndex = newSectorIndex;

        attackerAreas = (atk == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(atk);
        defenderAreas = (def == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(def);

        inBattle = true;

        if (changed && Minecraft.getInstance().player != null) {
            VoiceManager.play(ModSounds.VOICE_SECTOR_PUSH.get());
        }
    }

    public static void reset() {
        myTeam = 2;
        attackerTickets = 0;
        defenderTickets = -1;
        points = Collections.emptyList();
        attackerAreas = Collections.emptyList();
        defenderAreas = Collections.emptyList();
        sectorIndex = 0;
        lastProgressById.clear();
        deltaProgressById.clear();
    }
}
