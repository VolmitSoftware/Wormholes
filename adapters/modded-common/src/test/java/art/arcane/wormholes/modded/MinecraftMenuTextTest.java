package art.arcane.wormholes.modded;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MinecraftMenuTextTest {
    @Test
    public void templateFormattingDoesNotInterpretPlayerProvidedFormatting() {
        Component component = MinecraftMenuText.format("&a&lPortal {name}&r done", Map.of("name", "&c{name}"));
        assertEquals("Portal &c{name} done", component.getString());
        AtomicBoolean argumentFound = new AtomicBoolean();
        component.visit((style, text) -> {
            if (text.contains("&c{name}")) {
                argumentFound.set(true);
                assertTrue(style.isBold());
                assertEquals(0x55FF55, style.getColor().getValue());
            }
            if (text.contains("done")) {
                assertFalse(style.isBold());
                assertFalse(style.isItalic());
            }
            return Optional.empty();
        }, Style.EMPTY);
        assertTrue(argumentFound.get());
    }
}
