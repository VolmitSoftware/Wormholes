package art.arcane.wormholes.modded.client.render;

import org.joml.Vector4f;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PortalClipScopeTest {
    @Test
    public void shaderSwitchRestoresHostileSlotStateAndPreservesPackSlots() {
        States states = new States();
        states.enabled(2, true);
        states.enabled(5, true);
        try (PortalClipScope scope = PortalClipScope.open(new Vector4f(1, 0, 0, 0), states)) {
            scope.select(0, Set.of());
            assertTrue(states.enabled(0));
            scope.select(2, Set.of(5));
            assertFalse(states.enabled(0));
            assertTrue(states.enabled(2));
            assertTrue(states.enabled(5));
            scope.select(-1, Set.of());
            assertFalse(states.enabled(2));
            assertFalse(states.enabled(5));
        }
        assertFalse(states.enabled(0));
        assertTrue(states.enabled(2));
        assertTrue(states.enabled(5));
        assertNull(PortalClipScope.current());
    }

    @Test
    public void nestedViewsSuspendOuterPlaneAndRestoreItAfterFailure() {
        States states = new States();
        try (PortalClipScope outer = PortalClipScope.open(new Vector4f(1, 0, 0, 0), states)) {
            outer.select(0, Set.of());
            assertThrows(IllegalStateException.class, () -> {
                try (PortalClipScope inner = PortalClipScope.open(new Vector4f(-1, 0, 0, 0), states)) {
                    assertFalse(states.enabled(0));
                    inner.select(1, Set.of());
                    assertTrue(states.enabled(1));
                    throw new IllegalStateException("Failed destination geometry");
                }
            });
            assertSame(outer, PortalClipScope.current());
            assertTrue(states.enabled(0));
            assertFalse(states.enabled(1));
        }
        assertFalse(states.enabled(0));
        assertFalse(states.enabled(1));
        assertNull(PortalClipScope.current());
    }

    @Test
    public void repeatedProgramBindingRepairsExternalDisableWithoutResnapshotting() {
        States states = new States();
        try (PortalClipScope scope = PortalClipScope.open(new Vector4f(0, 0, -1, 2), states)) {
            scope.select(0, Set.of());
            states.enabled(0, false);
            scope.select(0, Set.of());
            assertTrue(states.enabled(0));
        }
        assertFalse(states.enabled(0));
    }

    private static final class States implements PortalClipScope.Bindings {
        private final Map<Integer, Boolean> enabled = new HashMap<>();

        @Override
        public int maximum() {
            return 8;
        }

        @Override
        public boolean enabled(int distance) {
            return enabled.getOrDefault(distance, false);
        }

        @Override
        public void enabled(int distance, boolean value) {
            enabled.put(distance, value);
        }
    }
}
