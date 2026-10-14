package art.arcane.automator;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;

public final class ClientGui {
    private ClientGui() {
    }

    public static Screen screen(Minecraft client) {
        return client.gui.screen();
    }

    public static void setScreen(Minecraft client, Screen screen) {
        client.gui.setScreen(screen);
    }

    public static boolean hudHidden(Minecraft client) {
        return client.gui.hud.isHidden();
    }

    public static void toggleHud(Minecraft client) {
        client.gui.hud.toggle();
    }

    public static ChatComponent chat(Minecraft client) {
        return client.gui.hud.getChat();
    }

    public static RenderTarget mainTarget(Minecraft client) {
        return client.gameRenderer.mainRenderTarget();
    }
}
