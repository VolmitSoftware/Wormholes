package art.arcane.wormholes.modded.client.menu;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MenuComponentsTest {
    private static final ToIntFunction<String> SIX_PER_CHAR = text -> text.length() * 6;

    @Test
    public void sectionCodesBecomeStyledComponents() {
        Component component = MenuComponents.component("§aHi §lBold");
        assertEquals("Hi Bold", component.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GREEN), component.getSiblings().getFirst().getStyle().getColor());
        assertTrue(component.getSiblings().get(1).getStyle().isBold());
    }

    @Test
    public void ampersandCodesStayLiteralText() {
        Component component = MenuComponents.component("&cRed");
        assertEquals("&cRed", component.getString());
        assertNull(component.getStyle().getColor());
        for (Component sibling : component.getSiblings()) {
            assertNull(sibling.getStyle().getColor());
        }
    }

    @Test
    public void buttonFacesDropTheServerColours() {
        Component styled = MenuComponents.component("§9Orientation §c§lNow");
        Component face = MenuComponents.face(styled);
        assertEquals("Orientation Now", face.getString());
        assertEquals(List.of(), colours(face));
        assertEquals(2, colours(styled).size());
    }

    @Test
    public void plainTextStripsEveryCode() {
        assertEquals("Hi Bold", MenuComponents.plain("§aHi §lBold"));
    }

    @Test
    public void descriptionLinesJoinIntoOneTooltip() {
        Component tooltip = MenuComponents.lines(List.of("§7First", "Second"));
        assertEquals("First\nSecond", tooltip.getString());
        assertEquals("", MenuComponents.lines(List.of()).getString());
    }

    @Test
    public void clipFrontKeepsTheEndOfLongText() {
        assertEquals("...Gravity", MenuComponents.clipFront("Settings > Transit > Gravity", 60, SIX_PER_CHAR));
        assertEquals("short", MenuComponents.clipFront("short", 100, SIX_PER_CHAR));
        assertEquals("...", MenuComponents.clipFront("anything", 10, SIX_PER_CHAR));
    }

    private static List<TextColor> colours(Component component) {
        List<TextColor> colours = new ArrayList<>();
        component.visit((style, text) -> {
            if (style.getColor() != null) {
                colours.add(style.getColor());
            }
            return Optional.empty();
        }, Style.EMPTY);
        return colours;
    }
}
