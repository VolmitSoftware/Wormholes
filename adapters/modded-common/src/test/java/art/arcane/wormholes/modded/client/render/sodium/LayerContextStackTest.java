package art.arcane.wormholes.modded.client.render.sodium;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LayerContextStackTest {
    @Test
    public void enteringCapturesTheOuterStateAndExitingPutsItBack() {
        LayerContextStack<String> stack = new LayerContextStack<>();
        Renderer home = new Renderer("main lists");
        stack.enter(1, home);
        assertEquals(1, stack.slot());
        assertTrue(stack.open());
        assertSame(home, stack.top());
        home.state = "portal lists";
        stack.exit(home);
        assertEquals("main lists", home.state);
        assertEquals(0, stack.slot());
        assertFalse(stack.open());
        assertNull(stack.top());
    }

    @Test
    public void nestedLayersOnTheSameRendererRestoreInReverseOrder() {
        LayerContextStack<String> stack = new LayerContextStack<>();
        Renderer home = new Renderer("main");
        Renderer nether = new Renderer("nether idle");
        stack.enter(1, nether);
        nether.state = "nether through portal";
        stack.enter(2, home);
        home.state = "main through nested portal";
        assertEquals(2, stack.slot());
        stack.exit(home);
        assertEquals("main", home.state);
        assertEquals("nether through portal", nether.state);
        assertEquals(1, stack.slot());
        stack.exit(nether);
        assertEquals("nether idle", nether.state);
        assertEquals(0, stack.slot());
        assertEquals(List.of("main through nested portal"), home.replaced);
    }

    @Test
    public void siblingLayersReuseTheSameSlot() {
        LayerContextStack<String> stack = new LayerContextStack<>();
        Renderer nether = new Renderer("idle");
        stack.enter(1, nether);
        nether.state = "first portal";
        stack.exit(nether);
        stack.enter(1, nether);
        assertEquals(1, stack.slot());
        nether.state = "second portal";
        stack.exit(nether);
        assertEquals("idle", nether.state);
    }

    @Test
    public void aLayerCannotOpenAtOrBelowTheCurrentSlot() {
        LayerContextStack<String> stack = new LayerContextStack<>();
        Renderer home = new Renderer("main");
        assertThrows(IllegalStateException.class, () -> stack.enter(0, home));
        stack.enter(2, home);
        assertThrows(IllegalStateException.class, () -> stack.enter(2, new Renderer("other")));
        assertThrows(IllegalStateException.class, () -> stack.enter(1, new Renderer("other")));
        assertEquals(2, stack.slot());
    }

    @Test
    public void closingAnythingButTheInnermostLayerIsRejectedWithoutTouchingState() {
        LayerContextStack<String> stack = new LayerContextStack<>();
        Renderer outer = new Renderer("outer");
        Renderer inner = new Renderer("inner");
        assertThrows(IllegalStateException.class, () -> stack.exit(outer));
        stack.enter(1, outer);
        stack.enter(2, inner);
        outer.state = "changed";
        assertThrows(IllegalStateException.class, () -> stack.exit(outer));
        assertEquals("changed", outer.state);
        assertEquals(2, stack.slot());
    }

    @Test
    public void aFailedRestoreStillClosesTheLayer() {
        LayerContextStack<String> stack = new LayerContextStack<>();
        Renderer broken = new Renderer("main") {
            @Override
            public void restore(String state) {
                throw new IllegalStateException("restore failed");
            }
        };
        stack.enter(1, broken);
        assertThrows(IllegalStateException.class, () -> stack.exit(broken));
        assertEquals(0, stack.slot());
        assertFalse(stack.open());
    }

    private static class Renderer implements LayerContextStack.Context<String> {
        private final List<String> replaced = new ArrayList<>();
        private String state;

        private Renderer(String state) {
            this.state = state;
        }

        @Override
        public String capture() {
            return state;
        }

        @Override
        public void restore(String state) {
            replaced.add(this.state);
            this.state = state;
        }
    }
}
