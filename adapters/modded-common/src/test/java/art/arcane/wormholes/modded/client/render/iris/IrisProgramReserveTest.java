package art.arcane.wormholes.modded.client.render.iris;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class IrisProgramReserveTest {
    private static final String HOME = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String POCKETS = "wormholes:pockets";
    private static final Object FORMAT = new Object();
    private static final IrisProgramReserve.Inputs TERRAIN = shader("gbuffers_terrain", "terrain");
    private static final IrisProgramReserve.Inputs SKY = shader("gbuffers_skybasic", "sky");
    private static final IrisProgramReserve.Inputs COMPOSITE = IrisProgramReserve.Inputs.program("composite", "vertex", null, "fragment",
        Set.of(1, 2));
    private static final IrisProgramReserve.Inputs SHADOW_COMPUTE = IrisProgramReserve.Inputs.compute("shadowcomp", "compute", Set.of(3));

    @Test
    public void recordedHomeRecipeFillsTwoCompleteSetsWithOneLinkPerFrame() {
        Rig rig = new Rig();
        rig.record(HOME, false, TERRAIN, SKY, COMPOSITE);
        rig.reserve.home(HOME);
        assertFalse(rig.reserve.admits(HOME));
        assertFalse("the frame that constructed a pipeline must not also link a reserve program", rig.reserve.step());
        for (int frame = 0; frame < 3; frame++) {
            assertFalse(rig.reserve.admits(HOME));
            assertTrue(rig.reserve.step());
            assertEquals(frame + 1, rig.linked.size());
        }
        assertTrue(rig.reserve.admits(HOME));
        for (int frame = 0; frame < 3; frame++) {
            assertTrue(rig.reserve.step());
        }
        assertFalse(rig.reserve.step());
        assertEquals(List.of(TERRAIN, SKY, COMPOSITE, TERRAIN, SKY, COMPOSITE), rig.linked);
        IrisProgramReserve.Statistics statistics = rig.reserve.statistics();
        assertEquals(6L, statistics.linked());
        assertEquals(6, statistics.reserved());
        assertEquals(60L, statistics.linkNanos());
        assertEquals(1, statistics.recipes());
    }

    @Test
    public void portalConstructionReceivesReservedProgramsWithoutLinking() {
        Rig rig = filled();
        Set<Integer> reserved = new HashSet<>(rig.programs);
        rig.reserve.begin(HOME, true);
        int terrain = rig.reserve.take(TERRAIN);
        int sky = rig.reserve.take(SKY);
        int composite = rig.reserve.take(COMPOSITE);
        rig.reserve.end(true);
        assertTrue(reserved.contains(terrain) && reserved.contains(sky) && reserved.contains(composite));
        assertEquals(3, Set.of(terrain, sky, composite).size());
        assertEquals(6, rig.linked.size());
        IrisProgramReserve.Statistics statistics = rig.reserve.statistics();
        assertEquals(3L, statistics.handed());
        assertEquals(0L, statistics.misses());
        assertEquals(3, statistics.reserved());
        assertTrue("one complete set must remain after a construction consumed the other", rig.reserve.admits(HOME));
        assertFalse(rig.reserve.step());
        assertTrue(rig.reserve.step());
        assertEquals(TERRAIN, rig.linked.getLast());
        assertTrue(rig.deleted.isEmpty());
    }

    @Test
    public void handedProgramIsNeverHandedTwice() {
        Rig rig = filled();
        rig.reserve.begin(HOME, true);
        int first = rig.reserve.take(TERRAIN);
        rig.reserve.take(SKY);
        rig.reserve.take(COMPOSITE);
        rig.reserve.end(true);
        rig.reserve.begin(HOME, true);
        int second = rig.reserve.take(TERRAIN);
        rig.reserve.take(SKY);
        rig.reserve.take(COMPOSITE);
        rig.reserve.end(true);
        rig.reserve.begin(HOME, true);
        int third = rig.reserve.take(TERRAIN);
        rig.reserve.end(false);
        assertTrue(first > 0 && second > 0);
        assertNotEquals(first, second);
        assertEquals(0, third);
        assertEquals(1L, rig.reserve.statistics().misses());
        assertFalse(rig.reserve.admits(HOME));
    }

    @Test
    public void mainPipelineConstructionRecordsWithoutConsuming() {
        Rig rig = filled();
        rig.reserve.begin(HOME, false);
        assertEquals(0, rig.reserve.take(TERRAIN));
        assertEquals(0, rig.reserve.take(SKY));
        assertEquals(0, rig.reserve.take(COMPOSITE));
        rig.reserve.end(true);
        assertEquals(0L, rig.reserve.statistics().handed());
        assertEquals(0L, rig.reserve.statistics().misses());
        assertEquals(6, rig.reserve.statistics().reserved());
    }

    @Test
    public void changedInputsMissAndReplaceTheRecipeAndItsStalePrograms() {
        Rig rig = filled();
        IrisProgramReserve.Inputs changed = shader("gbuffers_terrain", "terrain with another option");
        rig.reserve.begin(HOME, true);
        int terrain = rig.reserve.take(changed);
        int sky = rig.reserve.take(SKY);
        int composite = rig.reserve.take(COMPOSITE);
        rig.reserve.end(true);
        assertEquals(0, terrain);
        assertTrue(sky > 0 && composite > 0);
        assertEquals(1L, rig.reserve.statistics().misses());
        assertFalse(rig.reserve.step());
        assertTrue(rig.reserve.step());
        assertEquals(2, rig.deleted.size());
        assertEquals(changed, rig.linked.getLast());
        rig.drain();
        assertTrue(rig.reserve.admits(HOME));
        assertEquals(6, rig.reserve.statistics().reserved());
    }

    @Test
    public void incompleteConstructionKeepsThePreviousRecipe() {
        Rig rig = filled();
        rig.reserve.begin(HOME, false);
        rig.reserve.take(shader("gbuffers_terrain", "partial"));
        rig.reserve.end(false);
        rig.drain();
        assertTrue(rig.deleted.isEmpty());
        assertTrue(rig.reserve.admits(HOME));
        assertEquals(6, rig.linked.size());
    }

    @Test
    public void clearDeletesEveryUnconsumedProgramAndForgetsRecipes() {
        Rig rig = filled();
        rig.reserve.begin(HOME, true);
        int handed = rig.reserve.take(TERRAIN);
        rig.reserve.end(false);
        rig.reserve.clear();
        List<Integer> expected = new ArrayList<>(rig.programs);
        expected.remove(Integer.valueOf(handed));
        assertEquals(new HashSet<>(expected), new HashSet<>(rig.deleted));
        assertEquals(5, rig.deleted.size());
        assertTrue("without a recipe the next construction links its own programs", rig.reserve.admits(HOME));
        assertFalse(rig.reserve.step());
        assertFalse(rig.reserve.step());
        IrisProgramReserve.Statistics statistics = rig.reserve.statistics();
        assertEquals(0, statistics.reserved());
        assertEquals(0, statistics.recipes());
        assertEquals(5L, statistics.deleted());
    }

    @Test
    public void repeatedInputsReserveOneProgramPerOccurrence() {
        Rig rig = new Rig();
        rig.record(HOME, false, TERRAIN, TERRAIN, SHADOW_COMPUTE);
        rig.reserve.home(HOME);
        rig.reserve.step();
        assertTrue(rig.reserve.step());
        assertTrue(rig.reserve.step());
        assertFalse(rig.reserve.admits(HOME));
        assertTrue(rig.reserve.step());
        assertTrue(rig.reserve.admits(HOME));
        assertEquals(List.of(TERRAIN, TERRAIN, SHADOW_COMPUTE), rig.linked);
        rig.drain();
        assertEquals(6, rig.linked.size());
    }

    @Test
    public void failedReserveLinkIsReportedOnceAndNeverRetried() {
        Rig rig = new Rig();
        rig.broken.add(SKY);
        rig.record(HOME, false, TERRAIN, SKY, COMPOSITE);
        rig.reserve.home(HOME);
        rig.reserve.step();
        assertTrue(rig.reserve.step());
        IllegalStateException failure = assertThrows(IllegalStateException.class, rig.reserve::step);
        assertEquals("link failed", failure.getMessage());
        rig.drain();
        assertEquals(List.of(TERRAIN, COMPOSITE, TERRAIN, COMPOSITE), rig.linked);
        assertTrue("a program the reserve cannot link is linked by the construction itself", rig.reserve.admits(HOME));
        assertEquals(1L, rig.reserve.statistics().failures());
    }

    @Test
    public void invalidReserveHandleDoesNotHoldBackAdmission() {
        Rig rig = new Rig();
        rig.invalid.add(SKY);
        rig.record(HOME, false, TERRAIN, SKY);
        rig.reserve.home(HOME);
        rig.drain();
        assertEquals(List.of(TERRAIN, TERRAIN), rig.linked);
        assertTrue(rig.reserve.admits(HOME));
        assertEquals(1L, rig.reserve.statistics().failures());
    }

    @Test
    public void demandedDimensionKeepsOneSetFilledBeforeTheSecondHomeSet() {
        Rig rig = new Rig();
        IrisProgramReserve.Inputs pocketTerrain = shader("gbuffers_terrain", "pocket terrain");
        rig.record(HOME, false, TERRAIN, SKY);
        rig.record(POCKETS, false, pocketTerrain);
        rig.reserve.home(HOME);
        rig.drain();
        assertEquals(List.of(TERRAIN, SKY, TERRAIN, SKY), rig.linked);
        assertFalse(rig.reserve.admits(POCKETS));
        rig.reserve.begin(POCKETS, true);
        assertEquals(0, rig.reserve.take(pocketTerrain));
        rig.reserve.end(true);
        rig.drain();
        assertEquals(List.of(TERRAIN, SKY, TERRAIN, SKY, pocketTerrain), rig.linked);
        assertTrue(rig.reserve.admits(POCKETS));
        rig.reserve.begin(HOME, true);
        rig.reserve.take(TERRAIN);
        rig.reserve.take(SKY);
        rig.reserve.end(true);
        rig.reserve.begin(POCKETS, true);
        rig.reserve.take(pocketTerrain);
        rig.reserve.end(true);
        rig.drain();
        assertEquals(List.of(TERRAIN, SKY, TERRAIN, SKY, pocketTerrain, pocketTerrain, TERRAIN, SKY), rig.linked);
    }

    @Test
    public void sharedInputsServeEveryDimensionThatUsesThem() {
        Rig rig = new Rig();
        rig.record(HOME, false, TERRAIN, SKY);
        rig.record(POCKETS, false, TERRAIN, SKY);
        rig.reserve.home(HOME);
        rig.drain();
        assertTrue(rig.reserve.admits(POCKETS));
        rig.reserve.begin(POCKETS, true);
        assertTrue(rig.reserve.take(TERRAIN) > 0);
        assertTrue(rig.reserve.take(SKY) > 0);
        rig.reserve.end(true);
        rig.drain();
        assertEquals(4 + 2, rig.linked.size());
        assertEquals(4, rig.reserve.statistics().reserved());
    }

    @Test
    public void homeChangeDeletesProgramsNoDimensionNeeds() {
        Rig rig = new Rig();
        IrisProgramReserve.Inputs netherTerrain = shader("gbuffers_terrain", "nether terrain");
        rig.record(HOME, false, TERRAIN);
        rig.record(NETHER, false, netherTerrain);
        rig.reserve.home(HOME);
        rig.drain();
        assertEquals(List.of(TERRAIN, TERRAIN), rig.linked);
        rig.reserve.home(NETHER);
        rig.drain();
        assertEquals(List.of(TERRAIN, TERRAIN, netherTerrain, netherTerrain), rig.linked);
        assertEquals(2, rig.deleted.size());
        assertTrue(rig.reserve.admits(NETHER));
        assertFalse(rig.reserve.admits(HOME));
        rig.drain();
        assertEquals(List.of(TERRAIN, TERRAIN, netherTerrain, netherTerrain, TERRAIN), rig.linked);
        assertTrue(rig.reserve.admits(HOME));
    }

    @Test
    public void dimensionWithoutRecipeIsAdmittedToLinkItsOwnPrograms() {
        Rig rig = new Rig();
        rig.record(HOME, false, TERRAIN);
        rig.reserve.home(HOME);
        rig.drain();
        assertTrue(rig.reserve.admits(NETHER));
        assertFalse(rig.reserve.step());
        assertEquals(List.of(TERRAIN, TERRAIN), rig.linked);
    }

    @Test
    public void recordedDimensionOutsideThePlanRefillsAfterItsAdmissionIsRefused() {
        Rig rig = new Rig();
        IrisProgramReserve.Inputs netherTerrain = shader("gbuffers_terrain", "nether terrain");
        rig.record(HOME, false, TERRAIN);
        rig.record(NETHER, false, netherTerrain, SKY);
        rig.reserve.home(HOME);
        rig.drain();
        assertEquals(List.of(TERRAIN, TERRAIN), rig.linked);
        assertFalse(rig.reserve.admits(NETHER));
        assertTrue(rig.reserve.step());
        assertFalse(rig.reserve.admits(NETHER));
        assertTrue(rig.reserve.step());
        assertTrue(rig.reserve.admits(NETHER));
        assertFalse(rig.reserve.step());
        assertEquals(List.of(TERRAIN, TERRAIN, netherTerrain, SKY), rig.linked);
    }

    @Test
    public void fillLinksWithinItsBudgetUntilEveryDeficitIsReserved() {
        Rig rig = new Rig();
        rig.record(HOME, false, TERRAIN, SKY, COMPOSITE);
        rig.reserve.home(HOME);
        assertFalse(rig.reserve.fill(25L));
        assertEquals(3, rig.linked.size());
        assertTrue(rig.reserve.fill(1_000L));
        assertEquals(6, rig.linked.size());
        assertTrue(rig.reserve.fill(1_000L));
        assertEquals(6, rig.linked.size());
    }

    @Test
    public void reserveLinksNothingWhileAConstructionRecords() {
        Rig rig = new Rig();
        rig.record(HOME, false, TERRAIN);
        rig.reserve.home(HOME);
        rig.reserve.begin(HOME, true);
        assertFalse(rig.reserve.step());
        assertFalse(rig.reserve.fill(1_000L));
        rig.reserve.end(false);
        assertTrue(rig.linked.isEmpty());
    }

    @Test
    public void nestedRecordingIsRejected() {
        Rig rig = new Rig();
        rig.reserve.begin(HOME, false);
        assertThrows(IllegalStateException.class, () -> rig.reserve.begin(NETHER, true));
        rig.reserve.end(false);
        assertThrows(IllegalStateException.class, () -> rig.reserve.end(false));
    }

    @Test
    public void inputsDistinguishEveryLinkParameter() {
        assertEquals(shader("name", "source"), shader("name", new String("source")));
        assertNotEquals(shader("name", "source"), shader("other", "source"));
        assertNotEquals(shader("name", "source"), IrisProgramReserve.Inputs.shader("name", "source", null, null, null, "fragment", new Object(),
            false));
        assertNotEquals(shader("name", "source"), IrisProgramReserve.Inputs.shader("name", "source", null, null, null, "fragment", FORMAT,
            true));
        assertNotEquals(shader("name", "source"), IrisProgramReserve.Inputs.shader("name", "source", "geometry", null, null, "fragment",
            FORMAT, false));
        assertNotEquals(COMPOSITE, IrisProgramReserve.Inputs.program("composite", "vertex", null, "fragment", Set.of(1)));
        assertNotEquals(IrisProgramReserve.Inputs.program("pass", "vertex", null, "fragment", Set.of()),
            IrisProgramReserve.Inputs.shader("pass", "vertex", null, null, null, "fragment", null, false));
    }

    @Test
    public void sodiumInputsDistinguishEveryStageAndKind() {
        assertEquals(sodium("terrain"), IrisProgramReserve.Inputs.sodium("terrain", new String("terrain vertex"), null, null, null, "fragment"));
        assertNotEquals(sodium("terrain"), sodium("translucent"));
        assertNotEquals(sodium("terrain"), IrisProgramReserve.Inputs.sodium("terrain", "terrain vertex", "geometry", null, null, "fragment"));
        assertNotEquals(sodium("terrain"), IrisProgramReserve.Inputs.sodium("terrain", "terrain vertex", null, "control", null, "fragment"));
        assertNotEquals(sodium("terrain"), IrisProgramReserve.Inputs.sodium("terrain", "terrain vertex", null, null, "evaluation", "fragment"));
        assertNotEquals(sodium("terrain"), IrisProgramReserve.Inputs.sodium("terrain", "terrain vertex", null, null, null, "other fragment"));
        assertNotEquals(sodium("terrain"), IrisProgramReserve.Inputs.shader("terrain", "terrain vertex", null, null, null, "fragment", null,
            false));
        assertNotEquals(sodium("terrain"), IrisProgramReserve.Inputs.program("terrain", "terrain vertex", null, "fragment", Set.of()));
    }

    @Test
    public void recordedSodiumTerrainProgramsAreHandedWithTheirRecipe() {
        List<IrisProgramReserve.Inputs> terrain = List.of(sodium("shadow"), sodium("shadow_cutout"), sodium("shadow_trans"), sodium("terrain"),
            sodium("terrain_cutout"), sodium("translucent"));
        List<IrisProgramReserve.Inputs> sequence = new ArrayList<>(List.of(TERRAIN, SKY, COMPOSITE));
        sequence.addAll(terrain);
        Rig rig = new Rig();
        rig.record(HOME, false, sequence.toArray(new IrisProgramReserve.Inputs[0]));
        rig.reserve.home(HOME);
        rig.drain();
        assertEquals(2 * sequence.size(), rig.linked.size());
        assertTrue(rig.reserve.admits(HOME));
        rig.reserve.begin(HOME, true);
        rig.reserve.take(TERRAIN);
        rig.reserve.take(SKY);
        rig.reserve.take(COMPOSITE);
        Set<Integer> handed = new HashSet<>();
        for (IrisProgramReserve.Inputs inputs : terrain) {
            int program = rig.reserve.take(inputs);
            assertTrue("a recorded Sodium terrain program must be handed from the reserve", program > 0);
            assertEquals(inputs, rig.linked.get(rig.programs.indexOf(program)));
            handed.add(program);
        }
        rig.reserve.end(true);
        assertEquals(terrain.size(), handed.size());
        assertEquals(0L, rig.reserve.statistics().misses());
        assertEquals(sequence.size(), rig.reserve.statistics().reserved());
        Set<Integer> unusedTerrain = new HashSet<>();
        for (int index = 0; index < rig.programs.size(); index++) {
            if (terrain.contains(rig.linked.get(index)) && !handed.contains(rig.programs.get(index))) {
                unusedTerrain.add(rig.programs.get(index));
            }
        }
        assertEquals(terrain.size(), unusedTerrain.size());
        rig.reserve.clear();
        assertTrue("clear must delete every unused Sodium terrain spare", rig.deleted.containsAll(unusedTerrain));
        for (int program : handed) {
            assertFalse("a handed Sodium terrain program belongs to its pipeline", rig.deleted.contains(program));
        }
        assertEquals(sequence.size(), rig.deleted.size());
    }

    private static IrisProgramReserve.Inputs shader(String name, String vertex) {
        return IrisProgramReserve.Inputs.shader(name, vertex, null, null, null, "fragment", FORMAT, false);
    }

    private static IrisProgramReserve.Inputs sodium(String name) {
        return IrisProgramReserve.Inputs.sodium(name, name + " vertex", null, null, null, "fragment");
    }

    private static Rig filled() {
        Rig rig = new Rig();
        rig.record(HOME, false, TERRAIN, SKY, COMPOSITE);
        rig.reserve.home(HOME);
        rig.drain();
        assertEquals(6, rig.programs.size());
        return rig;
    }

    private static final class Rig {
        private final List<IrisProgramReserve.Inputs> linked = new ArrayList<>();
        private final List<Integer> programs = new ArrayList<>();
        private final List<Integer> deleted = new ArrayList<>();
        private final Set<IrisProgramReserve.Inputs> broken = new HashSet<>();
        private final Set<IrisProgramReserve.Inputs> invalid = new HashSet<>();
        private final AtomicLong clock = new AtomicLong();
        private final IrisProgramReserve reserve = new IrisProgramReserve(new IrisProgramReserve.Options(this::link, deleted::add, clock::get));

        private int link(IrisProgramReserve.Inputs inputs) {
            clock.addAndGet(10L);
            if (broken.contains(inputs)) {
                throw new IllegalStateException("link failed");
            }
            if (invalid.contains(inputs)) {
                return 0;
            }
            linked.add(inputs);
            int program = 100 + linked.size();
            programs.add(program);
            return program;
        }

        private void record(String dimension, boolean consuming, IrisProgramReserve.Inputs... sequence) {
            reserve.begin(dimension, consuming);
            for (IrisProgramReserve.Inputs inputs : sequence) {
                reserve.take(inputs);
            }
            reserve.end(true);
        }

        private void drain() {
            int idle = 0;
            while (idle < 2) {
                try {
                    idle = reserve.step() ? 0 : idle + 1;
                } catch (IllegalStateException failure) {
                    idle = 0;
                }
            }
        }
    }
}
