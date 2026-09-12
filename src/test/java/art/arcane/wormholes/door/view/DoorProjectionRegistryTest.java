package art.arcane.wormholes.door.view;

import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.door.RuntimeDoor;
import art.arcane.wormholes.portal.ILocalPortal;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorProjectionRegistryTest {
    private static final UUID WORLD_ID = new UUID(0, 500);
    private static final World WORLD = world();

    @Test
    void installedAperturesAreHandedOutAndDropWhenTheDoorIsRemoved() {
        DoorProjectionRegistry registry = new DoorProjectionRegistry(8, 1, everyoneNearby());
        RuntimeDoor door = liveDoor(1);
        DoorProjectionAdapter adapter = registry.install(door, plane(1), WORLD);

        assertNotNull(adapter);
        assertSame(adapter, registry.adapter(adapter.getId()));
        assertEquals(1, registry.size());
        assertEquals(List.of(adapter), registry.advance(true));

        registry.remove(adapter.getId());
        assertEquals(0, registry.size());
        assertNull(registry.adapter(adapter.getId()));
        assertEquals(List.of(), registry.advance(true));
    }

    @Test
    void reinstallingTheSameDoorReAimsItsApertureInsteadOfLeakingOne() {
        DoorProjectionRegistry registry = new DoorProjectionRegistry(8, 1, everyoneNearby());
        RuntimeDoor door = liveDoor(2);
        DoorProjectionAdapter first = registry.install(door, plane(2), WORLD);
        DoorProjectionAdapter second = registry.install(door, new DoorwayPlane(2, 70, 9, BlockFace.SOUTH), WORLD);

        assertSame(first, second);
        assertEquals(1, registry.size());
        assertEquals(70 + 1.0D, second.getOrigin().getY(), 1.0E-9D);
    }

    @Test
    void theBudgetCapsHowManyAperturesProjectAndRotatesThroughTheRest() {
        DoorProjectionRegistry registry = new DoorProjectionRegistry(2, 1, everyoneNearby());
        List<UUID> installed = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            installed.add(registry.install(liveDoor(10 + index), plane(10 + index), WORLD).getId());
        }

        Set<UUID> seen = new HashSet<>();
        for (int pass = 0; pass < 5; pass++) {
            List<ILocalPortal> active = registry.advance(true);
            assertTrue(active.size() <= 2, "pass " + pass + " admitted " + active.size());
            for (ILocalPortal portal : active) {
                seen.add(portal.getId());
            }
        }

        assertEquals(new HashSet<>(installed), seen, "every aperture must get a turn");
    }

    @Test
    void nothingProjectsWhileTheGlobalFlagIsOffOrTheDoorOptedOut() {
        DoorProjectionRegistry registry = new DoorProjectionRegistry(8, 1, everyoneNearby());
        RuntimeDoor optedOut = liveDoor(20, DoorProjectionState.OFF);
        RuntimeDoor inherited = liveDoor(21, DoorProjectionState.INHERIT);
        registry.install(optedOut, plane(20), WORLD);
        DoorProjectionAdapter kept = registry.install(inherited, plane(21), WORLD);

        assertEquals(List.of(), registry.advance(false));

        DoorProjectionSource source = new DoorProjectionSource(() -> registry, () -> false);
        assertEquals(List.of(), source.activeProjectionPortals());

        DoorProjectionSource enabled = new DoorProjectionSource(() -> registry, () -> true);
        assertEquals(List.of(kept), new ArrayList<>(enabled.activeProjectionPortals()));
    }

    @Test
    void aClosedDoorIsRegisteredButNeverProjectsAndUnattendedDoorsAreSkipped() {
        DoorProjectionRegistry attended = new DoorProjectionRegistry(8, 1, everyoneNearby());
        RuntimeDoor shut = new RuntimeDoor(endpoint(30, DoorProjectionState.INHERIT));
        attended.install(shut, plane(30), WORLD);
        assertEquals(1, attended.size());
        assertEquals(List.of(), attended.advance(true), "a shut door has no live aperture");

        DoorProjectionRegistry empty = new DoorProjectionRegistry(8, 1, (worldId, x, y, z, r2) -> false);
        empty.install(liveDoor(31), plane(31), WORLD);
        assertEquals(List.of(), empty.advance(true), "nobody is near enough to project for");
    }

    @Test
    void theBackingPaneOnlyHidesForAnAdapterThatWouldProject() {
        DoorProjectionRegistry registry = new DoorProjectionRegistry(8, 1, everyoneNearby());
        DoorProjectionAdapter inherited = registry.install(liveDoor(40), plane(40), WORLD);
        DoorProjectionAdapter optedOut =
            registry.install(liveDoor(41, DoorProjectionState.OFF), plane(41), WORLD);

        assertTrue(registry.hidesBacking(inherited.getId(), true, true));
        assertEquals(false, registry.hidesBacking(inherited.getId(), false, true));
        assertEquals(false, registry.hidesBacking(inherited.getId(), true, false));
        assertEquals(false, registry.hidesBacking(optedOut.getId(), true, true));
        assertEquals(false, registry.hidesBacking(UUID.randomUUID(), true, true));
    }

    @Test
    void turningProjectionOffAndBackOnLeavesTheRegistryUsable() {
        DoorProjectionRegistry registry = new DoorProjectionRegistry(8, 1, everyoneNearby());
        DoorProjectionAdapter first = registry.install(liveDoor(60), plane(60), WORLD);

        registry.clear();

        assertTrue(first.isDestroyed());
        assertEquals(0, registry.size());
        DoorProjectionAdapter second = registry.install(liveDoor(61), plane(61), WORLD);
        assertNotNull(second, "a cleared registry still accepts apertures");
        assertEquals(List.of(second), registry.advance(true));
    }

    @Test
    void closingTheRegistryRetiresEveryAperture() {
        DoorProjectionRegistry registry = new DoorProjectionRegistry(8, 1, everyoneNearby());
        DoorProjectionAdapter adapter = registry.install(liveDoor(50), plane(50), WORLD);

        registry.close();

        assertTrue(adapter.isDestroyed());
        assertEquals(0, registry.size());
        assertEquals(List.of(), registry.advance(true));
        assertNull(registry.install(liveDoor(51), plane(51), WORLD));
    }

    private static DoorProjectionRegistry.Attendance everyoneNearby() {
        return (worldId, x, y, z, rangeSquared) -> true;
    }

    private static RuntimeDoor liveDoor(int seed) {
        return liveDoor(seed, DoorProjectionState.INHERIT);
    }

    private static RuntimeDoor liveDoor(int seed, DoorProjectionState projection) {
        RuntimeDoor door = new RuntimeDoor(endpoint(seed, projection));
        door.cycle().observe(true);
        return door;
    }

    private static PlacedDoorEndpoint endpoint(int seed, DoorProjectionState projection) {
        return new PlacedDoorEndpoint(
            new DoorPosition(WORLD_ID, "minecraft:overworld", seed, 64, seed),
            DoorItemIdentity.publicDoor(new UUID(0, 600 + seed)),
            art.arcane.wormholes.door.DoorOpenState.OPEN,
            projection);
    }

    private static DoorwayPlane plane(int seed) {
        return new DoorwayPlane(seed, 64, seed, BlockFace.NORTH);
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(
            DoorProjectionRegistryTest.class.getClassLoader(),
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
