package top.mores.battlefield;

import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import top.mores.battlefield.game.medical.MedicalPouchService;
import top.mores.battlefield.game.medical.MedicalState;
import top.mores.battlefield.team.TeamSavedData;
import java.util.UUID;

/** Runs in a real dedicated Forge + TACZ server; no test code is packaged in the mod. */
@GameTestHolder(Battlefield.MODID)
@PrefixGameTestTemplate(false)
public class MedicalGameTests {
    @GameTest(template="medical_empty")
    public static void taczPreCostAuthorityGuard(GameTestHelper h) {
        var p=FakePlayerFactory.get(h.getLevel(),new GameProfile(UUID.randomUUID(),"medical-gun-test"));
        var gun=GunItemBuilder.create().setId(new ResourceLocation("tacz","ak47")).setAmmoCount(30).setAmmoInBarrel(true).forceBuild();
        p.setItemInHand(InteractionHand.MAIN_HAND,gun);
        var op=IGunOperator.fromLivingEntity(p); op.initialData(); op.getDataHolder().currentGunItem=()->gun;
        var state=MedicalPouchService.state(p); state.deploy(MedicalState.Role.ASSAULT,"test","ATTACKERS");
        state.begin(p.server.getTickCount(),14,true);
        var data=op.getDataHolder(); long reload=data.reloadTimestamp, melee=data.meleeTimestamp;
        for(var mode:new FireMode[]{FireMode.SEMI,FireMode.AUTO}) {
            IGun.getIGunOrNull(gun).setFireMode(gun,mode); var before=gun.copy();
            var result=op.shoot(()->0F,()->0F);
            h.assertTrue(result==ShootResult.FORGE_EVENT_CANCEL,"Authority shoot guard did not run for "+mode);
            h.assertTrue(ItemStack.matches(before,gun),"Blocked shooting mutated weapon/ammo NBT");
        }
        try {
            var charged=op.getClass().getMethod("shoot",java.util.function.Supplier.class,
                    java.util.function.Supplier.class,long.class,float.class);
            var before=gun.copy();
            Object result=charged.invoke(op,(java.util.function.Supplier<Float>)()->0F,
                    (java.util.function.Supplier<Float>)()->0F,System.currentTimeMillis(),0.75F);
            h.assertTrue(result==ShootResult.FORGE_EVENT_CANCEL && ItemStack.matches(before,gun),
                    "Charged authority request bypassed the medical guard: " + result);
        } catch(NoSuchMethodException legacyWithoutChargedShooting) {
            // Actual 1.1.7-hotfix has no charged shooting overload.
        } catch(ReflectiveOperationException ex) { throw new RuntimeException(ex); }
        op.reload(); op.melee(); op.aim(true);
        h.assertTrue(data.reloadTimestamp==reload && data.meleeTimestamp==melee && !data.isAiming,"Reload/melee/ADS bypassed medical lock");
        // With only shooter initialized, an unguarded script call would dereference its null gunIndex.
        var script=new com.tacz.guns.item.ModernKineticGunScriptAPI(); script.setShooter(p); script.shootOnce(true);
        state.cancel(); h.assertFalse(MedicalPouchService.locked(p),"Cancel left authority lock behind");
        h.succeed();
    }
    @GameTest(template="medical_empty")
    public static void realDamageAndZeroDamageCancellation(GameTestHelper h) {
        var p=new net.minecraftforge.common.util.FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"medical-hurt-test")) {
            @Override public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source) { return false; }
        };
        p.setHealth(p.getMaxHealth()); p.getAbilities().invulnerable=false;
        var state=MedicalPouchService.state(p); state.deploy(MedicalState.Role.ASSAULT,"test","ATTACKERS");
        state.begin(p.server.getTickCount(),14,true);
        p.hurt(p.damageSources().generic(),0);
        h.assertTrue(state.action()!=null,"Zero effective damage canceled treatment");
        float before=p.getHealth();
        p.hurt(p.damageSources().genericKill(),1);
        h.assertTrue(p.getHealth()<before,"Test fixture did not actually lose health");
        h.assertTrue(state.action()==null,"Actual applied damage did not cancel treatment");
        h.assertTrue(state.reserve()==1,"Damage cancellation consumed reserve");
        state.begin(p.server.getTickCount(),14,true); p.invulnerableTime=0; p.setAbsorptionAmount(3);
        float health=p.getHealth(); p.hurt(p.damageSources().genericKill(),1);
        h.assertTrue(p.getHealth()==health && p.getAbsorptionAmount()<3 && state.action()==null,"Effective absorption loss did not cancel medical");
        state.begin(p.server.getTickCount(),14,true); p.invulnerableTime=0;
        Object cancel=new Object() {
            @net.minecraftforge.eventbus.api.SubscribeEvent
            public void hurt(net.minecraftforge.event.entity.living.LivingHurtEvent e) { if(e.getEntity()==p) e.setCanceled(true); }
        };
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(cancel);
        try { p.hurt(p.damageSources().genericKill(),1); }
        finally { net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(cancel); }
        h.assertTrue(state.action()!=null,"Canceled damage canceled treatment");
        state.cancel(); h.succeed();
    }
    @GameTest(template="medical_empty")
    public static void effectCannotPersistOrBePickedUp(GameTestHelper h) {
        var e=ModEntities.MEDICAL_POUCH.get().create(h.getLevel());
        h.assertTrue(e!=null && !e.shouldBeSaved() && !e.isPickable() && !e.isPushable(),"Temporary effect acquired item/persistence behavior");
        h.assertFalse(e.save(new net.minecraft.nbt.CompoundTag()),"Entity was written to persistent storage");
        h.succeed();
    }


    // Real match bootstrap through joinBattle; reflection is confined to test fixtures so the release
    // mod does not gain an admin/test bypass API. Fake players provide harmless packet sinks.
    private static final class Fixture implements AutoCloseable {
        final net.minecraft.gametest.framework.GameTestHelper h;
        final java.util.List<net.minecraft.server.level.ServerPlayer> players=new java.util.ArrayList<>();
        final String arenaId="medical-test-"+UUID.randomUUID();
        Fixture(GameTestHelper h) {
            this.h=h;
            try {
                var configField=top.mores.battlefield.game.BattlefieldGameManager.class.getDeclaredField("config"); configField.setAccessible(true);
                var config=(top.mores.battlefield.config.SectorConfigLoader.SectorConfig)configField.get(null);
                var at=h.absolutePos(new net.minecraft.core.BlockPos(1,2,1));
                var pos=new top.mores.battlefield.config.SectorConfigLoader.Position(h.getLevel().dimension().location().toString(),at.getX()+0.5,at.getY(),at.getZ()+0.5);
                config.arenas.put(arenaId,new top.mores.battlefield.config.SectorConfigLoader.ArenaConfig(arenaId,
                        top.mores.battlefield.config.SectorConfigLoader.Mode.STATIC,h.getLevel().dimension().location().toString(),null,null,
                        1,0,null,"breakthrough",20,300,0,16,8,8,1,pos,pos,pos,pos,java.util.List.of(),java.util.List.of(),java.util.List.of()));
            } catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
        }
        @SuppressWarnings("unchecked")
        net.minecraft.server.level.ServerPlayer player(MedicalState.Role role, double x) {
            var p=new net.minecraftforge.common.util.FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"medical-fixture")) {
                @Override public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source) { return false; }
            };
            try {
                var list=p.server.getPlayerList();
                var playerField=net.minecraft.server.players.PlayerList.class.getDeclaredField("players");
                playerField.setAccessible(true);
                ((java.util.List<net.minecraft.server.level.ServerPlayer>)playerField.get(list)).add(p);
                java.lang.reflect.Field field=null;
                for(Class<?> c=list.getClass();c!=null;c=c.getSuperclass()) {
                    try { field=c.getDeclaredField("playersByUUID"); break; } catch(NoSuchFieldException ignored) {}
                }
                if(field==null) throw new IllegalStateException("PlayerList UUID index missing");
                field.setAccessible(true); ((java.util.Map<UUID,net.minecraft.server.level.ServerPlayer>)field.get(list)).put(p.getUUID(),p);
                players.add(p);
                top.mores.battlefield.game.BattlefieldGameManager.joinBattle(p,arenaId);
                var find=top.mores.battlefield.game.BattlefieldGameManager.class.getDeclaredMethod("findContext",UUID.class); find.setAccessible(true);
                Object ctx=find.invoke(null,p.getUUID());
                var phase=ctx.getClass().getDeclaredField("phase"); phase.setAccessible(true); phase.set(ctx,top.mores.battlefield.game.BattlefieldGameManager.Phase.RUNNING);
                var session=ctx.getClass().getDeclaredField("session"); session.setAccessible(true); session.set(ctx,null);
                top.mores.battlefield.team.TeamManager.setTeam(p,top.mores.battlefield.team.TeamId.ATTACKERS);
                MedicalPouchService.deployed(p,role);
                var pos=h.absolutePos(new net.minecraft.core.BlockPos(1,2,1)); p.setPos(pos.getX()+x,pos.getY(),pos.getZ()+0.5);
                return p;
            } catch(ReflectiveOperationException ex) { throw new RuntimeException(ex); }
        }
        @Override public void close() {
            for(var p:players) {
                top.mores.battlefield.game.BattlefieldGameManager.leaveBattle(p);
                try {
                    var field=net.minecraft.server.players.PlayerList.class.getDeclaredField("players"); field.setAccessible(true);
                    ((java.util.List<?>)field.get(p.server.getPlayerList())).remove(p);
                    field=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID"); field.setAccessible(true);
                    ((java.util.Map<?,?>)field.get(p.server.getPlayerList())).remove(p.getUUID());
                } catch(ReflectiveOperationException ex) { throw new RuntimeException(ex); }
            }
        }
    }
    @GameTest(template="medical_empty",timeoutTicks=120)
    public static void serviceDeadlineAndCeilingSettlement(GameTestHelper h) {
        var f=new Fixture(h); var p=f.player(MedicalState.Role.ASSAULT,1.5);
        p.setHealth(p.getMaxHealth()); MedicalPouchService.use(p);
        h.assertTrue(MedicalPouchService.state(p).action()==null,"Full health started unnecessary treatment");
        p.setHealth(5); top.mores.battlefield.game.combat.CombatRegenService.setRegenCeiling(p,10);
        MedicalPouchService.use(p); var a=MedicalPouchService.state(p).action();
        h.assertTrue(a!=null,"Real service refused eligible treatment");
        MedicalPouchService.use(p); h.assertTrue(a==MedicalPouchService.state(p).action(),"Repeated use replaced active attempt");
        h.runAfterDelay(13,()->h.assertTrue(p.getHealth()==5 && MedicalPouchService.state(p).reserve()==1,"Treatment occurred before 14 ticks"));
        h.runAfterDelay(16,()->{
            // 动作截止后不再一次加满：只消耗医疗包并开始逐步回血
            h.assertTrue(p.getHealth()>5 && p.getHealth()<p.getMaxHealth(),"Settlement should start a gradual heal, not a one-shot full heal");
            h.assertTrue(MedicalPouchService.state(p).reserve()==0 && !MedicalPouchService.locked(p),"Service did not consume/unlock once");
        });
        h.runAfterDelay(60,()->{
            h.assertTrue(p.getHealth()==p.getMaxHealth(),"Gradual heal did not finish inside the configured window");
            h.assertTrue(top.mores.battlefield.game.combat.CombatRegenService.regenCeiling(p)==p.getMaxHealth(),"Medical did not clear dynamic ceiling");
            f.close(); h.succeed();
        });
    }
    @GameTest(template="medical_empty",timeoutTicks=60)
    public static void slotChangeCancelsAndReconnectDoesNotRefill(GameTestHelper h) {
        var f=new Fixture(h); var p=f.player(MedicalState.Role.SUPPORT,1.5); p.setHealth(5);
        MedicalPouchService.use(p); p.getInventory().selected=1;
        h.runAfterDelay(2,()->{
            h.assertTrue(!MedicalPouchService.locked(p) && p.getHealth()==5 && MedicalPouchService.state(p).reserve()==1,"Slot change did not cancel cleanly");
            var s=MedicalPouchService.state(p); var a=s.begin(p.server.getTickCount(),1,true); s.complete(a,p.server.getTickCount()+1,true,true);
            var life=s.life;
            top.mores.battlefield.game.BattlefieldGameManager.onLogout(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(p));
            top.mores.battlefield.game.BattlefieldGameManager.onLoggedIn(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(p));
            MedicalPouchService.dimension(new net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent(p,h.getLevel().dimension(),net.minecraft.world.level.Level.NETHER));
            h.assertTrue(s.reserve()==0 && s.life.equals(life),"Reconnect/dimension event refreshed reserve or life");
            f.close(); h.succeed();
        });
    }
    @GameTest(template="medical_empty",timeoutTicks=60)
    public static void deliveryReplenishesOnlyReserveOnce(GameTestHelper h) {
        var f=new Fixture(h); var medic=f.player(MedicalState.Role.MEDIC,1.5); var target=f.player(MedicalState.Role.RECON,5.5);
        var s=MedicalPouchService.state(target); var a=s.begin(0,1,true); s.complete(a,1,true,true);
        float health=target.getHealth();
        var from=medic.getBoundingBox().getCenter();
        var first=new top.mores.battlefield.server.entity.MedicalPouchEntity(h.getLevel(),medic,target,from);
        var second=new top.mores.battlefield.server.entity.MedicalPouchEntity(h.getLevel(),medic,target,from);
        h.getLevel().addFreshEntity(first); h.getLevel().addFreshEntity(second);
        h.runAfterDelay(15,()->{
            h.assertTrue(s.reserve()==1 && first.isRemoved() && second.isRemoved(),"Competing delivery did not settle exactly once");
            h.assertTrue(target.getHealth()==health,"Receiving supply changed health");
            f.close(); h.succeed();
        });
    }

    private static void emptyReserve(net.minecraft.server.level.ServerPlayer p) {
        var s=MedicalPouchService.state(p); var a=s.begin(0,1,true); s.complete(a,1,true,true);
    }
    @GameTest(template="medical_empty")
    public static void sweptThinWallAndActualSlabShape(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var medic=f.player(MedicalState.Role.MEDIC,1.5); var target=f.player(MedicalState.Role.SUPPORT,5.5); emptyReserve(target);
            var cfg=top.mores.battlefield.config.BattlefieldServerConfig.get(); var before=cfg.medical;
            cfg.medical=new top.mores.battlefield.config.MedicalConfig(14,20,12,2,40,20,12,40);
            try {
                var obstacle=new net.minecraft.core.BlockPos(2,2,1);
                h.setBlock(obstacle,net.minecraft.world.level.block.Blocks.GLASS_PANE);
                var start=medic.getBoundingBox().getCenter(); var end=target.getBoundingBox().getCenter();
                h.assertFalse(MedicalPouchService.clearPath(medic,start,end),"Initial sight crossed glass pane");
                var e=new top.mores.battlefield.server.entity.MedicalPouchEntity(h.getLevel(),medic,target,start);
                e.tick();
                h.assertTrue(e.isRemoved() && MedicalPouchService.state(target).reserve()==0,"Fast swept segment tunneled through a thin obstacle");
                h.setBlock(obstacle,net.minecraft.world.level.block.Blocks.OAK_SLAB);
                h.assertTrue(MedicalPouchService.clearPath(medic,start,end),"Bottom slab was treated as a full cube");
                var open=new top.mores.battlefield.server.entity.MedicalPouchEntity(h.getLevel(),medic,target,start);
                open.tick(); open.tick(); open.tick();
                h.assertTrue(MedicalPouchService.state(target).reserve()==1 && open.isRemoved(),"Delivery failed through the actual empty half of a slab");
            } finally { cfg.medical=before; }
        }
        h.succeed();
    }
    @GameTest(template="medical_empty")
    public static void targetingAndSuccessfulLaunchCooldown(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var medic=f.player(MedicalState.Role.MEDIC,1.5); var target=f.player(MedicalState.Role.RECON,5.5); emptyReserve(target);
            java.util.function.Supplier<Long> flights=()->h.getLevel().getEntitiesOfClass(
                    top.mores.battlefield.server.entity.MedicalPouchEntity.class,medic.getBoundingBox().inflate(20)).stream().filter(e->e.involves(medic.getUUID())).count();
            medic.setYRot(0); medic.setXRot(0); MedicalPouchService.distribute(medic);
            h.assertTrue(flights.get()==0,"Off-axis target generated delivery");
            medic.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,target.getBoundingBox().getCenter());
            MedicalPouchService.distribute(medic);
            h.assertTrue(flights.get()==1,"Rejected request incorrectly consumed cooldown or valid target was missed");
            MedicalPouchService.distribute(medic); h.assertTrue(flights.get()==1,"A medic created concurrent in-flight packages");
            for(var e:h.getLevel().getEntitiesOfClass(top.mores.battlefield.server.entity.MedicalPouchEntity.class,medic.getBoundingBox().inflate(20)))
                if(e.involves(medic.getUUID())) e.discard();
            MedicalPouchService.distribute(medic); h.assertTrue(flights.get()==0,"Failed flight refunded successful-launch cooldown");
        }
        h.succeed();
    }
    @GameTest(template="medical_empty")
    public static void oldDeploymentCannotReceiveAndEnemiesCannotReceive(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var medic=f.player(MedicalState.Role.MEDIC,1.5); var target=f.player(MedicalState.Role.RECON,5.5); emptyReserve(target);
            var e=new top.mores.battlefield.server.entity.MedicalPouchEntity(h.getLevel(),medic,target,medic.getBoundingBox().getCenter());
            MedicalPouchService.deployed(target,MedicalState.Role.SUPPORT); emptyReserve(target); e.tick();
            h.assertTrue(e.isRemoved() && MedicalPouchService.state(target).reserve()==0,"Old-life delivery supplied a newly deployed player");
            top.mores.battlefield.team.TeamManager.setTeam(target,top.mores.battlefield.team.TeamId.DEFENDERS);
            MedicalPouchService.deployed(target,MedicalState.Role.RECON); emptyReserve(target);
            h.assertFalse(MedicalPouchService.receiver(medic,target),"Enemy could receive supply");
            MedicalPouchService.deployed(target,MedicalState.Role.MEDIC);
            h.assertFalse(MedicalPouchService.receiver(medic,target),"Medic could receive supply");
        }
        h.succeed();
    }

    @GameTest(template="medical_empty")
    public static void reloadAndOtherHealingAreRecheckedByService(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var p=f.player(MedicalState.Role.ASSAULT,1.5); p.setHealth(5);
            var op=IGunOperator.fromLivingEntity(p); var data=op.getDataHolder();
            // Authoritative holder changes before its next replicated snapshot: C in this same tick must fail.
            data.reloadStateType=com.tacz.guns.api.entity.ReloadState.StateType.TACTICAL_RELOAD_FEEDING;
            MedicalPouchService.use(p);
            h.assertTrue(!MedicalPouchService.locked(p) && data.reloadStateType==com.tacz.guns.api.entity.ReloadState.StateType.TACTICAL_RELOAD_FEEDING,
                    "Medical started during authoritative reload or forcibly canceled reload");
            data.reloadStateType=com.tacz.guns.api.entity.ReloadState.StateType.NOT_RELOADING;
            MedicalPouchService.use(p); h.assertTrue(MedicalPouchService.locked(p),"Medical did not recover after reload ended");
            top.mores.battlefield.game.combat.PlayerHealthService.healPlayer(p,p.getMaxHealth(),true);
            // Final no-waste rule uses exactly the live service predicate.
            var state=MedicalPouchService.state(p); var a=state.action();
            h.assertFalse(state.complete(a,a.end(),MedicalPouchService.eligible(p),MedicalPouchService.needsCare(p)),"Other completed healing still consumed the pouch");
            h.assertTrue(state.reserve()==1,"Other healing wasted reserve");
        }
        h.succeed();
    }
}
