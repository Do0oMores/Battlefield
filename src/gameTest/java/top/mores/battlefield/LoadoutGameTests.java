package top.mores.battlefield;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.util.AllowAttachmentTagMatcher;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.*;
import top.mores.battlefield.loadout.*;
import top.mores.battlefield.loadout.tacz.*;
import top.mores.battlefield.protocol.*;
import java.util.*;

/** Real registry/resource tests; no fake NBT and no forceBuild. Not packaged in the release mod. */
@GameTestHolder(Battlefield.MODID)
@PrefixGameTestTemplate(false)
public class LoadoutGameTests {
    @GameTest(template="medical_empty") public static void preMatchInventorySurvivesEntityReplacementAndIsRestoredOnce(GameTestHelper h) {
        UUID id=UUID.randomUUID(); var profile=new com.mojang.authlib.GameProfile(id,"loadout-restore");
        var original=new net.minecraftforge.common.util.FakePlayer(h.getLevel(),profile);
        original.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,7));
        original.getInventory().setItem(36,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_BOOTS));
        original.getInventory().setItem(40,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH,3));
        top.mores.battlefield.game.PlayerSnapshotStore.captureAndClear(original);
        original.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK));
        top.mores.battlefield.game.PlayerSnapshotStore.captureAndClear(original); // cannot overwrite the first snapshot
        var replacement=new net.minecraftforge.common.util.FakePlayer(h.getLevel(),profile);
        top.mores.battlefield.game.PlayerSnapshotStore.restore(replacement);
        h.assertTrue(replacement.getInventory().getItem(0).is(net.minecraft.world.item.Items.DIAMOND) && replacement.getInventory().getItem(0).getCount()==7,"Original inventory lost across entity replacement");
        h.assertTrue(replacement.getInventory().getItem(36).is(net.minecraft.world.item.Items.IRON_BOOTS) && replacement.getInventory().getItem(40).getCount()==3,"Armor/offhand not restored");
        h.assertTrue(top.mores.battlefield.game.PlayerSnapshotStore.restore(replacement)==null,"Snapshot restored twice");
        h.assertTrue(replacement.getInventory().getItem(0).getCount()==7,"Repeated restore duplicated items"); h.succeed();
    }
    private static LoadoutDto.Equipment gun(Map<String,LoadoutDto.Attachment> attachments) {
        return new LoadoutDto.Equipment(new LoadoutDto.Weapon("battlefield:ak47","TACZ_GUN","tacz:ak47",true),0,attachments);
    }
    @GameTest(template="medical_empty") public static void realGunAndExactAttachmentBuild(GameTestHelper h) {
        var id=new ResourceLocation("tacz:ak47"); h.assertTrue(TimelessAPI.getCommonGunIndex(id).isPresent(),"Default pack ak47 absent");
        var entry=TimelessAPI.getAllCommonAttachmentIndex().stream().filter(a->a.getValue().getType()==AttachmentType.SCOPE && AllowAttachmentTagMatcher.match(id,a.getKey())).findFirst().orElseThrow();
        var selected=new LoadoutDto.Attachment("battlefield:scope",entry.getKey().toString(),"SCOPE");
        var stack=new TaczGunFactory().create(gun(Map.of("SCOPE",selected))); var api=IGun.getIGunOrNull(stack);
        h.assertTrue(api!=null && api.getGunId(stack).equals(id),"Gun builder identity mismatch");
        h.assertTrue(api.getAttachmentId(stack,AttachmentType.SCOPE).equals(entry.getKey()),"Scope not installed");
        h.succeed();
    }
    @GameTest(template="medical_empty") public static void missingGunAndInvalidAttachmentFailClosed(GameTestHelper h) {
        boolean missing=false,invalid=false;
        try { new TaczGunFactory().create(new LoadoutDto.Equipment(new LoadoutDto.Weapon("battlefield:bad","TACZ_GUN","battlefield:missing",true),0,Map.of())); } catch(IllegalArgumentException expected) { missing=true; }
        try { new TaczGunFactory().create(gun(Map.of("SCOPE",new LoadoutDto.Attachment("battlefield:missing","battlefield:missing","SCOPE")))); } catch(IllegalArgumentException expected) { invalid=true; }
        h.assertTrue(missing && invalid,"Missing resources must never construct a usable kit"); h.succeed();
    }
    @GameTest(template="medical_empty") public static void providersAmmoAndResourceReload(GameTestHelper h) {
        var settings=new DeploymentSettings(true,3,1000,DeploymentEquipmentLayout.defaults()); var factory=new EquipmentFactory(settings);
        Map<EquipmentSlot,LoadoutDto.Equipment> slots=new EnumMap<>(EquipmentSlot.class);
        for(var slot:EquipmentSlot.values()) slots.put(slot,new LoadoutDto.Equipment(new LoadoutDto.Weapon("battlefield:"+slot.name().toLowerCase(Locale.ROOT),"MINECRAFT_ITEM","minecraft:stick",false),0,Map.of()));
        slots.put(EquipmentSlot.PRIMARY,gun(Map.of()));
        var description=new LoadoutDto(UUID.randomUUID(),SoldierClass.ASSAULT,0,1,1,System.currentTimeMillis(),1,slots);
        var staged=factory.create(description); h.assertTrue(staged.size()>6,"No reserve ammo constructed");
        var gunApi=IGun.getIGunOrNull(staged.get(0)); h.assertTrue(gunApi.getCurrentAmmoCount(staged.get(0))>0,"Magazine not filled");
        var validator=new TaczResourceValidator(h.getLevel().getServer(),factory); validator.refresh(); h.assertTrue(validator.ready(),"Resource index unavailable");
        validator.invalidate(); h.assertFalse(validator.ready(),"Reload retained old verified resources"); validator.refresh(); h.assertTrue(validator.ready(),"Reload did not rebuild snapshot"); validator.close();
        boolean unknown=false;
        try { factory.createEquipment(new LoadoutDto.Equipment(new LoadoutDto.Weapon("battlefield:bad","BFEXAMPLE","example:bad",false),0,Map.of())); } catch(IllegalArgumentException expected) { unknown=true; }
        h.assertTrue(unknown,"Unknown provider must reject"); h.succeed();
    }
}
