package art.arcane.optics.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Face;

final class StateRewriteCacheTest {
    private static final AxisPermutation QUARTER_TURN = AxisPermutation.between(Frame.canonical(Face.N), Frame.canonical(Face.E));
    private static final AxisPermutation NORTH_MIRROR = AxisPermutation.mirror(Frame.canonical(Face.N), QuarterTurn.DEGREES_0);

    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();
    private final StateRewriteCache<TestBlock> cache = new StateRewriteCache<TestBlock>(this::read, this::write);

    @Test
    void rewritesThroughTheRulesAndReusesTheResultPerPermutation() {
        TestBlock stairs = new TestBlock("stairs", StateProperties.of(Map.of("facing", "north", "half", "bottom", "shape", "inner_left")));

        TestBlock turned = cache.apply(stairs, QUARTER_TURN);
        TestBlock again = cache.apply(new TestBlock("stairs", stairs.properties()), QUARTER_TURN);
        TestBlock mirrored = cache.apply(stairs, NORTH_MIRROR);

        assertEquals(BlockStateRules.apply(stairs.properties(), QUARTER_TURN), turned.properties());
        assertSame(turned, again);
        assertEquals(BlockStateRules.apply(stairs.properties(), NORTH_MIRROR), mirrored.properties());
        assertEquals(3, reads.get());
        assertEquals(2, writes.get());
    }

    @Test
    void identityAndUnaffectedStatesComeBackUntouchedWithoutRewriting() {
        TestBlock stairs = new TestBlock("stairs", StateProperties.of(Map.of("facing", "north")));
        TestBlock leaves = new TestBlock("leaves", StateProperties.of(Map.of("distance", "3", "persistent", "true")));

        assertSame(stairs, cache.apply(stairs, AxisPermutation.IDENTITY));
        assertSame(leaves, cache.apply(leaves, QUARTER_TURN));
        assertTrue(cache.rewrites(stairs));
        assertFalse(cache.rewrites(leaves));
        assertFalse(cache.rewrites(new TestBlock("stone", StateProperties.EMPTY)));
        assertEquals(0, writes.get());
    }

    @Test
    void unchangedRewritesKeepTheCallersInstance() {
        TestBlock first = new TestBlock("rod", StateProperties.of(Map.of("axis", "y")));
        TestBlock equal = new TestBlock("rod", first.properties());

        assertSame(first, cache.apply(first, QUARTER_TURN));
        assertSame(equal, cache.apply(equal, QUARTER_TURN));
        assertEquals(2, reads.get());
        assertEquals(0, writes.get());
    }

    @Test
    void theCacheStaysBoundedByForgettingOldStates() {
        TestBlock first = new TestBlock("block0", StateProperties.of(Map.of("facing", "north")));
        cache.apply(first, QUARTER_TURN);
        for (int index = 1; index <= StateRewriteCache.LIMIT; index++) {
            cache.apply(new TestBlock("block" + index, first.properties()), QUARTER_TURN);
        }
        int before = reads.get();

        TestBlock rewritten = cache.apply(first, QUARTER_TURN);

        assertEquals(before + 2, reads.get());
        assertNotSame(first, rewritten);
        assertEquals("east", rewritten.properties().get("facing"));
    }

    private StateProperties read(TestBlock block) {
        reads.incrementAndGet();
        return block.properties();
    }

    private TestBlock write(TestBlock block, StateProperties properties) {
        writes.incrementAndGet();
        return properties.equals(block.properties()) ? block : new TestBlock(block.name(), properties);
    }

    private record TestBlock(String name, StateProperties properties) {
    }
}
