package art.arcane.wormholes.modded.client.render.iris;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class IrisConstructionBudgetTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    public void eachFrameAllowsOnePipelineConstruction() {
        IrisConstructionBudget budget = new IrisConstructionBudget(dimension -> true);
        assertTrue(budget.available(1, OVERWORLD));
        budget.spend(1);
        assertFalse(budget.available(1, OVERWORLD));
        assertFalse(budget.available(1, NETHER));
        assertTrue(budget.available(2, OVERWORLD));
        budget.spend(2);
        assertFalse(budget.available(2, NETHER));
        assertTrue(budget.available(3, NETHER));
    }

    @Test
    public void unreadyDimensionsWaitWithoutConsumingTheFrame() {
        Set<String> ready = new HashSet<>(Set.of(NETHER));
        IrisConstructionBudget budget = new IrisConstructionBudget(ready::contains);
        assertFalse(budget.available(1, OVERWORLD));
        assertTrue(budget.available(1, NETHER));
        ready.add(OVERWORLD);
        assertTrue(budget.available(1, OVERWORLD));
        budget.spend(1);
        assertFalse(budget.available(1, NETHER));
    }

    @Test
    public void clearingTheBudgetReleasesTheCurrentFrame() {
        IrisConstructionBudget budget = new IrisConstructionBudget(dimension -> true);
        budget.spend(4);
        assertFalse(budget.available(4, OVERWORLD));
        budget.clear();
        assertTrue(budget.available(4, OVERWORLD));
    }

    @Test
    public void readinessIsRequired() {
        assertThrows(NullPointerException.class, () -> new IrisConstructionBudget(null));
    }

    @Test
    public void reserveAdmissionNeverStrandsAConstruction() {
        List<IrisProgramReserve.Inputs> linked = new ArrayList<>();
        IrisProgramReserve reserve = new IrisProgramReserve(new IrisProgramReserve.Options(inputs -> {
            linked.add(inputs);
            return linked.size();
        }, program -> {
        }, System::nanoTime));
        IrisConstructionBudget budget = new IrisConstructionBudget(reserve::admits);
        IrisProgramReserve.Inputs terrain = IrisProgramReserve.Inputs.program("gbuffers_terrain", "vertex", null, "fragment", Set.of());
        reserve.home(OVERWORLD);
        assertTrue("a dimension without a recipe constructs by linking its own programs", budget.available(1, NETHER));
        budget.spend(1);
        reserve.begin(NETHER, true);
        assertEquals(0, reserve.take(terrain));
        reserve.end(true);
        assertFalse("the frame that constructed must not link a reserve program", reserve.step());
        int frame = 2;
        while (!budget.available(frame, NETHER)) {
            assertTrue("a refused dimension must keep refilling until it is admitted", reserve.step());
            assertTrue(frame < 4);
            frame++;
        }
        assertEquals(List.of(terrain), linked);
        budget.spend(frame);
        assertFalse(budget.available(frame, NETHER));
        reserve.begin(NETHER, true);
        assertEquals(1, reserve.take(terrain));
        reserve.end(true);
        assertFalse(reserve.step());
        assertFalse(budget.available(frame + 1, NETHER));
        assertTrue(reserve.step());
        assertTrue(budget.available(frame + 2, NETHER));
    }
}
