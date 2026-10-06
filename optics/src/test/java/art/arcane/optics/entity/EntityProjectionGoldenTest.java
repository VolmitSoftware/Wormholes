package art.arcane.optics.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.volume.ViewVolume;

final class EntityProjectionGoldenTest {
    static final long SECRET = 0x5EC12E7L;
    static final int SAMPLES_PER_PAIR = 3;
    static final double DEPTH = 24.0D;
    private static final long SWEEP = 0x32C6C741E5604AE4L;

    @Test
    void serverAndClientProjectionsMatchTheRecordedSweep() {
        ViewVolume frustum = openFrustum();
        EntityProjection projection = new EntityProjection();
        long value = sweep((scenario, digest) -> {
            OpticTransform transform = window(scenario).transform();
            EntitySnapshot visual = scenario.visual();
            boolean visible = projection.project(visual, transform, frustum, scenario.itemFrame(), scenario.hanging());
            digest.add(visible ? 1L : 0L);
            if (visible) {
                digest.add(projection.x());
                digest.add(projection.y());
                digest.add(projection.z());
                digest.add(projection.velocityX());
                digest.add(projection.velocityY());
                digest.add(projection.velocityZ());
                digest.look(projection.yaw(), projection.pitch());
                digest.add(projection.metadataTransform());
            }
            ViewWindow window = window(scenario);
            digest.add(window.transform().flipsWorldUp() ? 1L : 0L);
            EntityProjection.Projected projected = projection.project(visual, window, scenario.hanging(), scenario.itemFrame(), SECRET);
            digest.add(projected == null ? 0L : 1L);
            if (projected != null) {
                EntitySnapshot local = projected.visual();
                digest.add(local.x());
                digest.add(local.y());
                digest.add(local.z());
                digest.add(local.lookX());
                digest.add(local.lookY());
                digest.add(local.lookZ());
                digest.look(local.yaw(), local.pitch());
                digest.add(local.velocityX());
                digest.add(local.velocityY());
                digest.add(local.velocityZ());
                digest.add(projected.metadataTransform());
                digest.add(local.id().getMostSignificantBits());
                digest.add(local.id().getLeastSignificantBits());
            }
            EntityProjection.Projected model = projection.nativeModel(visual, window, scenario.hanging(), SECRET);
            digest.add(model == null ? 0L : 1L);
            if (model != null) {
                digest.add(model.visual().x());
                digest.add(model.visual().y());
                digest.add(model.visual().z());
                digest.add(model.metadataTransform());
            }
        });
        assertEquals(Long.toHexString(SWEEP), Long.toHexString(value));
    }

    @Test
    void paintingSnapshotUsesTheSameTransformedBlockAnchorAsLiveProjection() {
        Frame local = Frame.canonical(Face.E);
        Frame remote = Frame.canonical(Face.N);
        EntitySnapshot visual = EntitySnapshot.full(UUID.randomUUID(), "minecraft:painting", 7.0D, 2.5D, -1.5D, 2.0D,
            0, 0, -1, 0, 0, 0, 0, 0, false, "", "", "", null, null, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, 0);
        EntityProjection projection = new EntityProjection();
        OpticTransform transform = OpticTransform.between(remote, new Vec3d(0, 0, 0), local, new Vec3d(0, 0, 0));
        assertTrue(projection.project(visual, transform, openFrustum(), false, true));
        assertEquals(new Vec3d(1, 2, 7), new Vec3d(projection.x(), projection.y(), projection.z()));
    }

    @Test
    void nativeModelsKeepSourceFeetLookAndMetadataForEveryRollWhilePacketModelsStayProjected() {
        EntityProjection projection = new EntityProjection();
        EntitySnapshot source = visual(UUID.randomUUID(), 200.5D, 64.0D, 196.5D, 0.0D, 0.0D, -1.0D);
        for (Face up : new Face[] {Face.U, Face.E, Face.D, Face.W}) {
            ViewWindow window = ViewWindow.between(new Vec3d(10.5D, 64, 20.5D), Frame.canonical(Face.S), new Vec3d(200.5D, 64, 200.5D),
                Frame.fromNormalUp(Face.S, up), true, 24);
            EntitySnapshot nativeModel = projection.nativeModel(source, window, false, SECRET).visual();
            EntitySnapshot packet = projection.project(source, window, false, false, SECRET).visual();
            assertEquals(source.x(), nativeModel.x());
            assertEquals(source.y(), nativeModel.y());
            assertEquals(source.z(), nativeModel.z());
            assertEquals(source.yaw(), nativeModel.yaw());
            assertEquals(source.pitch(), nativeModel.pitch());
            assertEquals(source.lookX(), nativeModel.lookX());
            assertEquals(source.lookY(), nativeModel.lookY());
            assertEquals(source.lookZ(), nativeModel.lookZ());
            assertSame(source.metadata(), nativeModel.metadata());
            assertSame(source.equipment(), nativeModel.equipment());
            assertEquals(packet.id(), nativeModel.id());
            assertNotEquals(source.id(), nativeModel.id());
            assertNotEquals(nativeModel.z(), packet.z());
        }
    }

    @Test
    void nativeMirrorModelsKeepSourceAnchorsAndCullUsingTheProjectedVolume() {
        EntityProjection projection = new EntityProjection();
        for (QuarterTurn turns : QuarterTurn.values()) {
            ViewWindow mirror = ViewWindow.mirror(new Vec3d(10.5D, 64, 20.5D), Frame.canonical(Face.S), turns, true, 24);
            EntitySnapshot source = visual(UUID.randomUUID(), 10.5D, 64.25D, 24.5D, 0, 0, -1);
            EntityProjection.Projected result = projection.nativeModel(source, mirror, true, SECRET);
            assertNotNull(result);
            assertEquals(source.x(), result.visual().x());
            assertEquals(source.y(), result.visual().y());
            assertEquals(source.z(), result.visual().z());
            assertEquals(ItemFrameTransform.NONE, result.metadataTransform());
            EntitySnapshot wrongSide = visual(UUID.randomUUID(), 10.5D, 64.25D, 16.5D, 0, 0, -1);
            assertNull(projection.nativeModel(wrongSide, mirror, true, SECRET));
        }
    }

    @Test
    void entityBehindTheDestinationLandsBehindTheLocalPortalInLocalSpace() {
        EntityProjection projection = new EntityProjection();
        EntitySnapshot visual = visual(UUID.randomUUID(), 200.5D, 64.0D, 196.5D, 0.0D, 0.0D, -1.0D);
        EntityProjection.Projected projected = projection.project(visual, straightWindow(), false, false, SECRET);
        assertNotNull(projected);
        EntitySnapshot local = projected.visual();
        assertEquals(10.5D, local.x(), 1.0E-6D);
        assertEquals(64.0D, local.y(), 1.0E-6D);
        assertEquals(16.5D, local.z(), 1.0E-6D);
        assertEquals(-1.0D, local.lookZ(), 1.0E-6D);
        assertEquals(EntitySnapshot.MODE_FULL, local.mode());
        assertEquals(EntitySnapshot.EMPTY.length, local.mapData().length);
        assertEquals(ItemFrameTransform.NONE, projected.metadataTransform());
    }

    @Test
    void entityOnTheWrongSideOfTheDestinationIsCulled() {
        EntityProjection projection = new EntityProjection();
        EntitySnapshot visual = visual(UUID.randomUUID(), 200.5D, 64.0D, 204.5D, 0.0D, 0.0D, 1.0D);
        assertNull(projection.project(visual, straightWindow(), false, false, SECRET));
        EntitySnapshot far = visual(UUID.randomUUID(), 200.5D, 64.0D, 100.5D, 0.0D, 0.0D, 1.0D);
        assertNull(projection.project(far, straightWindow(), false, false, SECRET), "entities past the depth are culled");
    }

    @Test
    void rotatedDestinationRotatesPositionLookAndVelocity() {
        EntityProjection projection = new EntityProjection();
        ViewWindow window = ViewWindow.between(new Vec3d(10.5D, 64.0D, 20.5D), Frame.canonical(Face.S), new Vec3d(200.5D, 64.0D, 200.5D),
            Frame.canonical(Face.E), true, 24.0D);
        EntitySnapshot visual = new EntitySnapshot(EntitySnapshot.MODE_FULL, 0, EntitySnapshot.FIELD_ALL_FULL, UUID.randomUUID(), "minecraft:pig",
            196.5D, 64.0D, 200.5D, 0.9D, -1.0D, 0.0D, 0.0D, 90.0F, 0.0F, -0.2D, 0.0D, 0.0D, true, "", "", "", null, null,
            EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY);
        EntityProjection.Projected projected = projection.project(visual, window, false, false, SECRET);
        assertNotNull(projected);
        EntitySnapshot local = projected.visual();
        assertEquals(10.5D, local.x(), 1.0E-6D);
        assertEquals(16.5D, local.z(), 1.0E-6D);
        assertEquals(-1.0D, local.lookZ(), 1.0E-6D);
        assertEquals(-0.2D, local.velocityZ(), 1.0E-6D);
        assertEquals(180.0F, Math.abs(local.yaw()), 1.0E-3F);
    }

    @Test
    void opaqueIdsAreStableAndHideTheSource() {
        UUID source = UUID.randomUUID();
        UUID first = EntityProjection.opaque(SECRET, source);
        assertEquals(first, EntityProjection.opaque(SECRET, source));
        assertNotEquals(source, first);
        assertNotEquals(first, EntityProjection.opaque(SECRET + 1L, source));
        assertEquals(4, first.version());
        assertNull(EntityProjection.opaque(SECRET, null));
        EntityProjection projection = new EntityProjection();
        UUID vehicle = UUID.randomUUID();
        EntitySnapshot rider = new EntitySnapshot(EntitySnapshot.MODE_FULL, 0, EntitySnapshot.FIELD_ALL_FULL, source, "minecraft:zombie",
            200.5D, 64.0D, 196.5D, 1.9D, 0.0D, 0.0D, -1.0D, 180.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "", "", "", vehicle, null,
            EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY);
        EntitySnapshot local = projection.project(rider, straightWindow(), false, false, SECRET).visual();
        assertEquals(first, local.id());
        assertEquals(EntityProjection.opaque(SECRET, vehicle), local.passengerOf());
    }

    @Test
    void verticalFlipIsReportedForUpsideDownLinks() {
        assertFalse(straightWindow().transform().flipsWorldUp());
        ViewWindow floor = ViewWindow.between(new Vec3d(10.5D, 64.0D, 20.5D), Frame.canonical(Face.S), new Vec3d(200.5D, 64.0D, 200.5D),
            Frame.fromNormalUp(Face.S, Face.D), true, 24.0D);
        assertTrue(floor.transform().flipsWorldUp());
    }

    static long sweep(Probe probe) {
        Random random = new Random(0xE7717EL);
        List<Frame> frames = frames();
        Digest digest = new Digest();
        for (Frame local : frames) {
            for (Frame remote : frames) {
                for (int sample = 0; sample < SAMPLES_PER_PAIR; sample++) {
                    Vec3d localOrigin = origin(random);
                    Vec3d remoteOrigin = origin(random);
                    boolean front = random.nextBoolean();
                    probe.sample(new Scenario(local, localOrigin, remote, remoteOrigin, false, QuarterTurn.DEGREES_0, front, DEPTH,
                        visual(random, remoteOrigin), sample % 3 == 1, sample % 3 != 0), digest);
                }
            }
        }
        for (Frame plane : frames) {
            for (QuarterTurn turns : QuarterTurn.values()) {
                for (int sample = 0; sample < SAMPLES_PER_PAIR * 4; sample++) {
                    Vec3d origin = origin(random);
                    boolean front = random.nextBoolean();
                    probe.sample(new Scenario(plane, origin, plane.flipNormal(), origin, true, turns.coherentFor(plane), front, DEPTH,
                        visual(random, origin), sample % 3 == 1, sample % 3 != 0), digest);
                }
            }
        }
        return digest.value();
    }

    private static ViewWindow window(Scenario scenario) {
        return scenario.mirror()
            ? ViewWindow.mirror(scenario.localOrigin(), scenario.localFrame(), scenario.turns(), scenario.frontSide(), scenario.depth())
            : ViewWindow.between(scenario.localOrigin(), scenario.localFrame(), scenario.remoteOrigin(), scenario.remoteFrame(),
                scenario.frontSide(), scenario.depth());
    }

    private static ViewVolume openFrustum() {
        ViewVolume frustum = mock(ViewVolume.class);
        when(frustum.containsPrimitive(anyDouble(), anyDouble(), anyDouble())).thenReturn(true);
        return frustum;
    }

    private static ViewWindow straightWindow() {
        return ViewWindow.between(new Vec3d(10.5D, 64.0D, 20.5D), Frame.canonical(Face.S), new Vec3d(200.5D, 64.0D, 200.5D),
            Frame.canonical(Face.S), true, 24.0D);
    }

    private static EntitySnapshot visual(UUID id, double x, double y, double z, double lookX, double lookY, double lookZ) {
        return new EntitySnapshot(EntitySnapshot.MODE_FULL, 0, EntitySnapshot.FIELD_ALL_FULL, id, "minecraft:armor_stand", x, y, z, 1.975D,
            lookX, lookY, lookZ, 180.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "", "", "", null, null, new byte[] {1, 2}, EntitySnapshot.EMPTY,
            new byte[] {9});
    }

    static List<Frame> frames() {
        List<Frame> frames = new ArrayList<Frame>(24);
        for (Face normal : Face.values()) {
            for (Face up : Face.values()) {
                if (normal.getAxis() != up.getAxis()) {
                    frames.add(Frame.fromNormalUp(normal, up));
                }
            }
        }
        return frames;
    }

    private static Vec3d origin(Random random) {
        return new Vec3d(originComponent(random, 30_000.0D), originComponent(random, 200.0D), originComponent(random, 30_000.0D));
    }

    private static double originComponent(Random random, double range) {
        double whole = Math.floor((random.nextDouble() * 2.0D - 1.0D) * range);
        return switch (random.nextInt(3)) {
            case 0 -> whole;
            case 1 -> whole + 0.5D;
            default -> whole + 0.4995D;
        };
    }

    private static EntitySnapshot visual(Random random, Vec3d near) {
        double x = near.x() + (random.nextDouble() * 2.0D - 1.0D) * 16.0D;
        double y = near.y() + (random.nextDouble() * 2.0D - 1.0D) * 16.0D;
        double z = near.z() + (random.nextDouble() * 2.0D - 1.0D) * 16.0D;
        float yaw = (float) (random.nextDouble() * 360.0D - 180.0D);
        float pitch = (float) (random.nextDouble() * 180.0D - 90.0D);
        Vec3d look = random.nextInt(4) == 0 ? axisLook(random) : Angles.direction(yaw, pitch);
        double height = 0.25D + random.nextDouble() * 2.0D;
        return new EntitySnapshot(EntitySnapshot.MODE_FULL, 0, EntitySnapshot.FIELD_ALL_FULL, new UUID(random.nextLong(), random.nextLong()),
            "minecraft:armor_stand", x, y, z, height, look.x(), look.y(), look.z(), yaw, pitch, random.nextDouble() - 0.5D,
            random.nextDouble() - 0.5D, random.nextDouble() - 0.5D, random.nextBoolean(), "", "", "", null, null, EntitySnapshot.EMPTY,
            EntitySnapshot.EMPTY, EntitySnapshot.EMPTY);
    }

    private static Vec3d axisLook(Random random) {
        Face face = Face.values()[random.nextInt(6)];
        return new Vec3d(face.x(), face.y(), face.z());
    }

    record Scenario(Frame localFrame, Vec3d localOrigin, Frame remoteFrame, Vec3d remoteOrigin, boolean mirror, QuarterTurn turns,
                    boolean frontSide, double depth, EntitySnapshot visual, boolean itemFrame, boolean hanging) {
    }

    interface Probe {
        void sample(Scenario scenario, Digest digest);
    }

    static final class Digest {
        private long value = 0xCBF29CE484222325L;

        void add(long bits) {
            value = (value ^ bits) * 0x100000001B3L;
        }

        void add(double value) {
            add(Double.doubleToLongBits(value + 0.0D));
        }

        void add(float value) {
            add(Float.floatToIntBits(value + 0.0F));
        }

        void look(float yaw, float pitch) {
            add(Math.abs(pitch) == 90.0F ? 0.0F : yaw == -180.0F ? 180.0F : yaw);
            add(pitch);
        }

        long value() {
            return value;
        }
    }
}
