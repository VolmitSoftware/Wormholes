package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;

public final class ClientGui {
    private ClientGui() {
    }

    public static boolean hudHidden(Minecraft minecraft) {
        return minecraft.gui.hud.isHidden();
    }

    public static void toggleHud(Minecraft minecraft) {
        minecraft.gui.hud.toggle();
    }

    public static Screen screen(Minecraft minecraft) {
        return minecraft.gui.screen();
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.gui.setScreen(screen);
    }

    public static Overlay overlay(Minecraft minecraft) {
        return minecraft.gui.overlay();
    }

    public static void clearToasts(Minecraft minecraft) {
        minecraft.gui.toastManager().clear();
    }

    public static boolean bossFog(Minecraft minecraft) {
        return minecraft.gui.hud.getBossOverlay().shouldCreateWorldFog();
    }
}
