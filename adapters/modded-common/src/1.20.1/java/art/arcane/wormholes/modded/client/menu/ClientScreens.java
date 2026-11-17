package art.arcane.wormholes.modded.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class ClientScreens {
    private ClientScreens() {
    }

    public static void open(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }

    public static Screen current() {
        return Minecraft.getInstance().screen;
    }
}
