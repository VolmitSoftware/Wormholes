package art.arcane.wormholes.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Cuboid;

public final class LocalPairSettingsSyncTest {
    static {
        installBukkitStub();
    }

    private static void installBukkitStub() {
        try {
            java.lang.reflect.Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            if (serverField.get(null) != null) {
                return;
            }
            Object blockData = java.lang.reflect.Proxy.newProxyInstance(
                LocalPairSettingsSyncTest.class.getClassLoader(),
                new Class<?>[] { org.bukkit.block.data.BlockData.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMaterial" -> org.bukkit.Material.AIR;
                    case "getAsString" -> "minecraft:air";
                    case "clone" -> proxy;
                    case "hashCode" -> Integer.valueOf(0);
                    case "equals" -> Boolean.valueOf(proxy == (args == null ? null : args[0]));
                    case "toString" -> "minecraft:air";
                    default -> defaultValueFor(method.getReturnType());
                });
            Object server = java.lang.reflect.Proxy.newProxyInstance(
                LocalPairSettingsSyncTest.class.getClassLoader(),
                new Class<?>[] { org.bukkit.Server.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "createBlockData" -> blockData;
                    case "getLogger" -> java.util.logging.Logger.getLogger("LocalPairSettingsSyncTest");
                    case "hashCode" -> Integer.valueOf(0);
                    case "equals" -> Boolean.valueOf(proxy == (args == null ? null : args[0]));
                    case "toString" -> "stub";
                    default -> defaultValueFor(method.getReturnType());
                });
            serverField.set(null, server);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static Object defaultValueFor(Class<?> type) {
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
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        return null;
    }

    private static LocalPortal localPortal(int zOffset) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("worldKey", "minecraft:overworld");
        values.put("x1", Integer.valueOf(0));
        values.put("y1", Integer.valueOf(64));
        values.put("z1", Integer.valueOf(zOffset));
        values.put("x2", Integer.valueOf(0));
        values.put("y2", Integer.valueOf(66));
        values.put("z2", Integer.valueOf(zOffset + 2));
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(values));
        return new LocalPortal(UUID.randomUUID(), PortalType.GATEWAY, structure);
    }

    private static void withService(List<ILocalPortal> portals, Runnable body) {
        PortalSyncService previous = Wormholes.portalSyncService;
        try {
            Wormholes.portalSyncService = new PortalSyncService(null, () -> portals, Runnable::run);
            body.run();
        } finally {
            Wormholes.portalSyncService = previous;
        }
    }

    @Test
    public void aLinkedSameServerPairConvergesOnTheFiveDivergingSettings() {
        LocalPortal source = localPortal(0);
        LocalPortal counterpart = localPortal(64);
        source.setSettingsSyncEnabled(true);
        counterpart.setSettingsSyncEnabled(true);

        withService(List.of(source, counterpart), () -> {
            source.setDimensionalCounterpartId(counterpart.getId());

            source.setBlackoutBackground(true);
            source.setNetworkViewDepth(96);
            source.setNetworkViewHeartbeatTicks(20);
            source.setNetworkViewEntityIntervalTicks(2);
            source.setNetworkViewUnsubscribeGraceSeconds(45);

            assertTrue(counterpart.isBlackoutBackground(), "blackoutBackground must converge");
            assertEquals(96, counterpart.getNetworkViewDepth(), "networkViewDepth must converge");
            assertEquals(20, counterpart.getNetworkViewHeartbeatTicks(), "heartbeat must converge");
            assertEquals(2, counterpart.getNetworkViewEntityIntervalTicks(), "entity interval must converge");
            assertEquals(45, counterpart.getNetworkViewUnsubscribeGraceSeconds(), "grace must converge");
        });
    }

    @Test
    public void clearingASettingPropagatesJustLikeSettingOne() {
        LocalPortal source = localPortal(0);
        LocalPortal counterpart = localPortal(64);
        source.setSettingsSyncEnabled(true);
        counterpart.setSettingsSyncEnabled(true);

        withService(List.of(source, counterpart), () -> {
            source.setDimensionalCounterpartId(counterpart.getId());
            source.setSurfaceSkin("minecraft:concrete");
            assertEquals("minecraft:concrete", counterpart.getSurfaceSkin());

            source.setSurfaceSkin("");
            assertEquals("", counterpart.getSurfaceSkin(), "clearing a skin must propagate, not just setting one");
        });
    }

    @Test
    public void linkingTwoAlreadyDivergentPortalsConvergesThemImmediately() {
        LocalPortal source = localPortal(0);
        LocalPortal counterpart = localPortal(64);
        source.setSettingsSyncEnabled(true);
        counterpart.setSettingsSyncEnabled(true);

        withService(List.of(source, counterpart), () -> {
            source.setNetworkViewDepth(96);
            counterpart.setNetworkViewDepth(32);
            assertNotEquals(source.getNetworkViewDepth(), counterpart.getNetworkViewDepth());

            source.setDimensionalCounterpartId(counterpart.getId());

            assertEquals(96, counterpart.getNetworkViewDepth(), "linking must converge an already divergent pair");
        });
    }

    @Test
    public void aCounterpartWithSyncDisabledIsNeverOverwritten() {
        LocalPortal source = localPortal(0);
        LocalPortal counterpart = localPortal(64);
        source.setSettingsSyncEnabled(true);
        counterpart.setSettingsSyncEnabled(false);
        counterpart.setNetworkViewDepth(32);

        withService(List.of(source, counterpart), () -> {
            source.setDimensionalCounterpartId(counterpart.getId());
            source.setNetworkViewDepth(96);

            assertEquals(32, counterpart.getNetworkViewDepth(), "a portal with sync off must not be overwritten");
        });
    }

    @Test
    public void traversalDirectionAndTheSyncFlagItselfAreNotPropagated() {
        LocalPortal source = localPortal(0);
        LocalPortal counterpart = localPortal(64);
        source.setSettingsSyncEnabled(true);
        counterpart.setSettingsSyncEnabled(true);
        counterpart.setOutgoingTraversalsEnabled(false);
        counterpart.setIncomingTraversalsEnabled(true);

        withService(List.of(source, counterpart), () -> {
            source.setDimensionalCounterpartId(counterpart.getId());
            source.setOutgoingTraversalsEnabled(true);
            source.setIncomingTraversalsEnabled(false);

            assertFalse(counterpart.isOutgoingTraversalsEnabled(), "a one-way link must keep its own direction");
            assertTrue(counterpart.isIncomingTraversalsEnabled(), "a one-way link must keep its own direction");
            assertTrue(counterpart.isSettingsSyncEnabled(), "the sync flag itself must not be pushed");
        });
    }

    @Test
    public void anRtpCounterpartIsSkipped() {
        LocalPortal source = localPortal(0);
        LocalPortal counterpart = localPortal(64);
        source.setSettingsSyncEnabled(true);
        counterpart.setSettingsSyncEnabled(true);

        withService(List.of(source, counterpart), () -> {
            source.setDimensionalCounterpartId(counterpart.getId());
            counterpart.setType(PortalType.RTP);
            counterpart.setNetworkViewDepth(32);

            source.setNetworkViewDepth(96);

            assertEquals(32, counterpart.getNetworkViewDepth(), "an RTP portal must not be treated as a sync counterpart");
        });
    }

    @Test
    public void applyingToACounterpartDoesNotPingPongBack() {
        LocalPortal source = localPortal(0);
        LocalPortal counterpart = localPortal(64);
        source.setSettingsSyncEnabled(true);
        counterpart.setSettingsSyncEnabled(true);

        withService(List.of(source, counterpart), () -> {
            source.setDimensionalCounterpartId(counterpart.getId());
            counterpart.setDimensionalCounterpartId(source.getId());

            source.setNetworkViewDepth(96);

            assertEquals(96, counterpart.getNetworkViewDepth());
            assertEquals(96, source.getNetworkViewDepth(), "the source must not be rewritten by its own fan-out");
            assertFalse(PortalSyncService.isApplyingRemote(), "the re-entry guard must be restored after the fan-out");
        });
    }
}
