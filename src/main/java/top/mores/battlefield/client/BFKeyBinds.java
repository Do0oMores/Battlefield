package top.mores.battlefield.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public final class BFKeyBinds {
    private BFKeyBinds() {}

    public static final String CATEGORY = "key.categories.battlefield";

    public static final KeyMapping OPEN_RESPAWN_UI = new KeyMapping(
            "key.battlefield.open_respawn_ui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U,
            CATEGORY
    );

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_RESPAWN_UI);
    }
}
