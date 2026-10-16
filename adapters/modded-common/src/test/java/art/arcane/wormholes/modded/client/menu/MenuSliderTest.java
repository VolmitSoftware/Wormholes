package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.NumberElement;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MenuSliderTest {
    private static final KeyEvent ENTER = new KeyEvent(InputConstants.KEY_RETURN, 0, 0);
    private static final KeyEvent RIGHT = new KeyEvent(InputConstants.KEY_RIGHT, 0, 0);

    @Test
    public void enterWithoutAChangeSubmitsNothing() {
        List<Double> submitted = new ArrayList<>();
        MenuSlider slider = new MenuSlider(range(24.0D), Component.literal("Range"), submitted::add);
        assertTrue(slider.keyPressed(ENTER));
        assertEquals(List.of(), submitted);
    }

    @Test
    public void enterAfterAStepSubmitsTheNewValue() {
        List<Double> submitted = new ArrayList<>();
        MenuSlider slider = new MenuSlider(range(24.0D), Component.literal("Range"), submitted::add);
        assertTrue(slider.keyPressed(RIGHT));
        assertTrue(slider.keyPressed(ENTER));
        assertEquals(List.of(25.0D), submitted);
    }

    private static NumberElement range(double value) {
        MenuElementCommon common = MenuElementCommon.builder("range").label("Range").interactions(Set.of(MenuInteraction.SET_VALUE)).build();
        return new NumberElement(common, value, 0.0D, 64.0D, 1.0D, 8.0D, (int) value + " blocks");
    }
}
