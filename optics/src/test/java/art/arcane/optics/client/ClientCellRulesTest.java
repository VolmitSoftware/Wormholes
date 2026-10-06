package art.arcane.optics.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.scan.CellScan;
import art.arcane.optics.scan.ProjectorSample;
import art.arcane.optics.math.Face;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.ClientSweepPalette;

final class ClientCellRulesTest {
    private static final int BLACKOUT = 3;
    private static final int BACKING_STATE = 4;
    private static final int GLASS = 9;
    private static final int STONE = 10;

    @Test
    void destinationAirMirrorsTheScanAirRule() {
        ClientCellRules.Policy policy = policy(ApertureDescriptor.BLACKOUT_OFF, ApertureDescriptor.MASK_AIR_PROJECT);
        for (boolean shadowAir : new boolean[] {false, true}) {
            boolean projected = ClientCellRules.evaluate(false, ClientCellRules.AIR, false, shadowAir, policy) != ClientCellRules.KEEP_REAL;
            assertEquals(CellScan.shouldProjectAirSample(ProjectorSample.Kind.REMOTE_AIR, shadowAir), projected);
            assertEquals(CellScan.shouldProjectAirSample(ProjectorSample.Kind.MASK_AIR, shadowAir), projected);
        }
        assertEquals(ClientCellRules.AIR, ClientCellRules.evaluate(false, ClientCellRules.AIR, false, false, policy));
    }

    @Test
    void keepRealMaskAirPolicyNeverCarvesRealBlocks() {
        ClientCellRules.Policy policy = policy(ApertureDescriptor.BLACKOUT_OFF, ApertureDescriptor.MASK_AIR_KEEP_REAL);
        assertEquals(ClientCellRules.KEEP_REAL, ClientCellRules.evaluate(false, ClientCellRules.AIR, false, false, policy));
        assertEquals(STONE, ClientCellRules.evaluate(false, STONE, true, true, policy));
    }

    @Test
    void sentinelsResolveToTheStandInStates() {
        ClientCellRules.Policy shell = policy(ApertureDescriptor.BLACKOUT_SHELL, ApertureDescriptor.MASK_AIR_PROJECT);
        ClientCellRules.Policy buried = policy(ApertureDescriptor.BLACKOUT_SHELL_AND_BURIED, ApertureDescriptor.MASK_AIR_PROJECT);
        assertEquals(BACKING_STATE, ClientCellRules.evaluate(false, ClientCellRules.BACKING, false, true, shell));
        assertEquals(BACKING_STATE, ClientCellRules.evaluate(true, ClientCellRules.BACKING, false, true, shell));
        assertEquals(BACKING_STATE, ClientCellRules.evaluate(false, ClientCellRules.OCCLUDED, false, true, shell));
        assertEquals(BACKING_STATE, ClientCellRules.evaluate(true, ClientCellRules.OCCLUDED, false, false, shell));
        assertEquals(BLACKOUT, ClientCellRules.evaluate(false, ClientCellRules.OCCLUDED, false, true, buried));
        assertEquals(BACKING_STATE, ClientCellRules.evaluate(false, ClientCellRules.BACKING, false, true, buried));
    }

    @Test
    void shellCellsSealOnlyTransparentContent() {
        ClientCellRules.Policy policy = policy(ApertureDescriptor.BLACKOUT_SHELL, ApertureDescriptor.MASK_AIR_PROJECT);
        assertEquals(BLACKOUT, ClientCellRules.evaluate(true, ClientCellRules.AIR, false, true, policy));
        assertEquals(BLACKOUT, ClientCellRules.evaluate(true, ClientCellRules.AIR, false, false, policy));
        assertEquals(BLACKOUT, ClientCellRules.evaluate(true, GLASS, false, false, policy));
        assertEquals(STONE, ClientCellRules.evaluate(true, STONE, true, false, policy));
        assertEquals(GLASS, ClientCellRules.evaluate(false, GLASS, false, false, policy));
        ClientCellRules.Policy off = policy(ApertureDescriptor.BLACKOUT_OFF, ApertureDescriptor.MASK_AIR_PROJECT);
        assertEquals(GLASS, ClientCellRules.evaluate(true, GLASS, false, false, off));
        assertEquals(ClientCellRules.KEEP_REAL, ClientCellRules.evaluate(true, ClientCellRules.AIR, false, true, off));
    }

    @Test
    void sweepAndRulesReproduceEveryPanopticClaimWithAndWithoutBlackout() {
        for (ClientSweepScene scene : List.of(ClientSweepScene.rtpWall(24, 16), ClientSweepScene.floorHatch(24, 16),
            ClientSweepScene.archedDoor(24, 16))) {
            Random random = new Random(0xB1ACL);
            int blackoutClaims = 0;
            int airClaims = 0;
            int compared = 0;
            List<String> failures = new ArrayList<String>();
            for (int sample = 0; sample < 60; sample++) {
                Vec3 eye = frontEye(scene, random);
                boolean frontSide = scene.eyeFrontSide(eye);
                for (boolean blackout : new boolean[] {false, true}) {
                    ApertureDescriptor geometry = scene.geometry(frontSide,
                        blackout ? ApertureDescriptor.BLACKOUT_SHELL : ApertureDescriptor.BLACKOUT_OFF);
                    ClientSweep sweep = new ClientSweep(geometry, scene.bounds(frontSide), 0.0D);
                    sweep.sweep(eye.getX(), eye.getY(), eye.getZ(), 0.0D, 0.0D, 0.0D);
                    ClientCellRules.Policy policy = ClientCellRules.Policy.of(geometry, ClientSweepPalette.BACKING_STATE_ID);
                    ClientSweepPalette palette = new ClientSweepPalette();
                    Long2ObjectOpenHashMap<ProjectedBlockClaim<String, ClientSweepScene.SceneView>> server =
                        scene.serverClaims(eye, ProjectionRenderMode.PANOPTIC, blackout);
                    for (Long2ObjectMap.Entry<ProjectedBlockClaim<String, ClientSweepScene.SceneView>> entry : server.long2ObjectEntrySet()) {
                        long key = entry.getLongKey();
                        int x = CellKeys.unpackX(key);
                        int y = CellKeys.unpackY(key);
                        int z = CellKeys.unpackZ(key);
                        ProjectedBlockClaim<String, ClientSweepScene.SceneView> claim = entry.getValue();
                        String expected = claim.isBlackout() ? ClientSweepScene.BLACKOUT : claim.getData();
                        if (claim.isBlackout()) {
                            blackoutClaims++;
                        } else if (ClientSweepScene.AIR.equals(claim.getData())) {
                            airClaims++;
                        }
                        if (!sweep.applied(x, y, z)) {
                            failures.add("blackout=" + blackout + " eye=" + eye + " not applied " + x + "," + y + "," + z);
                            continue;
                        }
                        String destination = scene.destinationAt(frontSide, x, y, z);
                        int outcome = ClientCellRules.evaluate(sweep.shell(x, y, z), palette.id(destination),
                            ClientSweepScene.occluding(destination), ClientSweepScene.AIR.equals(ClientSweepScene.localAt(x, y, z)), policy);
                        String actual = outcome == ClientCellRules.KEEP_REAL ? "<real>" : palette.state(outcome);
                        compared++;
                        if (!expected.equals(actual)) {
                            failures.add("blackout=" + blackout + " eye=" + eye + " at " + x + "," + y + "," + z + " server=" + expected
                                + " client=" + actual + " shell=" + sweep.shell(x, y, z) + " destination=" + destination);
                        }
                    }
                }
            }
            assertTrue(failures.isEmpty(), failures.size() + " rule failures, first: " + failures.subList(0, Math.min(8, failures.size())));
            assertTrue(blackoutClaims > 0, "blackout shell claims must be exercised");
            assertTrue(airClaims > 0, "mask air claims must be exercised");
            assertTrue(compared > 10_000, "the mirror must compare a meaningful claim set, got " + compared);
        }
    }

    private static Vec3 frontEye(ClientSweepScene scene, Random random) {
        Face normal = scene.localFrame.getNormal();
        Frame frame = scene.localFrame;
        double side = random.nextBoolean() ? 1.0D : -1.0D;
        double along = side * (8.0D + (random.nextDouble() * 8.0D));
        double right = (random.nextDouble() - 0.5D) * 2.0D;
        double up = (random.nextDouble() - 0.5D) * 2.0D;
        Vec3 origin = scene.localOrigin;
        return ClientViewSweepParityTest.quantized(
            origin.getX() + (normal.x() * along) + (frame.getRight().x() * right) + (frame.getUp().x() * up),
            origin.getY() + (normal.y() * along) + (frame.getRight().y() * right) + (frame.getUp().y() * up),
            origin.getZ() + (normal.z() * along) + (frame.getRight().z() * right) + (frame.getUp().z() * up));
    }

    private static ClientCellRules.Policy policy(int blackoutPolicy, int maskAirPolicy) {
        return new ClientCellRules.Policy(blackoutPolicy, BLACKOUT, BACKING_STATE, maskAirPolicy);
    }
}
