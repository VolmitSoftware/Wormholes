package art.arcane.wormholes.door.view;

import art.arcane.wormholes.door.DoorHalf;

import art.arcane.optics.math.Vec3d;

import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.door.RuntimeDoor;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorProjectionAdapterTest {
    private static final double TOLERANCE = 1.0E-9D;
    private static final UUID WORLD_ID = new UUID(0, 400);
    private static final int RANGE = 24;

    @Test
    void aNorthFacingDoorProjectsBothOfItsCellsThroughItsRecessedSurface() {
        DoorwayPlane plane = new DoorwayPlane(10, 64, 10, Face.N);
        DoorProjectionAdapter adapter = adapter(plane, DoorItemIdentity.publicDoor(new UUID(0, 401)));

        assertEquals(2, adapter.getStructure().getBlockPositions().size());
        assertTrue(adapter.getStructure().containsBlock(10, 64, 10));
        assertTrue(adapter.getStructure().containsBlock(10, 65, 10));
        assertEquals(Face.N, adapter.getFrame().getNormal());
        assertEquals(Face.U, adapter.getFrame().getUp());

        Vec3d origin = adapter.getOrigin();
        assertEquals(10.5D, origin.getX(), TOLERANCE);
        assertEquals(65.0D, origin.getY(), TOLERANCE);
        assertEquals(10.92D, origin.getZ(), TOLERANCE);
        assertEquals(plane.center().z(), origin.getZ(), TOLERANCE);

        Box view = adapter.getView();
        Box area = adapter.getArea();
        assertEquals(area.min().getX() - RANGE, view.min().getX(), TOLERANCE);
        assertEquals(area.max().getY() + RANGE, view.max().getY(), TOLERANCE);
        assertEquals(area.min().getZ() - RANGE, view.min().getZ(), TOLERANCE);
        assertEquals(RANGE, adapter.getActivationRange());
    }

    @Test
    void aTrapdoorProjectsOneCellThroughAVerticalNormal() {
        DoorwayPlane bottom = DoorwayPlane.trapdoor(4, 70, -6, Face.S, DoorHalf.BOTTOM, DoorOpenState.OPEN);
        DoorProjectionAdapter adapter = adapter(bottom, DoorItemIdentity.publicDoor(new UUID(0, 402), art.arcane.wormholes.door.DoorForm.TRAPDOOR));

        assertEquals(1, adapter.getStructure().getBlockPositions().size());
        assertTrue(adapter.getStructure().containsBlock(4, 70, -6));
        assertEquals(Face.D, adapter.getFrame().getNormal());
        assertEquals(Face.S, adapter.getFrame().getUp());
        assertEquals(bottom.planeY(), adapter.getOrigin().y(), TOLERANCE);

        DoorwayPlane top = DoorwayPlane.trapdoor(4, 70, -6, Face.S, DoorHalf.TOP, DoorOpenState.OPEN);
        adapter.refresh(top);
        assertEquals(Face.U, adapter.getFrame().getNormal());
        assertEquals(Face.N, adapter.getFrame().getUp());
        assertEquals(top.planeY(), adapter.getOrigin().y(), TOLERANCE);
        assertEquals(1, adapter.getStructure().getBlockPositions().size());
    }

    @Test
    void anApertureIsOpenOnlyWhileItsDoorReadsPortalLive() {
        DoorwayPlane plane = new DoorwayPlane(0, 64, 0, Face.E);
        PlacedDoorEndpoint endpoint = endpoint(DoorItemIdentity.publicDoor(new UUID(0, 403)));
        RuntimeDoor door = new RuntimeDoor(endpoint);
        DoorProjectionAdapter adapter = new DoorProjectionAdapter(door, plane, world());

        assertFalse(adapter.isOpen());
        door.cycle().observe(true);
        assertTrue(adapter.isOpen());
        assertTrue(adapter.supportsProjections());
        assertTrue(adapter.isProjecting());
        assertFalse(adapter.hasTunnel());
        assertFalse(adapter.isMirrorMode());
        assertFalse(adapter.blocksProjection());
        assertFalse(adapter.isDestroyed());
        adapter.destroy();
        assertTrue(adapter.isDestroyed());
        assertEquals(endpoint.identity().itemId(), adapter.getId());
    }

    private static DoorProjectionAdapter adapter(DoorwayPlane plane, DoorItemIdentity identity) {
        return new DoorProjectionAdapter(new RuntimeDoor(endpoint(identity)), plane, world());
    }

    private static PlacedDoorEndpoint endpoint(DoorItemIdentity identity) {
        return new PlacedDoorEndpoint(new DoorPosition(WORLD_ID, "minecraft:overworld", 10, 64, 10), identity);
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(
            DoorProjectionAdapterTest.class.getClassLoader(),
            new Class<?>[]{World.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "getUID" -> WORLD_ID;
                case "getKey" -> NamespacedKey.fromString("minecraft:overworld");
                case "getName" -> "world";
                default -> null;
            });
    }
}
