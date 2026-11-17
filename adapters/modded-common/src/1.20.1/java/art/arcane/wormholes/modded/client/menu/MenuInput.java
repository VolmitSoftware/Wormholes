package art.arcane.wormholes.modded.client.menu;

import net.minecraft.client.gui.screens.Screen;

public record MenuInput(boolean hasShiftDown) {
    public static MenuInput current() {
        return new MenuInput(Screen.hasShiftDown());
    }
}
