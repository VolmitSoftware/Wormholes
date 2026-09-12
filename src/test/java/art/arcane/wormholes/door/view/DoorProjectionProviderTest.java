package art.arcane.wormholes.door.view;

import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.door.RuntimeDoor;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.rtp.RtpProjectionView;
import art.arcane.wormholes.util.Direction;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorProjectionProviderTest {
    private static final UUID WORLD_ID = new UUID(0, 700);
    private static final World WORLD = world();
    private static final UUID OBSERVER_ID = new UUID(0, 701);

    @Test
    void twoHingedDoorsMirrorSoTheViewLooksBackOutOfTheMate() {
        DoorwayPlane source = new DoorwayPlane(10, 64, 10, BlockFace.NORTH);
        DoorwayPlane mate = new DoorwayPlane(40, 64, 40, BlockFace.EAST);

        PortalFrame frame = DoorApertureFrames.destinationFrame(source, mate);

        assertEquals(Direction.W, frame.getNormal());
        assertEquals(Direction.U, frame.getUp());
    }

    @Test
    void aTrapdoorPairingIsStraightThroughAndKeepsTheMateFacing() {
        DoorwayPlane source = new DoorwayPlane(10, 64, 10, BlockFace.NORTH);
        DoorwayPlane trapdoorMate =
            DoorwayPlane.trapdoor(40, 64, 40, BlockFace.SOUTH, Bisected.Half.BOTTOM, DoorOpenState.OPEN);

        assertEquals(Direction.D, DoorApertureFrames.destinationFrame(source, trapdoorMate).getNormal());
        assertEquals(Direction.D, DoorApertureFrames.of(trapdoorMate).getNormal());

        DoorwayPlane trapdoorSource =
            DoorwayPlane.trapdoor(10, 64, 10, BlockFace.NORTH, Bisected.Half.TOP, DoorOpenState.OPEN);
        DoorwayPlane hingedMate = new DoorwayPlane(40, 64, 40, BlockFace.EAST);
        assertEquals(Direction.E, DoorApertureFrames.destinationFrame(trapdoorSource, hingedMate).getNormal());
    }

    @Test
    void aResolvedDestinationBecomesAReadyTargetTheProjectorCanAimAt() {
        DoorProjectionAdapter adapter = adapter(new DoorwayPlane(10, 64, 10, BlockFace.NORTH), DoorForm.DOOR);
        PortalFrame destinationFrame = PortalFrame.fromNormalUp(Direction.W, Direction.U);
        UUID routeId = new UUID(0, 702);
        DoorProjectionProvider provider = new DoorProjectionProvider((requested, observerId) ->
            Optional.of(new DoorProjectionDestination(
                routeId, "minecraft:the_nether", new Vector(40.5D, 65.0D, 40.92D), destinationFrame)));

        assertTrue(provider.supports(adapter));
        ProjectionManager.RtpProjectionResult result = provider.touch(adapter, observer());

        assertTrue(result.projectionEnabled());
        assertFalse(result.rimEnabled());
        RtpProjectionView.ReadyData ready = result.view().readyFor(OBSERVER_ID).orElseThrow();
        assertEquals(routeId, ready.routeId());
        assertEquals("minecraft:the_nether", ready.target().worldKey());
        assertEquals(40.5D, ready.target().safeFeet().x(), 1.0E-9D);
        // forward points away from the frame normal, which is what PortalProjector reverses back.
        assertEquals(Direction.E.x(), ready.target().forward().x(), 1.0E-9D);
        assertEquals(Direction.U.y(), ready.target().up().y(), 1.0E-9D);
        assertEquals("minecraft:overworld", ready.sourceFrame().worldKey());
        assertEquals(2.0D, ready.sourceFrame().height(), 1.0E-9D);
    }

    @Test
    void theRouteRevisionOnlyMovesWhenTheDestinationDoes() {
        DoorProjectionAdapter adapter = adapter(new DoorwayPlane(10, 64, 10, BlockFace.NORTH), DoorForm.DOOR);
        AtomicReference<Vector> origin = new AtomicReference<>(new Vector(40.5D, 65.0D, 40.92D));
        DoorProjectionProvider provider = new DoorProjectionProvider((requested, observerId) ->
            Optional.of(new DoorProjectionDestination(
                new UUID(0, 703), "minecraft:overworld", origin.get(),
                PortalFrame.fromNormalUp(Direction.W, Direction.U))));

        long first = revision(provider, adapter);
        assertEquals(first, revision(provider, adapter));

        origin.set(new Vector(41.5D, 65.0D, 40.92D));
        assertNotEquals(first, revision(provider, adapter));
    }

    @Test
    void anUnresolvableDestinationSuppressesTheProjectionInsteadOfGuessing() {
        DoorProjectionAdapter adapter = adapter(new DoorwayPlane(10, 64, 10, BlockFace.NORTH), DoorForm.DOOR);
        DoorProjectionProvider provider = new DoorProjectionProvider((requested, observerId) -> Optional.empty());

        ProjectionManager.RtpProjectionResult result = provider.touch(adapter, observer());

        assertFalse(result.projectionEnabled());
        assertTrue(result.view().readyFor(OBSERVER_ID).isEmpty());
    }

    private static long revision(DoorProjectionProvider provider, DoorProjectionAdapter adapter) {
        return provider.touch(adapter, observer()).view().readyFor(OBSERVER_ID).orElseThrow().routeRevision();
    }

    private static DoorProjectionAdapter adapter(DoorwayPlane plane, DoorForm form) {
        PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(
            new DoorPosition(WORLD_ID, "minecraft:overworld", plane.blockX(), plane.blockY(), plane.blockZ()),
            DoorItemIdentity.publicDoor(new UUID(0, 704), form));
        RuntimeDoor door = new RuntimeDoor(endpoint);
        door.cycle().observe(true);
        return new DoorProjectionAdapter(door, plane, WORLD);
    }

    private static Player observer() {
        return (Player) Proxy.newProxyInstance(
            DoorProjectionProviderTest.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> OBSERVER_ID;
                default -> null;
            });
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(
            DoorProjectionProviderTest.class.getClassLoader(),
            new Class<?>[]{World.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "getUID" -> WORLD_ID;
                case "getKey" -> NamespacedKey.fromString("minecraft:overworld");
                case "getName" -> "world";
                case "equals" -> arguments[0] == instance;
                case "hashCode" -> 1;
                default -> null;
            });
    }
}
