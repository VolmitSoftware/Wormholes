package art.arcane.optics.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

final class StatePropertiesTest {
    @Test
    void propertiesAreAnImmutableSortedCopy() {
        Map<String, String> source = new HashMap<String, String>();
        source.put("half", "bottom");
        source.put("facing", "north");
        source.put("shape", "straight");
        StateProperties properties = StateProperties.of(source);
        source.put("facing", "south");
        assertEquals("north", properties.get("facing"));
        assertNull(properties.get("waterlogged"));
        assertEquals(3, properties.size());
        assertEquals(List.of("facing", "half", "shape"), List.copyOf(properties.asMap().keySet()));
        assertEquals("[facing=north,half=bottom,shape=straight]", properties.toString());
        assertThrows(UnsupportedOperationException.class, () -> properties.asMap().put("facing", "east"));
    }

    @Test
    void withReplacesOrAddsWithoutTouchingTheOriginal() {
        StateProperties properties = StateProperties.of(Map.of("facing", "north"));
        StateProperties turned = properties.with("facing", "east");
        StateProperties added = properties.with("half", "top");
        assertEquals("north", properties.get("facing"));
        assertEquals("east", turned.get("facing"));
        assertEquals("top", added.get("half"));
        assertEquals("north", added.get("facing"));
        assertSame(properties, properties.with("facing", "north"));
        assertEquals(StateProperties.of(Map.of("facing", "east")), turned);
        assertEquals(StateProperties.of(Map.of("facing", "east")).hashCode(), turned.hashCode());
        assertNotEquals(properties, turned);
        assertEquals(0, StateProperties.EMPTY.size());
        assertThrows(NullPointerException.class, () -> properties.with(null, "x"));
        assertThrows(NullPointerException.class, () -> properties.with("facing", null));
    }
}
