package top.mores.battlefield.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public final class BFKeyBinds {
    private BFKeyBinds() {
    }

    public static final String CATEGORY = "key.categories.battlefield";

    public static final KeyMapping OPEN_RESPAWN_UI = new KeyMapping(
            "key.battlefield.open_respawn_ui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U,
            CATEGORY
    );

    public static final KeyMapping SELF_HEAL = new KeyMapping("key.battlefield.self_heal",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);
    public static final KeyMapping GIVE_MEDICAL = new KeyMapping("key.battlefield.give_medical",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY);

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(SELF_HEAL);
        event.register(GIVE_MEDICAL);
        event.register(OPEN_RESPAWN_UI);
    }
}
