package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;

public final class ClientGui {
    private ClientGui() {
    }

    public static boolean hudHidden(Minecraft minecraft) {
        return minecraft.options.hideGui;
    }

    public static void toggleHud(Minecraft minecraft) {
        minecraft.options.hideGui = !minecraft.options.hideGui;
    }

    public static Screen screen(Minecraft minecraft) {
        return minecraft.screen;
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.setScreen(screen);
    }

    public static Overlay overlay(Minecraft minecraft) {
        return minecraft.getOverlay();
    }

    public static void clearToasts(Minecraft minecraft) {
        minecraft.getToastManager().clear();
    }

    public static boolean bossFog(Minecraft minecraft) {
        return minecraft.gui.getBossOverlay().shouldCreateWorldFog();
    }
}
