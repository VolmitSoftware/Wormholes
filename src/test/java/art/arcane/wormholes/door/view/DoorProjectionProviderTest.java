package art.arcane.wormholes.door.view;

import art.arcane.wormholes.door.DoorHalf;


import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.door.RuntimeDoor;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.rtp.RtpProjectionView;
import art.arcane.optics.math.Face;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.Player;
import art.arcane.optics.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

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
        DoorwayPlane source = new DoorwayPlane(10, 64, 10, Face.N);
        DoorwayPlane mate = new DoorwayPlane(40, 64, 40, Face.E);

        Frame frame = DoorApertureFrames.destinationFrame(source, mate);

        assertEquals(Face.W, frame.getNormal());
        assertEquals(Face.U, frame.getUp());
    }

    @Test
    void aTrapdoorPairingIsStraightThroughAndKeepsTheMateFacing() {
        DoorwayPlane source = new DoorwayPlane(10, 64, 10, Face.N);
        DoorwayPlane trapdoorMate =
            DoorwayPlane.trapdoor(40, 64, 40, Face.S, DoorHalf.BOTTOM, DoorOpenState.OPEN);

        assertEquals(Face.D, DoorApertureFrames.destinationFrame(source, trapdoorMate).getNormal());
        assertEquals(Face.D, DoorApertureFrames.of(trapdoorMate).getNormal());

        DoorwayPlane trapdoorSource =
            DoorwayPlane.trapdoor(10, 64, 10, Face.N, DoorHalf.TOP, DoorOpenState.OPEN);
        DoorwayPlane hingedMate = new DoorwayPlane(40, 64, 40, Face.E);
        assertEquals(Face.E, DoorApertureFrames.destinationFrame(trapdoorSource, hingedMate).getNormal());
    }

    @Test
    void nativeObserversIgnoreProjectionSwitchesButStillRequireActiveDoorsAndResolvedRoutes() {
        for (DoorForm form : DoorForm.values()) {
            DoorwayPlane plane = form == DoorForm.DOOR ? new DoorwayPlane(10, 64, 10, Face.N)
                : DoorwayPlane.trapdoor(10, 64, 10, Face.N, DoorHalf.BOTTOM, DoorOpenState.OPEN);
            DoorProjectionAdapter inherited = adapter(plane, form);
            RuntimeDoor runtime = new RuntimeDoor(inherited.endpoint().withProjection(DoorProjectionState.OFF));
            runtime.cycle().observe(true);
            DoorProjectionAdapter disabled = new DoorProjectionAdapter(runtime, plane, WORLD);
            AtomicBoolean nativeView = new AtomicBoolean();
            AtomicBoolean globalEnabled = new AtomicBoolean();
            AtomicReference<DoorProjectionDestination> route = new AtomicReference<>(new DoorProjectionDestination(
                UUID.randomUUID(), "minecraft:overworld", new Vec3d(40.5, 65, 40.5), Frame.canonical(Face.S)));
            DoorProjectionProvider provider = new DoorProjectionProvider(new DoorProjectionProvider.Options(
                (source, observerId, bypass) -> Optional.ofNullable(route.get()), observer -> nativeView.get(), globalEnabled::get));

            assertFalse(provider.touch(inherited, observer()).projectionEnabled());
            assertFalse(provider.touch(disabled, observer()).projectionEnabled());
            nativeView.set(true);
            assertTrue(provider.touch(inherited, observer()).projectionEnabled());
            assertTrue(provider.touch(disabled, observer()).projectionEnabled());
            runtime.cycle().observe(false);
            assertFalse(provider.touch(disabled, observer()).projectionEnabled());
            runtime.cycle().observe(true);
            route.set(null);
            assertFalse(provider.touch(disabled, observer()).projectionEnabled());
            route.set(new DoorProjectionDestination(UUID.randomUUID(), "minecraft:overworld", new Vec3d(40.5, 65, 40.5),
                Frame.canonical(Face.S)));
            nativeView.set(false);
            globalEnabled.set(true);
            assertTrue(provider.touch(inherited, observer()).projectionEnabled());
            assertFalse(provider.touch(disabled, observer()).projectionEnabled());
        }
    }

    @Test
    void aResolvedDestinationBecomesAReadyTargetTheProjectorCanAimAt() {
        DoorProjectionAdapter adapter = adapter(new DoorwayPlane(10, 64, 10, Face.N), DoorForm.DOOR);
        Frame destinationFrame = Frame.fromNormalUp(Face.W, Face.U);
        UUID routeId = new UUID(0, 702);
        DoorProjectionProvider provider = provider((requested, observerId, bypass) ->
            Optional.of(new DoorProjectionDestination(
                routeId, "minecraft:the_nether", new Vec3d(40.5D, 65.0D, 40.92D), destinationFrame)));

        assertTrue(provider.supports(adapter));
        ProjectionManager.RtpProjectionResult result = provider.touch(adapter, observer());

        assertTrue(result.projectionEnabled());
        assertFalse(result.rimEnabled());
        RtpProjectionView.ReadyData ready = result.view().readyFor(OBSERVER_ID).orElseThrow();
        assertEquals(routeId, ready.routeId());
        assertEquals("minecraft:the_nether", ready.target().worldKey());
        assertEquals(40.5D, ready.target().safeFeet().x(), 1.0E-9D);
        // forward points away from the frame normal, which is what PortalProjector reverses back.
        assertEquals(Face.E.x(), ready.target().forward().x(), 1.0E-9D);
        assertEquals(Face.U.y(), ready.target().up().y(), 1.0E-9D);
        assertEquals("minecraft:overworld", ready.sourceFrame().worldKey());
        assertEquals(2.0D, ready.sourceFrame().height(), 1.0E-9D);
    }

    @Test
    void theRouteRevisionOnlyMovesWhenTheDestinationDoes() {
        DoorProjectionAdapter adapter = adapter(new DoorwayPlane(10, 64, 10, Face.N), DoorForm.DOOR);
        AtomicReference<Vec3d> origin = new AtomicReference<>(new Vec3d(40.5D, 65.0D, 40.92D));
        DoorProjectionProvider provider = provider((requested, observerId, bypass) ->
            Optional.of(new DoorProjectionDestination(
                new UUID(0, 703), "minecraft:overworld", origin.get(),
                Frame.fromNormalUp(Face.W, Face.U))));

        long first = revision(provider, adapter);
        assertEquals(first, revision(provider, adapter));

        origin.set(new Vec3d(41.5D, 65.0D, 40.92D));
        assertNotEquals(first, revision(provider, adapter));
    }

    @Test
    void anUnresolvableDestinationSuppressesTheProjectionInsteadOfGuessing() {
        DoorProjectionAdapter adapter = adapter(new DoorwayPlane(10, 64, 10, Face.N), DoorForm.DOOR);
        DoorProjectionProvider provider = provider((requested, observerId, bypass) -> Optional.empty());

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

    private static DoorProjectionProvider provider(DoorApertureDestinations destinations) {
        return new DoorProjectionProvider(new DoorProjectionProvider.Options(destinations, observer -> false, () -> true));
    }

    private static Player observer() {
        return (Player) Proxy.newProxyInstance(
            DoorProjectionProviderTest.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> OBSERVER_ID;
                case "hasPermission" -> false;
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
