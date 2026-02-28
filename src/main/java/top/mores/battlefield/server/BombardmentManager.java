package top.mores.battlefield.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.ModEntities;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.S2CBombardSmokePacket;
import top.mores.battlefield.server.entity.BombEntity;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BombardmentManager {
    private BombardmentManager() {}

    public static final int DURATION_TICKS = 15 * 20; // 15s
    public static final int TOTAL_BOMBS = 30;
    public static final int INTERVAL_TICKS = DURATION_TICKS / TOTAL_BOMBS; // 10 ticks
    public static final float RADIUS = 20f;
    public static final float DAMAGE_RADIUS = 3f;
    public static final float MAX_DAMAGE = 18f;

    private record Key(ServerLevel level, UUID owner) {}
    private record Instance(UUID owner, TeamId ownerTeam, Vec3 center, long startTick, long endTick) {}

    private static final ConcurrentHashMap<Key, Instance> RUNNING = new ConcurrentHashMap<>();

    /** 以玩家指定中心点开始轰炸（覆盖重开） */
    public static void start(ServerPlayer player, Vec3 center) {
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();

        TeamId ownerTeam = TeamManager.getTeam(player);

        RUNNING.put(new Key(level, player.getUUID()),
                new Instance(player.getUUID(), ownerTeam, center, now, now + DURATION_TICKS));
    }

    public static void stop(ServerPlayer player) {
        RUNNING.remove(new Key(player.serverLevel(), player.getUUID()));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        Iterator<Key> it = RUNNING.keySet().iterator();
        while (it.hasNext()) {
            Key key = it.next();
            Instance inst = RUNNING.get(key);
            if (inst == null) { it.remove(); continue; }

            ServerLevel level = key.level();
            long now = level.getGameTime();

            if (now >= inst.endTick()) {
                it.remove();
                continue;
            }

            // ✅ 区域烟：低频刷（按视角变色）
            if ((now & 7) == 0) { // 每 8 tick 一次
                spawnAreaSmoke(level, inst.owner(), inst.ownerTeam(), inst.center(), level.getRandom());
            }

            if ((now - inst.startTick()) % INTERVAL_TICKS == 0) {
                spawnOneBomb(level, inst.owner(), inst.ownerTeam(), inst.center(), level.getRandom());
            }
        }
    }

    private static void spawnOneBomb(ServerLevel level, UUID owner, TeamId ownerTeam, Vec3 center, RandomSource rand) {
        // 圆内均匀随机落点
        double ang = rand.nextDouble() * Math.PI * 2.0;
        double u = rand.nextDouble();
        double rr = RADIUS * Math.sqrt(u);

        double tx = center.x + Math.cos(ang) * rr;
        double tz = center.z + Math.sin(ang) * rr;

        // 地表 y
        BlockPos surface = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.containing(tx, 0, tz)
        );
        double ty = surface.getY() + 0.2;

        // ✅ 落点预警烟：按视角变色
        sendSmokePerViewer(level, owner, ownerTeam, tx, ty + 0.2, tz,
                6, 0.8f, 0.2f, 0.8f, 0.01f);

        // 炸弹出生点：目标上空
        double spawnY = ty + 60.0;

        BombEntity bomb = new BombEntity(
                ModEntities.BOMB.get(),
                level,
                new Vec3(tx, spawnY, tz),
                new Vec3(tx, ty, tz),
                DAMAGE_RADIUS,
                MAX_DAMAGE,
                owner,
                ownerTeam
        );

        bomb.setDeltaMovement(
                (rand.nextDouble() - 0.5) * 0.05,
                0.0,
                (rand.nextDouble() - 0.5) * 0.05
        );

        level.addFreshEntity(bomb);
    }

    private static void spawnAreaSmoke(ServerLevel level, UUID owner, TeamId ownerTeam, Vec3 center, RandomSource rand) {
        // 每次刷 6 团：按视角变色
        for (int i = 0; i < 6; i++) {
            double ang = rand.nextDouble() * Math.PI * 2.0;
            double u = rand.nextDouble();
            double rr = RADIUS * Math.sqrt(u);

            double x = center.x + Math.cos(ang) * rr;
            double z = center.z + Math.sin(ang) * rr;

            BlockPos surface = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    BlockPos.containing(x, 0, z)
            );
            double y = surface.getY() + 0.2;

            sendSmokePerViewer(level, owner, ownerTeam, x, y, z,
                    4, 0.6f, 0.15f, 0.6f, 0.008f);
        }
    }

    /** ✅ 逐玩家发烟：呼叫者/队友看到蓝；敌方看到红 */
    private static void sendSmokePerViewer(ServerLevel level, UUID owner, TeamId ownerTeam,
                                           double x, double y, double z,
                                           int count, float dx, float dy, float dz, float speed) {

        for (ServerPlayer sp : level.getServer().getPlayerList().getPlayers()) {
            if (sp.serverLevel() != level) continue;

            TeamId t = TeamManager.getTeam(sp);

            boolean isOwner = sp.getUUID().equals(owner);
            boolean friendly = isOwner || (t == ownerTeam);

            // 蓝/红（你可再微调）
            float r = friendly ? 0.18f : 1.0f;
            float g = friendly ? 0.45f : 0.10f;
            float b = friendly ? 1.00f : 0.10f;

            BattlefieldNet.CH.send(
                    PacketDistributor.PLAYER.with(() -> sp),
                    new S2CBombardSmokePacket(x, y, z, r, g, b, count, dx, dy, dz, speed)
            );
        }
    }
}
