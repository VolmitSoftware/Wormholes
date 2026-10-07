package art.arcane.optics.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;
import art.arcane.optics.aperture.ApertureDescriptor;

final class ClientViewSweepTest {
    private static final double HYSTERESIS = ClientSweep.DEFAULT_HYSTERESIS_BLOCKS;

    @Test
    void entersStayInsideTheInnerConeAndExitsStayOutsideTheOuterCone() {
        ClientSweepScene scene = ClientSweepScene.rtpWall(32, 24);
        ApertureDescriptor geometry = scene.geometry(true, ApertureDescriptor.BLACKOUT_OFF);
        BlockBox bounds = scene.bounds(true);
        ClientSweep sweep = new ClientSweep(geometry, bounds, HYSTERESIS);
        LongOpenHashSet mirror = new LongOpenHashSet();
        Random random = new Random(0x4157L);
        double x = 6.0D;
        double y = 65.5D;
        double z = 0.5D;
        int exits = 0;
        int enters = 0;
        for (int step = 0; step < 80; step++) {
            x = Math.max(1.0D, Math.min(12.0D, x + ((random.nextDouble() - 0.5D) * 0.9D)));
            y = Math.max(63.0D, Math.min(68.0D, y + ((random.nextDouble() - 0.5D) * 0.4D)));
            z = Math.max(-5.0D, Math.min(5.0D, z + ((random.nextDouble() - 0.5D) * 0.9D)));
            sweep.sweep(x, y, z, 0.0D, 0.0D, 0.0D);
            LongOpenHashSet inner = fresh(geometry, bounds, HYSTERESIS, x, y, z);
            LongOpenHashSet outer = fresh(geometry, bounds, HYSTERESIS * 2.0D, x, y, z);
            for (long key : sweep.entered()) {
                assertTrue(inner.contains(key), "entered outside the inner cone at step " + step);
                assertTrue(mirror.add(key), "entered twice at step " + step);
            }
            for (long key : sweep.exited()) {
                assertFalse(outer.contains(key), "exited inside the outer cone at step " + step);
                assertTrue(mirror.remove(key), "exited a cell that was not applied at step " + step);
            }
            exits += sweep.exited().size();
            enters += sweep.entered().size();
            assertTrue(mirror.containsAll(inner), "inner cone not applied at step " + step);
            assertTrue(outer.containsAll(mirror), "applied cells outside the outer cone at step " + step);
            assertEquals(mirror.size(), sweep.appliedCount());
            for (long key : inner) {
                assertTrue(sweep.applied(CellKeys.unpackX(key), CellKeys.unpackY(key), CellKeys.unpackZ(key)));
            }
        }
        assertTrue(enters > 0 && exits > 0, "the walk must move the cone");
    }

    @Test
    void appliedKeysListExactlyTheAppliedCells() {
        ClientSweepScene scene = ClientSweepScene.rtpWall(32, 24);
        ClientSweep sweep = new ClientSweep(scene.geometry(true, ApertureDescriptor.BLACKOUT_OFF), scene.bounds(true), HYSTERESIS);
        sweep.sweep(6.0D, 65.5D, 0.5D, 0.0D, 0.0D, 0.0D);
        LongArrayList keys = new LongArrayList();
        sweep.appliedKeys(keys);
        assertTrue(sweep.appliedCount() > 0);
        assertEquals(sweep.appliedCount(), keys.size());
        LongOpenHashSet entered = new LongOpenHashSet(sweep.entered());
        for (int index = 0; index < keys.size(); index++) {
            long key = keys.getLong(index);
            assertTrue(sweep.applied(CellKeys.unpackX(key), CellKeys.unpackY(key), CellKeys.unpackZ(key)));
            assertTrue(entered.contains(key));
        }
    }

    @Test
    void aLateralOscillationWithinThePaddingFlipsNoCells() {
        ClientSweepScene scene = ClientSweepScene.rtpWall(64, 40);
        ApertureDescriptor geometry = scene.geometry(true, ApertureDescriptor.BLACKOUT_OFF);
        for (double distance : new double[] {1.5D, 3.0D, 8.0D, 20.0D}) {
            ClientSweep sweep = new ClientSweep(geometry, scene.bounds(true), HYSTERESIS);
            double x = 0.5D + distance;
            sweep.sweep(x, 65.5D, 0.5D, 0.0D, 0.0D, 0.0D);
            sweep.sweep(x, 65.6D, 0.7D, 0.0D, 0.0D, 0.0D);
            for (int swing = 0; swing < 20; swing++) {
                boolean second = (swing & 1) == 0;
                sweep.sweep(x, second ? 65.5D : 65.6D, second ? 0.5D : 0.7D, 0.0D, 0.0D, 0.0D);
                assertTrue(sweep.entered().isEmpty() && sweep.exited().isEmpty(),
                    "cells flipped inside the hysteresis band at distance " + distance + " swing " + swing);
            }
            assertTrue(sweep.appliedCount() > 0);
        }
    }

    @Test
    void withoutHysteresisTheSameOscillationFlipsCells() {
        ClientSweepScene scene = ClientSweepScene.rtpWall(64, 40);
        ClientSweep sweep = new ClientSweep(scene.geometry(true, ApertureDescriptor.BLACKOUT_OFF), scene.bounds(true), 0.0D);
        sweep.sweep(3.5D, 65.5D, 0.5D, 0.0D, 0.0D, 0.0D);
        sweep.sweep(3.5D, 65.6D, 0.7D, 0.0D, 0.0D, 0.0D);
        assertFalse(sweep.entered().isEmpty() && sweep.exited().isEmpty());
    }

    @Test
    void jitterBelowTheEyeQuantumDoesNoWork() {
        ClientSweepScene scene = ClientSweepScene.rtpWall(32, 24);
        ClientSweep sweep = new ClientSweep(scene.geometry(true, ApertureDescriptor.BLACKOUT_OFF), scene.bounds(true), HYSTERESIS);
        assertTrue(sweep.sweep(5.0D, 65.5D, 0.5D, 0.0D, 0.0D, 0.0D));
        int applied = sweep.appliedCount();
        assertFalse(sweep.sweep(5.012D, 65.51D, 0.49D, 0.0D, 0.0D, 0.0D));
        assertTrue(sweep.entered().isEmpty() && sweep.exited().isEmpty() && sweep.reshelled().isEmpty());
        assertEquals(applied, sweep.appliedCount());
    }

    @Test
    void velocityLookaheadSweepsTheNextTickEye() {
        ClientSweepScene scene = ClientSweepScene.rtpWall(32, 24);
        ApertureDescriptor geometry = scene.geometry(true, ApertureDescriptor.BLACKOUT_OFF);
        ClientSweep predicted = new ClientSweep(geometry, scene.bounds(true), HYSTERESIS);
        predicted.sweep(5.0D, 65.5D, 0.5D, 0.4D, 0.1D, -0.3D);
        LongOpenHashSet expected = fresh(geometry, scene.bounds(true), HYSTERESIS, 5.4D, 65.6D, 0.2D);
        assertEquals(expected, new LongOpenHashSet(predicted.entered()));
        assertFalse(expected.equals(fresh(geometry, scene.bounds(true), HYSTERESIS, 5.0D, 65.5D, 0.5D)));
    }

    @Test
    void crossingToTheUnservedSideRevertsEveryCell() {
        ClientSweepScene scene = ClientSweepScene.rtpWall(32, 24);
        ClientSweep sweep = new ClientSweep(scene.geometry(true, ApertureDescriptor.BLACKOUT_OFF), scene.bounds(true), HYSTERESIS);
        sweep.sweep(4.0D, 65.5D, 0.5D, 0.0D, 0.0D, 0.0D);
        int applied = sweep.appliedCount();
        assertTrue(applied > 0);
        assertTrue(sweep.eyeFrontSide());
        sweep.sweep(-4.0D, 65.5D, 0.5D, 0.0D, 0.0D, 0.0D);
        assertFalse(sweep.eyeFrontSide());
        assertEquals(applied, sweep.exited().size());
        assertEquals(0, sweep.appliedCount());
        assertTrue(sweep.entered().isEmpty());
    }

    @Test
    void reconfiguringTheBoundsKeepsSharedCellsAndExitsTheRest() {
        ClientSweepScene scene = ClientSweepScene.rtpWall(32, 24);
        ApertureDescriptor geometry = scene.geometry(true, ApertureDescriptor.BLACKOUT_OFF);
        BlockBox bounds = scene.bounds(true);
        ClientSweep sweep = new ClientSweep(geometry, bounds, HYSTERESIS);
        sweep.sweep(4.0D, 65.5D, 0.5D, 0.0D, 0.0D, 0.0D);
        LongOpenHashSet before = new LongOpenHashSet(sweep.entered());
        BlockBox shallow = BlockBox.spanning(-16, bounds.minY(), bounds.minZ(), -1, bounds.minY() + bounds.sizeY() - 1,
            bounds.minZ() + bounds.sizeZ() - 1);
        sweep.reconfigure(geometry, shallow);
        LongOpenHashSet dropped = new LongOpenHashSet(sweep.exited());
        assertFalse(dropped.isEmpty());
        for (long key : before) {
            boolean kept = shallow.index(CellKeys.unpackX(key), CellKeys.unpackY(key), CellKeys.unpackZ(key)) >= 0;
            assertEquals(!kept, dropped.contains(key));
        }
        assertEquals(before.size() - dropped.size(), sweep.appliedCount());
        sweep.sweep(4.0D, 65.5D, 0.5D, 0.0D, 0.0D, 0.0D);
        assertTrue(sweep.entered().isEmpty());
        assertTrue(sweep.exited().isEmpty());
    }

    @Test
    void clearRevertsEveryAppliedCell() {
        ClientSweepScene scene = ClientSweepScene.floorHatch(32, 24);
        ClientSweep sweep = new ClientSweep(scene.geometry(true, ApertureDescriptor.BLACKOUT_SHELL), scene.bounds(true), HYSTERESIS);
        sweep.sweep(0.5D, 72.0D, 0.5D, 0.0D, 0.0D, 0.0D);
        LongOpenHashSet applied = new LongOpenHashSet(sweep.entered());
        assertFalse(applied.isEmpty());
        sweep.clear();
        assertEquals(applied, new LongOpenHashSet(sweep.exited()));
        assertEquals(0, sweep.appliedCount());
        assertTrue(sweep.sweep(0.5D, 72.0D, 0.5D, 0.0D, 0.0D, 0.0D));
        assertEquals(applied, new LongOpenHashSet(sweep.entered()));
    }

    @Test
    void shellChangesOnAppliedCellsAreReportedSeparately() {
        ClientSweepScene scene = ClientSweepScene.rtpWall(24, 16);
        ClientSweep sweep = new ClientSweep(scene.geometry(true, ApertureDescriptor.BLACKOUT_SHELL), scene.bounds(true), HYSTERESIS);
        int reshelled = 0;
        LongOpenHashSet applied = new LongOpenHashSet();
        for (int step = 0; step < 24; step++) {
            double x = 2.0D + (step * 0.35D);
            double z = 0.5D + (Math.sin(step * 0.7D) * 1.5D);
            sweep.sweep(x, 65.5D, z, 0.0D, 0.0D, 0.0D);
            LongOpenHashSet entered = new LongOpenHashSet(sweep.entered());
            LongOpenHashSet exited = new LongOpenHashSet(sweep.exited());
            for (long key : sweep.reshelled()) {
                assertTrue(applied.contains(key) && !entered.contains(key) && !exited.contains(key));
                assertTrue(sweep.applied(CellKeys.unpackX(key), CellKeys.unpackY(key), CellKeys.unpackZ(key)));
            }
            reshelled += sweep.reshelled().size();
            applied.addAll(entered);
            applied.removeAll(exited);
        }
        assertTrue(reshelled > 0, "moving the eye must move the blackout shell");
    }

    @Test
    void theShellIsTheFarFaceAndTheHiddenRim() {
        ClientSweepScene scene = ClientSweepScene.rtpWall(24, 16);
        ClientSweep sweep = new ClientSweep(scene.geometry(true, ApertureDescriptor.BLACKOUT_SHELL), scene.bounds(true), 0.0D);
        sweep.sweep(8.5D, 65.5D, 0.5D, 0.0D, 0.0D, 0.0D);
        int deepest = Integer.MAX_VALUE;
        for (long key : sweep.entered()) {
            deepest = Math.min(deepest, CellKeys.unpackX(key));
        }
        assertTrue(deepest <= -23 && deepest >= -24, "the cone must reach the depth limit, deepest slab " + deepest);
        int far = 0;
        for (long key : sweep.entered()) {
            int x = CellKeys.unpackX(key);
            int y = CellKeys.unpackY(key);
            int z = CellKeys.unpackZ(key);
            if (x == deepest) {
                far += sweep.shell(x, y, z) ? 1 : 0;
            }
            if (x == -1 && y == 65 && z == 0) {
                assertFalse(sweep.shell(x, y, z), "a cell seen straight through the aperture is never shell");
            }
        }
        assertTrue(far > 0);
    }

    @Test
    void invalidGeometryIsRejected() {
        ClientSweepScene scene = ClientSweepScene.rtpWall(32, 24);
        ApertureDescriptor valid = scene.geometry(true, ApertureDescriptor.BLACKOUT_OFF);
        ApertureDescriptor broken = new ApertureDescriptor(valid.originX(), valid.originY(), valid.originZ(), 17, true, 0, false,
            valid.apertureWidth(), valid.apertureHeight(), valid.apertureMask(), 2.0F, 0.75F, 0.2F, 32, 0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of());
        assertThrows(IllegalArgumentException.class, () -> new ClientSweep(broken, scene.bounds(true), HYSTERESIS));
        assertThrows(IllegalArgumentException.class, () -> new ClientSweep(valid, scene.bounds(true), -1.0D));
    }

    @Test
    void nineDomePlatesFromOneEyeAreRecorded() {
        Vec3d eye = new Vec3d(637.5D, 64.62D, -4686.5D);
        List<ClientSweep> sweeps = domeSweeps(eye);
        for (int warmup = 0; warmup < 60; warmup++) {
            for (ClientSweep sweep : sweeps) {
                sweep.clear();
                sweep.sweep(eye.x() + ((warmup & 3) * 0.1D), eye.y(), eye.z(), 0.0D, 0.0D, 0.0D);
            }
        }
        long[] firstFill = new long[21];
        long[] moving = new long[21];
        int cells = 0;
        for (int run = 0; run < firstFill.length; run++) {
            for (ClientSweep sweep : sweeps) {
                sweep.clear();
            }
            long start = System.nanoTime();
            for (ClientSweep sweep : sweeps) {
                sweep.sweep(eye.x(), eye.y(), eye.z(), 0.0D, 0.0D, 0.0D);
            }
            firstFill[run] = System.nanoTime() - start;
            cells = 0;
            for (ClientSweep sweep : sweeps) {
                cells += sweep.appliedCount();
            }
            start = System.nanoTime();
            for (ClientSweep sweep : sweeps) {
                sweep.sweep(eye.x() + 0.15D, eye.y() + 0.05D, eye.z() - 0.1D, 0.0D, 0.0D, 0.0D);
            }
            moving[run] = System.nanoTime() - start;
        }
        Arrays.sort(firstFill);
        Arrays.sort(moving);
        System.out.println(String.format(Locale.ROOT,
            "ClientViewSweep dome cost: nine plates, %d applied cells, first fill p50 %.3f ms, moving eye p50 %.3f ms",
            cells, firstFill[firstFill.length / 2] / 1.0E6D, moving[moving.length / 2] / 1.0E6D));
        int lit = 0;
        for (ClientSweep sweep : sweeps) {
            lit += sweep.appliedCount() > 0 ? 1 : 0;
        }
        assertEquals(9, sweeps.size());
        assertTrue(lit >= 8, "the dome eye must see through nearly every plate, saw " + lit);
    }

    private static List<ClientSweep> domeSweeps(Vec3d eye) {
        List<ClientSweep> sweeps = new ArrayList<ClientSweep>(9);
        sweeps.add(domeSweep(new Box(635.0D, 639.999D, 67.0D, 67.999D, -4689.0D, -4684.001D), Face.U, true, eye));
        Face[] facings = {Face.S, Face.E, Face.S, Face.W, Face.N, Face.N, Face.E, Face.W};
        double[][] spans = {
            {634.0D, 636.999D, -4682.0D, -4681.001D},
            {642.0D, 642.999D, -4686.0D, -4683.001D},
            {638.0D, 640.999D, -4682.0D, -4681.001D},
            {632.0D, 632.999D, -4686.0D, -4683.001D},
            {638.0D, 640.999D, -4692.0D, -4691.001D},
            {634.0D, 636.999D, -4692.0D, -4691.001D},
            {642.0D, 642.999D, -4690.0D, -4687.001D},
            {632.0D, 632.999D, -4690.0D, -4687.001D}};
        for (int index = 0; index < facings.length; index++) {
            double[] span = spans[index];
            sweeps.add(domeSweep(new Box(span[0], span[1], 63.0D, 65.999D, span[2], span[3]), facings[index], false, eye));
        }
        return sweeps;
    }

    private static ClientSweep domeSweep(Box area, Face facing, boolean mirror, Vec3d eye) {
        ApertureCells aperture = new ApertureCells();
        aperture.setArea(area);
        Frame frame = Frame.canonical(facing);
        Vec3d origin = area.center();
        boolean frontSide = ((eye.x() - origin.x()) * facing.x()) + ((eye.y() - origin.y()) * facing.y())
            + ((eye.z() - origin.z()) * facing.z()) >= 0.0D;
        ApertureDescriptor geometry = ApertureDescriptor.fromPortal(new ApertureDescriptor.Source(aperture, frame, frontSide, mirror, 0,
            ClientSweepScene.NEAR_PLANE_PADDING, ClientSweepScene.APERTURE_PADDING, ClientSweepScene.CULLING_RATIO, 64, 0,
            ApertureDescriptor.BLACKOUT_OFF, 0, ApertureDescriptor.MASK_AIR_PROJECT, BlockClaim.LightingPolicy.SOURCE, 0,
            mirror ? 0 : 1, 0.0D, 0, 0L, List.of())).orElseThrow();
        BlockBox bounds = ClientSweepScene.plateBox(area, frame, origin, frontSide, 64, 40, ClientSweepScene.APERTURE_PADDING);
        return new ClientSweep(geometry, bounds, HYSTERESIS);
    }

    private static LongOpenHashSet fresh(ApertureDescriptor geometry, BlockBox bounds, double hysteresis, double x, double y, double z) {
        ClientSweep sweep = new ClientSweep(geometry, bounds, hysteresis);
        sweep.sweep(x, y, z, 0.0D, 0.0D, 0.0D);
        LongArrayList entered = sweep.entered();
        return new LongOpenHashSet(entered);
    }
}
