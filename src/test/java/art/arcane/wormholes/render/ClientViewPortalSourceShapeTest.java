package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Cuboid;

final class ClientViewPortalSourceShapeTest {
    @Test
    void theEffectDescriptorCarriesTheShapeAndTheEffectiveMask() {
        World world = world();
        LocalPortal portal = portal(world);
        Location eye = new Location(world, 3.5D, 67.5D, -4.0D);
        ApertureDescriptor full = ClientViewPortalSource.effectGeometry(portal, eye);
        assertNotNull(full);
        assertEquals(ShapeDescriptor.FULL, full.shape());
        assertEquals(49, full.openCellCount());

        assertTrue(portal.setApertureShape(ShapeDescriptor.parse("circle")));
        ApertureDescriptor circle = ClientViewPortalSource.effectGeometry(portal, eye);
        assertNotNull(circle);
        assertEquals(ShapeDescriptor.parse("circle"), circle.shape());
        assertEquals(portal.getStructure().getBlockPositions().size(), circle.openCellCount());
        assertEquals(7, circle.apertureWidth());
        assertEquals(7, circle.apertureHeight());
    }

    @Test
    void theEffectRevisionChangesWhenOnlyTheShapeChanges() {
        World world = world();
        LocalPortal portal = portal(world);
        Location eye = new Location(world, 3.5D, 67.5D, -4.0D);
        assertTrue(portal.setApertureShape(ShapeDescriptor.parse("circle")));
        long circle = ClientViewPortalSource.effectGeometryRevision(portal, eye);
        ApertureDescriptor before = ClientViewPortalSource.effectGeometry(portal, eye);
        assertTrue(portal.setApertureShape(ShapeDescriptor.parse("circle@rotate(45)")));
        ApertureDescriptor after = ClientViewPortalSource.effectGeometry(portal, eye);
        assertNotNull(before);
        assertNotNull(after);
        assertEquals(before.openCellCount(), after.openCellCount());
        assertArrayEquals(before.apertureMask(), after.apertureMask());
        assertNotEquals(circle, ClientViewPortalSource.effectGeometryRevision(portal, eye));
    }

    private static LocalPortal portal(World world) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        structure.setArea(new Cuboid(new Location(world, 0.0D, 64.0D, 0.0D), new Location(world, 6.0D, 70.0D, 0.0D)));
        LocalPortal portal = new LocalPortal(UUID.randomUUID(), PortalType.WORMHOLE, structure);
        portal.setAmbientAttended(false);
        return portal;
    }

    private static World world() {
        UUID id = UUID.randomUUID();
        NamespacedKey key = NamespacedKey.minecraft("shape-source");
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class}, (Object proxy, Method method, Object[] arguments) -> switch(method.getName()) {
            case "getUID" -> id;
            case "getKey" -> key;
            case "getName" -> "shape-source";
            case "getMinHeight" -> Integer.valueOf(-64);
            case "getMaxHeight" -> Integer.valueOf(320);
            case "getEnvironment" -> World.Environment.NORMAL;
            case "equals" -> Boolean.valueOf(proxy == arguments[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> "ShapeSourceWorld";
            default -> null;
        });
    }
}
