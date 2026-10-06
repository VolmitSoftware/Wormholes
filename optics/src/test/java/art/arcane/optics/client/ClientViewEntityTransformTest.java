package art.arcane.optics.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.entity.ItemFrameTransform;
import art.arcane.optics.math.Face;

class ClientViewEntityTransformTest {
    private static final long SECRET = 0x5EC12E7L;

    @Test
    void nativeModelsKeepSourceFeetLookAndMetadataForEveryRollWhilePacketModelsStayProjected() {
        ClientViewEntityTransform transform = new ClientViewEntityTransform();
        EntitySnapshot source = visual(UUID.randomUUID(), 200.5D, 64.0D, 196.5D, 0.0D, 0.0D, -1.0D);
        for (Face up : new Face[] {Face.U, Face.E, Face.D, Face.W}) {
            ClientViewEntityTransform.EntityFrame frame = new ClientViewEntityTransform.EntityFrame(10.5D, 64, 20.5D, Frame.canonical(Face.S),
                200.5D, 64, 200.5D, Frame.fromNormalUp(Face.S, up), false, 0, true, 24);
            EntitySnapshot nativeModel = transform.nativeModel(source, frame, false, SECRET).visual();
            EntitySnapshot packet = transform.project(source, frame, false, false, SECRET).visual();
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
        ClientViewEntityTransform transform = new ClientViewEntityTransform();
        for (int quarter = 0; quarter < 4; quarter++) {
            ClientViewEntityTransform.EntityFrame mirror = new ClientViewEntityTransform.EntityFrame(10.5D, 64, 20.5D, Frame.canonical(Face.S),
                10.5D, 64, 20.5D, Frame.canonical(Face.S), true, quarter, true, 24);
            EntitySnapshot source = visual(UUID.randomUUID(), 10.5D, 64.25D, 24.5D, 0, 0, -1);
            ClientViewEntityTransform.Projected result = transform.nativeModel(source, mirror, true, SECRET);
            assertNotNull(result);
            assertEquals(source.x(), result.visual().x());
            assertEquals(source.y(), result.visual().y());
            assertEquals(source.z(), result.visual().z());
            assertEquals(ItemFrameTransform.NONE, result.metadataTransform());
            EntitySnapshot wrongSide = visual(UUID.randomUUID(), 10.5D, 64.25D, 16.5D, 0, 0, -1);
            assertNull(transform.nativeModel(wrongSide, mirror, true, SECRET));
        }
    }

    @Test
    void entityBehindTheDestinationLandsBehindTheLocalPortalInLocalSpace() {
        ClientViewEntityTransform transform = new ClientViewEntityTransform();
        ClientViewEntityTransform.EntityFrame frame = straightFrame();
        EntitySnapshot visual = visual(UUID.randomUUID(), 200.5D, 64.0D, 196.5D, 0.0D, 0.0D, -1.0D);
        ClientViewEntityTransform.Projected projected = transform.project(visual, frame, false, false, SECRET);
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
        ClientViewEntityTransform transform = new ClientViewEntityTransform();
        EntitySnapshot visual = visual(UUID.randomUUID(), 200.5D, 64.0D, 204.5D, 0.0D, 0.0D, 1.0D);
        assertNull(transform.project(visual, straightFrame(), false, false, SECRET));
        EntitySnapshot far = visual(UUID.randomUUID(), 200.5D, 64.0D, 100.5D, 0.0D, 0.0D, 1.0D);
        assertNull(transform.project(far, straightFrame(), false, false, SECRET), "entities past the depth are culled");
    }

    @Test
    void rotatedDestinationRotatesPositionLookAndVelocity() {
        ClientViewEntityTransform transform = new ClientViewEntityTransform();
        ClientViewEntityTransform.EntityFrame frame = new ClientViewEntityTransform.EntityFrame(10.5D, 64.0D, 20.5D, Frame.canonical(Face.S),
            200.5D, 64.0D, 200.5D, Frame.canonical(Face.E), false, 0, true, 24.0D);
        EntitySnapshot visual = new EntitySnapshot(EntitySnapshot.MODE_FULL, 0, EntitySnapshot.FIELD_ALL_FULL, UUID.randomUUID(), "minecraft:pig",
            196.5D, 64.0D, 200.5D, 0.9D, -1.0D, 0.0D, 0.0D, 90.0F, 0.0F, -0.2D, 0.0D, 0.0D, true, "", "", "", null, null,
            EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY);
        ClientViewEntityTransform.Projected projected = transform.project(visual, frame, false, false, SECRET);
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
        UUID first = ClientViewEntityTransform.opaque(SECRET, source);
        assertEquals(first, ClientViewEntityTransform.opaque(SECRET, source));
        assertNotEquals(source, first);
        assertNotEquals(first, ClientViewEntityTransform.opaque(SECRET + 1L, source));
        assertEquals(4, first.version());
        assertNull(ClientViewEntityTransform.opaque(SECRET, null));
        ClientViewEntityTransform transform = new ClientViewEntityTransform();
        UUID vehicle = UUID.randomUUID();
        EntitySnapshot rider = new EntitySnapshot(EntitySnapshot.MODE_FULL, 0, EntitySnapshot.FIELD_ALL_FULL, source, "minecraft:zombie",
            200.5D, 64.0D, 196.5D, 1.9D, 0.0D, 0.0D, -1.0D, 180.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "", "", "", vehicle, null,
            EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY);
        EntitySnapshot local = transform.project(rider, straightFrame(), false, false, SECRET).visual();
        assertEquals(first, local.id());
        assertEquals(ClientViewEntityTransform.opaque(SECRET, vehicle), local.passengerOf());
    }

    @Test
    void verticalFlipIsReportedForUpsideDownLinks() {
        ClientViewEntityTransform transform = new ClientViewEntityTransform();
        assertFalse(transform.upsideDown(straightFrame()));
        ClientViewEntityTransform.EntityFrame floor = new ClientViewEntityTransform.EntityFrame(10.5D, 64.0D, 20.5D, Frame.canonical(Face.S),
            200.5D, 64.0D, 200.5D, Frame.fromNormalUp(Face.S, Face.D), false, 0, true, 24.0D);
        assertTrue(transform.upsideDown(floor));
    }

    private static ClientViewEntityTransform.EntityFrame straightFrame() {
        return new ClientViewEntityTransform.EntityFrame(10.5D, 64.0D, 20.5D, Frame.canonical(Face.S), 200.5D, 64.0D, 200.5D,
            Frame.canonical(Face.S), false, 0, true, 24.0D);
    }

    private static EntitySnapshot visual(UUID id, double x, double y, double z, double lookX, double lookY, double lookZ) {
        return new EntitySnapshot(EntitySnapshot.MODE_FULL, 0, EntitySnapshot.FIELD_ALL_FULL, id, "minecraft:armor_stand", x, y, z, 1.975D,
            lookX, lookY, lookZ, 180.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "", "", "", null, null, new byte[] {1, 2}, EntitySnapshot.EMPTY,
            new byte[] {9});
    }
}
