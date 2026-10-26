package top.mores.battlefield.client.net;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import org.joml.Vector3f;
import top.mores.battlefield.client.ClientGameState;
import top.mores.battlefield.client.DeployPreviewClientState;
import top.mores.battlefield.client.deploy.DeployMapTexture;
import top.mores.battlefield.client.ui.RedeployScreen;
import top.mores.battlefield.game.ScoreReason;
import top.mores.battlefield.net.S2CBackpackPreview;
import top.mores.battlefield.net.S2CBombardSmokePacket;
import top.mores.battlefield.net.S2CDeployDataPacket;
import top.mores.battlefield.net.S2CDeployMapPacket;
import top.mores.battlefield.net.S2CDeployResultPacket;
import top.mores.battlefield.net.S2CGameStatePacket;
import top.mores.battlefield.net.S2CScoreToastPacket;
import top.mores.battlefield.net.S2CSectorAreaPacket;
import top.mores.battlefield.net.team.S2CSquadPanelPacket;
import top.mores.battlefield.team.ui.TeamHud;

public final class BattlefieldClientPackets {
    private BattlefieldClientPackets() {
    }

    /**
     * 部署数据到达：
     * <ul>
     *   <li>{@code showScreen=false}：不弹界面，把原因作为 actionbar 提示（例如"战斗中无法重新部署"）；</li>
     *   <li>界面已打开：原地刷新，保留当前选中项；</li>
     *   <li>界面未打开且当前只有原版死亡界面：替换成部署界面。</li>
     * </ul>
     * 刻意不抢其他容器的界面（{@code mc.screen} 是箱子/背包时不动），避免玩家正在操作时被弹窗打断。
     */
    public static void handleDeployData(S2CDeployDataPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (!msg.showScreen()) {
            if (mc.screen instanceof RedeployScreen screen) {
                screen.closeFromServer();
                return;
            }
            String reason = msg.data().denyReason();
            if (!reason.isBlank()) {
                mc.player.displayClientMessage(Component.literal(reason).withStyle(ChatFormatting.YELLOW), true);
            }
            return;
        }

        if (mc.screen instanceof RedeployScreen screen) {
            screen.applyData(msg.data());
            return;
        }
        if (mc.screen == null || mc.screen instanceof DeathScreen) {
            mc.setScreen(new RedeployScreen(msg.data()));
        }
    }

    public static void handleDeployResult(S2CDeployResultPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof RedeployScreen screen) {
            screen.onDeployResult(msg.requestId(), msg.ok(), msg.message());
        }
    }

    /** 底图到达：先入纹理缓存，界面开着就立刻换图重算投影。 */
    public static void handleDeployMap(S2CDeployMapPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!DeployMapTexture.apply(msg)) return;
        if (mc.screen instanceof RedeployScreen screen) {
            screen.onMapArrived();
        }
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

        // 坐标异常时直接丢弃，避免把 NaN 递给粒子系统
        if (!Double.isFinite(msg.x) || !Double.isFinite(msg.y) || !Double.isFinite(msg.z)) return;

        // 发射方阵营 == 本地玩家阵营 视为友方；观战者（myTeam=2）只有发射方也是观战时才算友方
        float[] color = (ClientGameState.myTeam == msg.ownerTeam)
                ? S2CBombardSmokePacket.FRIENDLY_COLOR
                : S2CBombardSmokePacket.ENEMY_COLOR;
        var dust = new DustParticleOptions(new Vector3f(color[0], color[1], color[2]), 1.5f);

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
        Minecraft.getInstance().execute(() -> DeployPreviewClientState.putPreview(msg.soldierClass(), msg.items(), msg.status()));
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
