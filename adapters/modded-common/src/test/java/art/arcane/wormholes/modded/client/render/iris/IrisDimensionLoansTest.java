package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisDimensionLoansTest {
    private static final String NETHER = "minecraft:the_nether";
    private static final String POCKETS = "wormholes:pockets";
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    public void eachIdleDimensionLendsToOneActualCameraInPlanOrder() {
        IrisDimensionLoans loans = new IrisDimensionLoans();
        IrisViewPipelinePath first = path("first", NETHER);
        IrisViewPipelinePath second = path("second", NETHER);
        IrisViewPipelinePath pocket = path("pocket", POCKETS);
        IrisViewPipelinePath home = path("home", OVERWORLD);
        loans.plan(List.of(first, second, pocket, home), dimension -> !dimension.equals(OVERWORLD), path -> false);
        assertTrue(loans.holds(first));
        assertFalse(loans.holds(second));
        assertTrue(loans.holds(pocket));
        assertFalse(loans.holds(home));
        assertEquals(List.of(first, pocket), loans.holders());
    }

    @Test
    public void actualHolderKeepsItsLoanWhileANewCameraOfTheSameDimensionAppears() {
        IrisDimensionLoans loans = new IrisDimensionLoans();
        IrisViewPipelinePath first = path("first", NETHER);
        IrisViewPipelinePath second = path("second", NETHER);
        loans.plan(List.of(first), dimension -> true, path -> false);
        loans.plan(List.of(second, first), dimension -> true, path -> false);
        assertTrue(loans.holds(first));
        assertFalse(loans.holds(second));
    }

    @Test
    public void replacedCameraTakesOverTheLoanOnlyAfterTheHolderStopsDrawing() {
        IrisDimensionLoans loans = new IrisDimensionLoans();
        IrisViewPipelinePath front = path("front", NETHER);
        IrisViewPipelinePath back = path("back", NETHER);
        loans.plan(List.of(front), dimension -> true, path -> false);
        loans.plan(List.of(), dimension -> true, path -> false);
        assertTrue(loans.holds(front));
        loans.plan(List.of(back), dimension -> true, path -> false);
        assertTrue(loans.holds(back));
        assertFalse(loans.holds(front));
    }

    @Test
    public void ownedCamerasAndUnavailableDimensionsNeverHoldLoans() {
        IrisDimensionLoans loans = new IrisDimensionLoans();
        IrisViewPipelinePath cached = path("cached", NETHER);
        IrisViewPipelinePath lent = path("lent", NETHER);
        loans.plan(List.of(cached, lent), dimension -> true, cached::equals);
        assertFalse(loans.holds(cached));
        assertTrue(loans.holds(lent));
        loans.plan(List.of(lent), dimension -> false, path -> false);
        assertFalse(loans.holds(lent));
        assertTrue(loans.holders().isEmpty());
    }

    @Test
    public void drawClaimsAllowOneCameraPerDimensionEachFrame() {
        IrisDimensionLoans loans = new IrisDimensionLoans();
        IrisViewPipelinePath first = path("first", NETHER);
        IrisViewPipelinePath second = path("second", NETHER);
        Set<IrisViewPipelinePath> actual = Set.of(first, second);
        loans.plan(List.of(first, second), dimension -> true, path -> false);
        assertFalse(loans.claim(second, 7, actual::contains));
        assertTrue(loans.claim(first, 7, actual::contains));
        assertTrue(loans.claim(first, 7, actual::contains));
        assertFalse(loans.claim(second, 7, actual::contains));
        assertEquals(1L, loans.loans());
        assertTrue(loans.claim(first, 8, actual::contains));
        assertEquals(2L, loans.loans());
    }

    @Test
    public void unplannedDrawClaimsAnIdleLoanAndKeepsItForLaterFrames() {
        IrisDimensionLoans loans = new IrisDimensionLoans();
        IrisViewPipelinePath crossing = path("crossing", OVERWORLD);
        IrisViewPipelinePath previous = path("previous", OVERWORLD);
        loans.plan(List.of(previous), dimension -> true, path -> false);
        assertTrue(loans.claim(crossing, 3, path -> false));
        assertTrue(loans.holds(crossing));
        assertFalse(loans.holds(previous));
        assertFalse(loans.claim(previous, 3, path -> false));
    }

    @Test
    public void forgottenCamerasReleaseTheirLoans() {
        IrisDimensionLoans loans = new IrisDimensionLoans();
        IrisViewPipelinePath first = path("first", NETHER);
        loans.plan(List.of(first), dimension -> true, path -> false);
        assertTrue(loans.claim(first, 1, path -> true));
        loans.forget(first::equals);
        assertFalse(loans.holds(first));
        assertTrue(loans.claim(path("other", NETHER), 1, path -> true));
        loans.clear();
        assertTrue(loans.holders().isEmpty());
    }

    private static IrisViewPipelinePath path(String key, String dimension) {
        return new IrisViewPipelinePath(List.of(new IrisViewPipelinePath.Camera("ARM", key, OVERWORLD, dimension,
            Similarity.of(OpticTransform.IDENTITY, 1.0D))), dimension);
    }
}
