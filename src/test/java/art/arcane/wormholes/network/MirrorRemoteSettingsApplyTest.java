package art.arcane.wormholes.network;

import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.util.Cuboid;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorRemoteSettingsApplyTest {
    @Test
    void remoteMirrorEnableNeverDiscardsALocalPortalsUniversalTunnel() {
        LocalPortal portal = portal(world("overworld"), PortalType.GATEWAY);
        UUID remoteId = UUID.randomUUID();
        assertTrue(portal.linkRemote("beta", remoteId));

        PortalSyncService.applyToLocal(portal, mirrorEnablePayload());

        assertNotNull(portal.getTunnel(), "remote settings update deleted the local operator's cross-server link");
        UniversalTunnel universal = assertInstanceOf(UniversalTunnel.class, portal.getTunnel());
        assertEquals("beta", universal.getServerName());
        assertEquals(remoteId, universal.getDestinationPortalId());
        assertFalse(portal.isMirrorMode());
    }

    @Test
    void remoteMirrorEnableNeverDiscardsALinkedLocalCounterpartsTunnel() {
        World world = world("overworld");
        LocalPortal portal = portal(world, PortalType.PORTAL);
        LocalPortal destination = portal(world, PortalType.PORTAL);
        assertTrue(portal.setDestination(destination));

        PortalSyncService.applyToLocal(portal, mirrorEnablePayload());

        assertNotNull(portal.getTunnel(), "linked-local settings sync deleted the counterpart's link");
        assertEquals(destination.getId(), portal.getTunnel().getDestinationId());
        assertFalse(portal.isMirrorMode());
    }

    @Test
    void remoteMirrorEnableNeverLeavesAMirrorStillCarryingATunnel() {
        LocalPortal portal = portal(world("overworld"), PortalType.GATEWAY);
        assertTrue(portal.linkRemote("beta", UUID.randomUUID()));

        PortalSyncService.applyToLocal(portal, mirrorEnablePayload());

        assertFalse(portal.isMirrorMode() && portal.getTunnel() != null,
            "mirror plus tunnel is the state the load-time normalizer destroys on the next restart");
    }

    @Test
    void remoteMirrorEnableStillAppliesToAnUnlinkedPortal() {
        LocalPortal portal = portal(world("overworld"), PortalType.GATEWAY);
        assertNull(portal.getTunnel());

        PortalSyncService.applyToLocal(portal, mirrorEnablePayload());

        assertTrue(portal.isMirrorMode());
        assertNull(portal.getTunnel());
    }

    @Test
    void remoteMirrorDisableStillApplies() {
        LocalPortal portal = portal(world("overworld"), PortalType.GATEWAY);
        portal.setMirrorMode(true);
        assertTrue(portal.isMirrorMode());

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(PortalSyncService.KEY_PROJECTION_MODE, ProjectionMode.ON.name());
        settings.put(PortalSyncService.KEY_PROJECTION_ENABLED, "true");
        settings.put(PortalSyncService.KEY_MIRROR_MODE, "false");
        PortalSyncService.applyToLocal(portal, settings);

        assertFalse(portal.isMirrorMode());
    }

    @Test
    void anOperatorMirrorToggleStillShedsTheLink() {
        LocalPortal portal = portal(world("overworld"), PortalType.GATEWAY);
        assertTrue(portal.linkRemote("beta", UUID.randomUUID()));

        portal.setMirrorMode(true);

        assertTrue(portal.isMirrorMode());
        assertNull(portal.getTunnel());
    }

    private static Map<String, String> mirrorEnablePayload() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(PortalSyncService.KEY_PROJECTION_MODE, "MIRROR");
        settings.put(PortalSyncService.KEY_PROJECTION_ENABLED, "true");
        settings.put(PortalSyncService.KEY_MIRROR_MODE, "true");
        return settings;
    }

    private static LocalPortal portal(World world, PortalType type) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        structure.setArea(new Cuboid(new Location(world, 0.0D, 64.0D, 0.0D), new Location(world, 0.0D, 66.0D, 2.0D)));
        LocalPortal portal = new LocalPortal(UUID.randomUUID(), type, structure);
        portal.setAmbientAttended(false);
        return portal;
    }

    private static World world(String name) {
        UUID worldId = UUID.nameUUIDFromBytes(("mirror-remote-settings-world-" + name).getBytes(StandardCharsets.UTF_8));
        NamespacedKey key = new NamespacedKey("minecraft", name);
        InvocationHandler handler = (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
            case "getUID" -> worldId;
            case "getKey" -> key;
            case "getName" -> name;
            case "getMinHeight" -> Integer.valueOf(-64);
            case "getMaxHeight" -> Integer.valueOf(320);
            case "getSeaLevel" -> Integer.valueOf(63);
            case "getEnvironment" -> World.Environment.NORMAL;
            case "equals" -> Boolean.valueOf(proxy == arguments[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> "MirrorRemoteSettingsApplyTestWorld[" + name + "]";
            default -> defaultValue(method.getReturnType());
        };
        return (World) Proxy.newProxyInstance(MirrorRemoteSettingsApplyTest.class.getClassLoader(),
            new Class<?>[] {World.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == double.class) {
            return Double.valueOf(0.0D);
        }
        if (type == float.class) {
            return Float.valueOf(0.0F);
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        return null;
    }
}
