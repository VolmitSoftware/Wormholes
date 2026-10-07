package art.arcane.optics.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.volume.LodProfile;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

public final class PassPlannerPropertyTest {
    private static final int SCENES = 400;
    private static final Face[] FACES = Face.values();
    private static final AtmosphereMode[] ATMOSPHERES = AtmosphereMode.values();
    private static final LodProfile[] PROFILES = LodProfile.values();

    @Test
    public void anyChangeToAHashedInputRescansInsteadOfReusing() {
        Random random = new Random(0x0B71C5L);
        List<Mutation> mutations = mutations();
        for (int sceneIndex = 0; sceneIndex < SCENES; sceneIndex++) {
            Scene scene = Scene.random(random);
            PassInputs inputs = scene.settled();
            assertSame(PassPlan.REUSE, PassPlanner.plan(inputs), "scene " + sceneIndex + " must start reusable");
            for (Mutation mutation : mutations) {
                Scene changed = scene.copy();
                mutation.apply().accept(changed, random);
                if (!changed.hashedDiffers(scene)) {
                    continue;
                }
                PassInputs mutated = scene.settled();
                changed.describe(mutated);
                PassPlan plan = PassPlanner.plan(mutated);
                String context = "scene " + sceneIndex + " mutation " + mutation.name();
                assertFalse(PassPlanner.reusable(mutated), context);
                assertTrue(plan.kind() == PassPlan.Kind.RESCAN || plan.kind() == PassPlan.Kind.RESUME_OCCLUSION, context);
                assertTrue(plan.has(PassPlan.CONTENT_INVALIDATED), context);
                assertTrue((plan.reasons() & ResampleReasons.PRESENTATION) != 0, context);
            }
        }
    }

    @Test
    public void unhashedFactsAndSmallEyeDriftLeaveTheRevisionAlone() {
        Random random = new Random(0x5EEDL);
        for (int sceneIndex = 0; sceneIndex < SCENES; sceneIndex++) {
            Scene scene = Scene.random(random);
            PassInputs inputs = scene.settled();
            long revision = PassPlanner.revision(inputs);
            inputs.resample(random.nextBoolean(), random.nextBoolean());
            inputs.projection(random.nextBoolean(), random.nextBoolean(), random.nextBoolean());
            inputs.sampler(random.nextBoolean(), random.nextBoolean());
            inputs.samples(random.nextBoolean(), random.nextBoolean(), random.nextBoolean());
            inputs.fitted(random.nextBoolean(), random.nextBoolean());
            inputs.camera(random.nextBoolean(), random.nextDouble(), random.nextDouble(), random.nextDouble());
            double drift = random.nextDouble() * 0.2D;
            Face normal = scene.localFrame.getNormal();
            inputs.eye(scene.eyeX + drift * normal.x(), scene.eyeY + drift * normal.y(), scene.eyeZ + drift * normal.z());
            assertEquals(revision, PassPlanner.revision(inputs), "scene " + sceneIndex);
        }
    }

    @Test
    public void equalInputsHashEqually() {
        Random random = new Random(42L);
        for (int sceneIndex = 0; sceneIndex < SCENES; sceneIndex++) {
            Scene scene = Scene.random(random);
            assertEquals(PassPlanner.revision(scene.settled()), PassPlanner.revision(scene.copy().settled()));
        }
    }

    private static List<Mutation> mutations() {
        return List.of(
            new Mutation("localFrame", (scene, random) -> scene.localFrame = frame(random)),
            new Mutation("remoteFrame", (scene, random) -> scene.remoteFrame = frame(random)),
            new Mutation("localOrigin", (scene, random) -> scene.localX += 1.0D + random.nextInt(8)),
            new Mutation("remoteOrigin", (scene, random) -> scene.remoteY += 0.5D + random.nextInt(8)),
            new Mutation("mirror", (scene, random) -> scene.mirror = !scene.mirror),
            new Mutation("quarterTurns", (scene, random) -> scene.quarterTurns = (scene.quarterTurns + 1 + random.nextInt(3)) & 3),
            new Mutation("depth", (scene, random) -> scene.depth += 1 + random.nextInt(16)),
            new Mutation("lateral", (scene, random) -> scene.lateral += 1 + random.nextInt(16)),
            new Mutation("projectionDistance", (scene, random) -> scene.projectionDistance -= 1.0D + random.nextInt(16)),
            new Mutation("aperturePadding", (scene, random) -> scene.aperturePadding += 0.25D),
            new Mutation("nearPlanePadding", (scene, random) -> scene.nearPlanePadding += 0.125D),
            new Mutation("cullingRatio", (scene, random) -> scene.cullingRatio *= 0.5D),
            new Mutation("revealMargin", (scene, random) -> scene.revealMargin += 1.0D),
            new Mutation("maxCells", (scene, random) -> scene.maxCells += 1 + random.nextInt(1_000)),
            new Mutation("recursionDepth", (scene, random) -> scene.recursionDepth += 1),
            new Mutation("buriedCellCulling", (scene, random) -> scene.buriedCellCulling = !scene.buriedCellCulling),
            new Mutation("observerOcclusion", (scene, random) -> scene.observerOcclusion = !scene.observerOcclusion),
            new Mutation("lodProfile", (scene, random) -> scene.lodProfile = PROFILES[(scene.lodProfile.ordinal() + 1) % PROFILES.length]),
            new Mutation("lodMergeRuns", (scene, random) -> scene.lodMergeRuns = !scene.lodMergeRuns),
            new Mutation("lodDistance", (scene, random) -> scene.lodDistance += 1 + random.nextInt(8)),
            new Mutation("lodCutoff", (scene, random) -> scene.lodCutoff += 1 + random.nextInt(8)),
            new Mutation("blockEntities", (scene, random) -> scene.blockEntities = !scene.blockEntities),
            new Mutation("blackout", (scene, random) -> scene.blackout = !scene.blackout),
            new Mutation("blackoutColour", (scene, random) -> {
                scene.blackout = true;
                scene.blackoutColor = (scene.blackoutColor + 1 + random.nextInt(15)) & 15;
            }),
            new Mutation("fogPlate", (scene, random) -> {
                scene.blackout = true;
                scene.fogPlate = !scene.fogPlate;
            }),
            new Mutation("atmosphere", (scene, random) -> scene.atmosphere = ATMOSPHERES[(scene.atmosphere.ordinal() + 1) % ATMOSPHERES.length]),
            new Mutation("apertureRevision", (scene, random) -> scene.apertureRevision += 1 + random.nextInt(4)),
            new Mutation("destinationIdentity", (scene, random) -> scene.destinationIdentity ^= 1L + random.nextLong()),
            new Mutation("eyeSide", (scene, random) -> {
                Face normal = scene.localFrame.getNormal();
                double toPlane = (scene.eyeX - scene.localX) * normal.x() + (scene.eyeY - scene.localY) * normal.y()
                    + (scene.eyeZ - scene.localZ) * normal.z();
                double across = -2.0D * toPlane - 0.5D;
                scene.eyeX += across * normal.x();
                scene.eyeY += across * normal.y();
                scene.eyeZ += across * normal.z();
                scene.cameraFollowsEye = false;
            }));
    }

    private static Frame frame(Random random) {
        return Frame.canonical(FACES[random.nextInt(FACES.length)]);
    }

    private record Mutation(String name, BiConsumer<Scene, Random> apply) {
    }

    private static final class Scene {
        private Frame localFrame;
        private Frame remoteFrame;
        private double localX;
        private double localY;
        private double localZ;
        private double remoteX;
        private double remoteY;
        private double remoteZ;
        private boolean mirror;
        private int quarterTurns;
        private int depth;
        private int lateral;
        private double projectionDistance;
        private double aperturePadding;
        private double nearPlanePadding;
        private double cullingRatio;
        private double revealMargin;
        private int maxCells;
        private int recursionDepth;
        private boolean buriedCellCulling;
        private boolean observerOcclusion;
        private LodProfile lodProfile;
        private boolean lodMergeRuns;
        private int lodDistance;
        private int lodCutoff;
        private boolean blockEntities;
        private boolean blackout;
        private int blackoutColor;
        private boolean fogPlate;
        private AtmosphereMode atmosphere;
        private long apertureRevision;
        private long destinationIdentity;
        private double eyeX;
        private double eyeY;
        private double eyeZ;
        private double cameraX;
        private double cameraY;
        private double cameraZ;
        private boolean cameraFollowsEye = true;

        private static Scene random(Random random) {
            Scene scene = new Scene();
            scene.localFrame = frame(random);
            scene.remoteFrame = frame(random);
            scene.localX = random.nextInt(2_000) - 1_000 + 0.5D;
            scene.localY = random.nextInt(200) + 0.5D;
            scene.localZ = random.nextInt(2_000) - 1_000 + 0.5D;
            scene.remoteX = random.nextInt(2_000) - 1_000 + 0.5D;
            scene.remoteY = random.nextInt(200) + 0.5D;
            scene.remoteZ = random.nextInt(2_000) - 1_000 + 0.5D;
            scene.mirror = random.nextBoolean();
            scene.quarterTurns = random.nextInt(4);
            scene.depth = 8 + random.nextInt(120);
            scene.lateral = random.nextInt(64);
            scene.projectionDistance = 16.0D + random.nextInt(256);
            scene.aperturePadding = random.nextInt(4) * 0.25D;
            scene.nearPlanePadding = random.nextInt(4) * 0.125D;
            scene.cullingRatio = 0.25D + random.nextInt(4) * 0.25D;
            scene.revealMargin = random.nextInt(10);
            scene.maxCells = 1_000 + random.nextInt(50_000);
            scene.recursionDepth = random.nextInt(4);
            scene.buriedCellCulling = random.nextBoolean();
            scene.observerOcclusion = random.nextBoolean();
            scene.lodProfile = PROFILES[random.nextInt(PROFILES.length)];
            scene.lodMergeRuns = random.nextBoolean();
            scene.lodDistance = random.nextInt(64);
            scene.lodCutoff = random.nextInt(64);
            scene.blockEntities = random.nextBoolean();
            scene.blackout = random.nextBoolean();
            scene.blackoutColor = random.nextInt(16);
            scene.fogPlate = random.nextBoolean();
            scene.atmosphere = ATMOSPHERES[random.nextInt(ATMOSPHERES.length)];
            scene.apertureRevision = random.nextInt(1_000);
            scene.destinationIdentity = random.nextLong();
            Face normal = scene.localFrame.getNormal();
            double out = 1.0D + random.nextInt(20);
            scene.eyeX = scene.localX + out * normal.x() + random.nextDouble();
            scene.eyeY = scene.localY + out * normal.y() + random.nextDouble();
            scene.eyeZ = scene.localZ + out * normal.z() + random.nextDouble();
            return scene;
        }

        private Scene copy() {
            Scene copy = new Scene();
            copy.localFrame = localFrame;
            copy.remoteFrame = remoteFrame;
            copy.localX = localX;
            copy.localY = localY;
            copy.localZ = localZ;
            copy.remoteX = remoteX;
            copy.remoteY = remoteY;
            copy.remoteZ = remoteZ;
            copy.mirror = mirror;
            copy.quarterTurns = quarterTurns;
            copy.depth = depth;
            copy.lateral = lateral;
            copy.projectionDistance = projectionDistance;
            copy.aperturePadding = aperturePadding;
            copy.nearPlanePadding = nearPlanePadding;
            copy.cullingRatio = cullingRatio;
            copy.revealMargin = revealMargin;
            copy.maxCells = maxCells;
            copy.recursionDepth = recursionDepth;
            copy.buriedCellCulling = buriedCellCulling;
            copy.observerOcclusion = observerOcclusion;
            copy.lodProfile = lodProfile;
            copy.lodMergeRuns = lodMergeRuns;
            copy.lodDistance = lodDistance;
            copy.lodCutoff = lodCutoff;
            copy.blockEntities = blockEntities;
            copy.blackout = blackout;
            copy.blackoutColor = blackoutColor;
            copy.fogPlate = fogPlate;
            copy.atmosphere = atmosphere;
            copy.apertureRevision = apertureRevision;
            copy.destinationIdentity = destinationIdentity;
            copy.eyeX = eyeX;
            copy.eyeY = eyeY;
            copy.eyeZ = eyeZ;
            return copy;
        }

        private boolean hashedDiffers(Scene other) {
            return !localFrame.equals(other.localFrame) || !remoteFrame.equals(other.remoteFrame)
                || localX != other.localX || localY != other.localY || localZ != other.localZ
                || remoteX != other.remoteX || remoteY != other.remoteY || remoteZ != other.remoteZ
                || mirror != other.mirror || quarterTurns != other.quarterTurns || depth != other.depth || lateral != other.lateral
                || projectionDistance != other.projectionDistance || aperturePadding != other.aperturePadding
                || nearPlanePadding != other.nearPlanePadding || cullingRatio != other.cullingRatio || revealMargin != other.revealMargin
                || maxCells != other.maxCells || recursionDepth != other.recursionDepth || buriedCellCulling != other.buriedCellCulling
                || observerOcclusion != other.observerOcclusion || lodProfile != other.lodProfile || lodMergeRuns != other.lodMergeRuns
                || lodDistance != other.lodDistance || lodCutoff != other.lodCutoff || blockEntities != other.blockEntities
                || blackout != other.blackout || blackout && (blackoutColor != other.blackoutColor || fogPlate != other.fogPlate)
                || atmosphere != other.atmosphere || apertureRevision != other.apertureRevision
                || destinationIdentity != other.destinationIdentity || frontSide() != other.frontSide();
        }

        private boolean frontSide() {
            Face normal = localFrame.getNormal();
            return (eyeX - localX) * normal.x() + (eyeY - localY) * normal.y() + (eyeZ - localZ) * normal.z() >= 0.0D;
        }

        private void describe(PassInputs inputs) {
            inputs.local(localFrame, localX, localY, localZ);
            inputs.remote(remoteFrame, remoteX, remoteY, remoteZ);
            inputs.mirror(mirror, quarterTurns);
            inputs.extent(depth, lateral, projectionDistance);
            inputs.padding(aperturePadding, nearPlanePadding);
            inputs.frustum(cullingRatio, revealMargin);
            inputs.limits(maxCells, recursionDepth);
            inputs.scanMode(new ScanMode(buriedCellCulling, observerOcclusion));
            inputs.lod(lodProfile, lodMergeRuns, lodDistance, lodCutoff);
            inputs.blockEntities(blockEntities);
            inputs.blackout(blackout, blackoutColor, fogPlate);
            inputs.atmosphere(atmosphere);
            inputs.identity(apertureRevision, destinationIdentity);
            inputs.eye(eyeX, eyeY, eyeZ);
            if (cameraFollowsEye) {
                inputs.camera(true, eyeX, eyeY, eyeZ);
            }
        }

        private PassInputs settled() {
            PassInputs inputs = new PassInputs();
            describe(inputs);
            inputs.camera(true, eyeX, eyeY, eyeZ);
            inputs.projection(true, false, false);
            inputs.dissolving(false);
            inputs.unresolvedOcclusion(false);
            inputs.resample(false, false);
            inputs.lightingDue(false);
            inputs.localDirty(false);
            inputs.holdsExposed(false);
            inputs.sampler(false, false);
            inputs.fitted(false, false);
            inputs.samples(false, false, false);
            inputs.committed(true, PassPlanner.revision(inputs), false);
            return inputs;
        }
    }
}
