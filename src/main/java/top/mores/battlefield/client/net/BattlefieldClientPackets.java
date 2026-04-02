package top.mores.battlefield.client.net;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.joml.Vector3f;
import top.mores.battlefield.client.ClientGameState;
import top.mores.battlefield.client.DeployPreviewClientState;
import top.mores.battlefield.client.ui.RespawnDeployScreen;
import top.mores.battlefield.game.ScoreReason;
import top.mores.battlefield.net.S2CBackpackPreview;
import top.mores.battlefield.net.S2CBombardSmokePacket;
import top.mores.battlefield.net.S2CGameStatePacket;
import top.mores.battlefield.net.S2COpenDeployScreenPacket;
import top.mores.battlefield.net.S2CScoreToastPacket;
import top.mores.battlefield.net.S2CSectorAreaPacket;
import top.mores.battlefield.net.team.S2CSquadPanelPacket;
import top.mores.battlefield.team.ui.TeamHud;

public final class BattlefieldClientPackets {
    private BattlefieldClientPackets() {
    }

    public static void handleOpenDeployScreen(S2COpenDeployScreenPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen != null) return;
        mc.setScreen(new RespawnDeployScreen());
    }

    public static void handleGameState(S2CGameStatePacket msg) {
        if (Minecraft.getInstance().player != null) {
            ClientGameState.update(msg.inBattle, msg.myTeam, msg.attackerTickets, msg.defenderTickets,
                    msg.remainingTimeTicks, msg.myScore, msg.myLastBonus,
                    msg.squadPlayerIds, msg.squadPlayerScores, msg.squadTotalScore,
                    msg.points, msg.phase, msg.overlayTitle, msg.overlaySub, msg.overlayTicks);
        }
    }

    public static void handleScoreToast(S2CScoreToastPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int nowTick = mc.player.tickCount;

        try {
            ScoreReason r = ScoreReason.valueOf(msg.reasonId());
            ClientGameState.pushScoreToast(msg.amount(), r.text, r.color, nowTick, r.mergeKey);
        } catch (Exception e) {
            ClientGameState.pushScoreToast(msg.amount(), msg.reasonId(), 0xFF88FF88, nowTick, null);
        }

        ClientGameState.hudLastScoreClientTick = nowTick;
    }

    public static void handleSectorAreas(S2CSectorAreaPacket msg) {
        if (Minecraft.getInstance().player != null) {
            ClientGameState.updateAreas(msg.sectorIndex, msg.attackerAreas, msg.defenderAreas);
        }
    }

    public static void handleBombardSmoke(S2CBombardSmokePacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        var dust = new DustParticleOptions(new Vector3f(msg.r, msg.g, msg.b), 1.5f);

        for (int i = 0; i < msg.count; i++) {
            double px = msg.x + (mc.level.random.nextDouble() * 2 - 1) * msg.dx;
            double py = msg.y + (mc.level.random.nextDouble() * 2 - 1) * msg.dy;
            double pz = msg.z + (mc.level.random.nextDouble() * 2 - 1) * msg.dz;

            mc.level.addParticle(dust, px, py, pz, 0, msg.speed, 0);
            if ((i & 1) == 0) {
                mc.level.addParticle(ParticleTypes.SMOKE, px, py, pz, 0, 0.01, 0);
            }
        }
    }

    public static void handleBackpackPreview(S2CBackpackPreview msg) {
        Minecraft.getInstance().execute(() -> DeployPreviewClientState.putPreview(msg.bpSlot(), msg.items()));
    }

    public static void handleSquadPanel(S2CSquadPanelPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        if (mc.screen instanceof TeamHud hud) {
            hud.applySnapshot(msg.view());
        } else {
            mc.setScreen(new TeamHud(msg.view()));
        }
    }
}
