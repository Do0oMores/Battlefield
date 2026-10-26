package top.mores.battlefield.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.client.input.AimKey;
import com.tacz.guns.client.input.ReloadKey;
import com.tacz.guns.client.input.ShootKey;
import com.tacz.guns.client.input.MeleeKey;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.C2SMedicalRequest;
import top.mores.battlefield.net.S2CMedicalState;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, value = Dist.CLIENT)
public final class MedicalClient {
    private MedicalClient() {}
    private static UUID life;
    private static long revision = -1, actionId;
    private static int age, duration;
    private static boolean deployed, medic;
    private static int reserve;
    private static volatile boolean using;
    // Independent from the authoritative action. Only a hardware release rearms shooting.
    private static final top.mores.battlefield.game.medical.FireReleaseGate FIRE = new top.mores.battlefield.game.medical.FireReleaseGate();
    private static boolean conflictShown;
    private static net.minecraft.client.multiplayer.ClientLevel world;

    // Public temporary input fields verified in the installed 1.1.8-hotfix JAR. They do not exist in
    // 1.1.7-hotfix, which has no charging feature; cache that distinction once, never probe each tick.
    private static final java.lang.reflect.Field CHARGE_PROGRESS = chargeField("chargeProgress");
    private static final java.lang.reflect.Field IS_CHARGING = chargeField("isCharging");
    private static java.lang.reflect.Field chargeField(String name) {
        try { return com.tacz.guns.client.gameplay.LocalPlayerDataHolder.class.getField(name); }
        catch (NoSuchFieldException legacyWithoutCharging) { return null; }
    }
    public static void clearCharge() {
        var player = Minecraft.getInstance().player;
        if (player == null || CHARGE_PROGRESS == null || IS_CHARGING == null) return;
        var data = IClientPlayerGunOperator.fromLocalPlayer(player).getDataHolder();
        try { CHARGE_PROGRESS.setFloat(data,0); IS_CHARGING.setBoolean(data,false); }
        catch (IllegalAccessException incompatible) { throw new IllegalStateException("TACZ charge fields changed",incompatible); }
    }

    public static boolean active() { return using; }
    public static boolean blockShoot() { return FIRE.blocked(using); }
    public static boolean deployed() { return deployed; }
    public static boolean medic() { return medic; }
    public static int reserve() { return reserve; }

    public static void accept(S2CMedicalState p) {
        // TCP preserves life ordering; within each life a revision suppresses stale attempts/cancels.
        if (p.revision() < revision) return;
        world = Minecraft.getInstance().level;
        if (p.life().equals(life) && p.revision() == revision && p.actionId() == actionId) {
            deployed = p.deployed(); medic = p.medic(); reserve = p.reserve(); return;
        }
        life = p.life(); revision = p.revision(); deployed = p.deployed(); medic = p.medic(); reserve = p.reserve();
        if (p.actionId() != 0 && p.actionId() != actionId) {
            FIRE.started();
            clearCharge();
            var mc = Minecraft.getInstance();
            if (mc.player != null) IClientPlayerGunOperator.fromLocalPlayer(mc.player).aim(false);
        }
        actionId = p.actionId(); duration = Math.max(1,p.duration()); age = Math.max(0,p.elapsed());
        using = deployed && actionId != 0;
    }
    public static void reset() {
        life = null; revision = -1; actionId = 0; age = 0; duration = 1;
        deployed = medic = using = false; reserve = 0;
        // If still held across a world transition, retain only the release gate.
    }
    @SubscribeEvent public static void unload(net.minecraftforge.event.level.LevelEvent.Unload e) {
        if (e.getLevel().isClientSide()) { reset(); world = null; }
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e) {
        reset(); FIRE.disconnect(); world = null; conflictShown = false;
    }
    public static boolean physicallyDown(KeyMapping mapping) {
        var key = mapping.getKey(); long window = Minecraft.getInstance().getWindow().getWindow();
        if (key.getType() == InputConstants.Type.MOUSE) return GLFW.glfwGetMouseButton(window,key.getValue()) == GLFW.GLFW_PRESS;
        if (key.getValue() < 0) return false;
        if (key.getType() == InputConstants.Type.KEYSYM) return InputConstants.isKeyDown(window,key.getValue());
        for (int code = GLFW.GLFW_KEY_SPACE; code <= GLFW.GLFW_KEY_LAST; code++)
            if (GLFW.glfwGetKeyScancode(code) == key.getValue()) return InputConstants.isKeyDown(window, code);
        return false;
    }
    private static void intent(KeyMapping binding, boolean give) {
        var mc = Minecraft.getInstance();
        if (mc.screen == null && mc.player != null && mc.player.isAlive() && deployed
                && life != null && binding.isConflictContextAndModifierActive())
            BattlefieldNet.sendToServer(new C2SMedicalRequest(life,revision,give));
    }
    // Input edges capture presses shorter than a client tick. GLFW_REPEAT never submits a new intention.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void key(net.minecraftforge.client.event.InputEvent.Key e) {
        if (e.getAction() != GLFW.GLFW_PRESS) return;
        if (BFKeyBinds.SELF_HEAL.matches(e.getKey(),e.getScanCode())) intent(BFKeyBinds.SELF_HEAL,false);
        if (BFKeyBinds.GIVE_MEDICAL.matches(e.getKey(),e.getScanCode())) intent(BFKeyBinds.GIVE_MEDICAL,true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void mouse(net.minecraftforge.client.event.InputEvent.MouseButton.Pre e) {
        if (e.getAction() != GLFW.GLFW_PRESS) return;
        if (BFKeyBinds.SELF_HEAL.matchesMouse(e.getButton())) intent(BFKeyBinds.SELF_HEAL,false);
        if (BFKeyBinds.GIVE_MEDICAL.matchesMouse(e.getButton())) intent(BFKeyBinds.GIVE_MEDICAL,true);
    }
    private static void drain(KeyMapping key) { key.setDown(false); while (key.consumeClick()) {} }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void tick(TickEvent.ClientTickEvent e) {
        var mc = Minecraft.getInstance();
        if (world != mc.level) { reset(); world = mc.level; }
        if (mc.player == null || mc.level == null) return;
        if (e.phase == TickEvent.Phase.START) {
            boolean fire = physicallyDown(ShootKey.SHOOT_KEY) || physicallyDown(mc.options.keyAttack);
            FIRE.observe(fire);
            if (using) {
                FIRE.started();
                IClientPlayerGunOperator.fromLocalPlayer(mc.player).aim(false);
                drain(AimKey.AIM_KEY); drain(ReloadKey.RELOAD_KEY); drain(MeleeKey.MELEE_KEY);
                drain(mc.options.keyUse);
            }
            if (blockShoot()) { clearCharge(); drain(ShootKey.SHOOT_KEY); drain(mc.options.keyAttack); }
            while (BFKeyBinds.SELF_HEAL.consumeClick()) {}
            while (BFKeyBinds.GIVE_MEDICAL.consumeClick()) {}
            if (!conflictShown && deployed) {
                conflictShown = true;
                for (KeyMapping other : mc.options.keyMappings) {
                    if (other != BFKeyBinds.SELF_HEAL && other.getKey().equals(BFKeyBinds.SELF_HEAL.getKey())) {
                        mc.player.displayClientMessage(Component.translatable("message.battlefield.medical_key_conflict",
                                Component.translatable(other.getName())), false);
                    }
                }
            }
        } else if (using) age++;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interaction(net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered e) {
        if ((e.isAttack() && blockShoot()) || (e.isUseItem() && using)) {
            e.setCanceled(true); e.setSwingHand(false);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void hand(RenderHandEvent e) {
        var mc = Minecraft.getInstance();
        if (!using || mc.player == null || !mc.player.isAlive() || !mc.options.getCameraType().isFirstPerson()) return;
        // Cancel both vanilla/TACZ hands. Draw exactly one skin arm and pouch on the main-hand pass.
        e.setCanceled(true);
        if (e.getHand() != InteractionHand.MAIN_HAND || mc.screen != null || mc.options.hideGui) return;
        float t = Math.max(0, Math.min(1, (age + e.getPartialTick()) / duration));
        float raise = smooth(Math.min(1, t / 0.30F)) * smooth(Math.min(1, (1-t) / 0.30F));
        int side = mc.player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        PoseStack pose = e.getPoseStack();
        pose.pushPose();
        try {
            pose.translate(0, -1.05F * (1-raise), 0);
            pose.pushPose();
            try {
                // Vanilla first-person arm basis, with no item/equipment mutation.
                pose.translate(side * 0.50F, -0.58F, -0.72F);
                pose.mulPose(Axis.YP.rotationDegrees(side * 45));
                pose.translate(side * -1.0F, 3.6F, 3.5F);
                pose.mulPose(Axis.ZP.rotationDegrees(side * 120));
                pose.mulPose(Axis.XP.rotationDegrees(200));
                pose.mulPose(Axis.YP.rotationDegrees(side * -135));
                pose.translate(side * 5.6F, 0, 0);
                var renderer = (PlayerRenderer)mc.getEntityRenderDispatcher().getRenderer(mc.player);
                if (side == 1) renderer.renderRightHand(pose,e.getMultiBufferSource(),e.getPackedLight(),mc.player);
                else renderer.renderLeftHand(pose,e.getMultiBufferSource(),e.getPackedLight(),mc.player);
            } finally { pose.popPose(); }
            pose.translate(side * 0.31F, -0.18F, -0.88F);
            pose.scale(0.70F,0.70F,0.70F);
            pose.mulPose(Axis.XP.rotationDegrees(-8));
            MedicalPouchModel.render(pose,e.getMultiBufferSource(),e.getPackedLight());
        } finally { pose.popPose(); }
    }
    private static float smooth(float t) { return t*t*(3-2*t); }
}
