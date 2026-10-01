package art.arcane.wormholes.render.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.ProjectedItemFrameTransform;
import art.arcane.wormholes.util.Direction;

class ClientViewEntityTransformTest {
    private static final long SECRET = 0x5EC12E7L;

    @Test
    void entityBehindTheDestinationLandsBehindTheLocalPortalInLocalSpace() {
        ClientViewEntityTransform transform = new ClientViewEntityTransform();
        ClientViewEntityTransform.Frame frame = straightFrame();
        EntityVisual visual = visual(UUID.randomUUID(), 200.5D, 64.0D, 196.5D, 0.0D, 0.0D, -1.0D);
        ClientViewEntityTransform.Projected projected = transform.project(visual, frame, false, false, SECRET);
        assertNotNull(projected);
        EntityVisual local = projected.visual();
        assertEquals(10.5D, local.x(), 1.0E-6D);
        assertEquals(64.0D, local.y(), 1.0E-6D);
        assertEquals(16.5D, local.z(), 1.0E-6D);
        assertEquals(-1.0D, local.lookZ(), 1.0E-6D);
        assertEquals(EntityVisual.MODE_FULL, local.mode());
        assertEquals(EntityVisual.EMPTY.length, local.mapData().length);
        assertEquals(ProjectedItemFrameTransform.NONE, projected.metadataTransform());
    }

    @Test
    void entityOnTheWrongSideOfTheDestinationIsCulled() {
        ClientViewEntityTransform transform = new ClientViewEntityTransform();
        EntityVisual visual = visual(UUID.randomUUID(), 200.5D, 64.0D, 204.5D, 0.0D, 0.0D, 1.0D);
        assertNull(transform.project(visual, straightFrame(), false, false, SECRET));
        EntityVisual far = visual(UUID.randomUUID(), 200.5D, 64.0D, 100.5D, 0.0D, 0.0D, 1.0D);
        assertNull(transform.project(far, straightFrame(), false, false, SECRET), "entities past the depth are culled");
    }

    @Test
    void rotatedDestinationRotatesPositionLookAndVelocity() {
        ClientViewEntityTransform transform = new ClientViewEntityTransform();
        ClientViewEntityTransform.Frame frame = new ClientViewEntityTransform.Frame(10.5D, 64.0D, 20.5D, PortalFrame.canonical(Direction.S),
            200.5D, 64.0D, 200.5D, PortalFrame.canonical(Direction.E), false, 0, true, 24.0D);
        EntityVisual visual = new EntityVisual(EntityVisual.MODE_FULL, 0, EntityVisual.FIELD_ALL_FULL, UUID.randomUUID(), "minecraft:pig",
            196.5D, 64.0D, 200.5D, 0.9D, -1.0D, 0.0D, 0.0D, 90.0F, 0.0F, -0.2D, 0.0D, 0.0D, true, "", "", "", null, null,
            EntityVisual.EMPTY, EntityVisual.EMPTY, EntityVisual.EMPTY);
        ClientViewEntityTransform.Projected projected = transform.project(visual, frame, false, false, SECRET);
        assertNotNull(projected);
        EntityVisual local = projected.visual();
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
        EntityVisual rider = new EntityVisual(EntityVisual.MODE_FULL, 0, EntityVisual.FIELD_ALL_FULL, source, "minecraft:zombie",
            200.5D, 64.0D, 196.5D, 1.9D, 0.0D, 0.0D, -1.0D, 180.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "", "", "", vehicle, null,
            EntityVisual.EMPTY, EntityVisual.EMPTY, EntityVisual.EMPTY);
        EntityVisual local = transform.project(rider, straightFrame(), false, false, SECRET).visual();
        assertEquals(first, local.id());
        assertEquals(ClientViewEntityTransform.opaque(SECRET, vehicle), local.passengerOf());
    }

    @Test
    void verticalFlipIsReportedForUpsideDownLinks() {
        ClientViewEntityTransform transform = new ClientViewEntityTransform();
        assertFalse(transform.upsideDown(straightFrame()));
        ClientViewEntityTransform.Frame floor = new ClientViewEntityTransform.Frame(10.5D, 64.0D, 20.5D, PortalFrame.canonical(Direction.S),
            200.5D, 64.0D, 200.5D, PortalFrame.fromNormalUp(Direction.S, Direction.D), false, 0, true, 24.0D);
        assertTrue(transform.upsideDown(floor));
    }

    private static ClientViewEntityTransform.Frame straightFrame() {
        return new ClientViewEntityTransform.Frame(10.5D, 64.0D, 20.5D, PortalFrame.canonical(Direction.S), 200.5D, 64.0D, 200.5D,
            PortalFrame.canonical(Direction.S), false, 0, true, 24.0D);
    }

    private static EntityVisual visual(UUID id, double x, double y, double z, double lookX, double lookY, double lookZ) {
        return new EntityVisual(EntityVisual.MODE_FULL, 0, EntityVisual.FIELD_ALL_FULL, id, "minecraft:armor_stand", x, y, z, 1.975D,
            lookX, lookY, lookZ, 180.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "", "", "", null, null, new byte[] {1, 2}, EntityVisual.EMPTY,
            new byte[] {9});
    }
}
