package art.arcane.wormholes.clientgametest;

import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.client.ClientMeshSections;
import art.arcane.wormholes.modded.client.ClientMeshEntities;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientEntityIds;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.portal.PortalType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class ClientViewMirrorClientGameTest implements FabricClientGameTest {
    private static final int NEGOTIATION_TIMEOUT_TICKS = 400;
    private static final int STREAM_TIMEOUT_TICKS = 600;
    private static final int FOLLOW_TICKS = 20;
    private static final int FOLLOW_SWING_TICKS = 5;
    private static final double POSITION_EPSILON = 1.0E-6D;
    private static final double BOX_EPSILON = 1.0E-4D;
    private static final BlockPos MIRROR_MIN = new BlockPos(40, 70, 20);
    private static final BlockPos GOLD_MARKER = new BlockPos(41, 71, 23);
    private static final BlockPos CHILD_MIN = new BlockPos(40, 70, 24);
    private static final BlockPos CHILD_DESTINATION_MIN = new BlockPos(40, 70, 300);
    private static final BlockPos CEILING_MIN = new BlockPos(80, 75, 20);
    private static final int CEILING_ROOM_HEIGHT = 5;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enable();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            runScenario(context, singleplayer.getConnection(), singleplayer.getServer(), "singleplayer");
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer(ClientViewTestConfig.serverProperties());
             TestDedicatedServerConnection connection = server.connect()) {
            runScenario(context, connection, server, "dedicated");
        }
    }

    private void runScenario(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, String label) {
        connection.waitForChunksDownload();
        context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), NEGOTIATION_TIMEOUT_TICKS);
        boolean mirrorCaps = context.computeOnClient(client -> WormholesClient.instance().session().has(ViewStreamCapability.CLIENT_MIRROR)
            && WormholesClient.instance().session().has(ViewStreamCapability.CLIENT_RECURSION));
        assertTrue(mirrorCaps, "the session did not negotiate CLIENT_MIRROR and CLIENT_RECURSION");
        ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
        UUID mirrorId = server.computeOnServer(minecraftServer -> buildMirror(minecraftServer, player));
        teleport(server, player, MIRROR_MIN.getX() + 1.5D, MIRROR_MIN.getY(), MIRROR_MIN.getZ() + 8.5D, 180.0F, 0.0F);
        connection.waitForChunksRender();
        context.waitFor(client -> NativeClientViewAssertions.ready(mirrorKey()), STREAM_TIMEOUT_TICKS);
        assertMirrorWithoutPlate(context);
        assertMirrorParity(context);
        context.takeScreenshot("clientview-mirror-" + label);
        assertSelfReflectionFollows(context);
        UUID[] child = server.computeOnServer(minecraftServer -> buildChild(minecraftServer, player));
        assertNestedContent(context, connection);
        context.takeScreenshot("clientview-mirror-nested-" + label);
        server.runOnServer(minecraftServer -> {
            WormholesModRuntime runtime = runtime();
            runtime.portals().remove(player, child[0]);
            runtime.portals().remove(player, child[1]);
            runtime.portals().remove(player, mirrorId);
        });
        context.waitFor(client -> WormholesClient.instance().reflections().size() == 0 && mirrorKey() == 0, STREAM_TIMEOUT_TICKS);
        int reflections = context.computeOnClient(client -> WormholesClient.instance().reflections().size());
        assertTrue(reflections == 0, "the self reflection outlived its mirror");
        runCeilingScenario(context, connection, server, player, label);
    }

    private void runCeilingScenario(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, ServerPlayer player,
                                    String label) {
        UUID ceilingId = server.computeOnServer(minecraftServer -> buildCeilingMirror(minecraftServer, player));
        teleport(server, player, CEILING_MIN.getX() + 1.5D, CEILING_MIN.getY() - CEILING_ROOM_HEIGHT, CEILING_MIN.getZ() + 1.5D, 180.0F, -90.0F);
        connection.waitForChunksRender();
        context.waitFor(client -> client.player.onGround() && mirrorKey() != 0 && WormholesClient.instance().reflections().entity(mirrorKey()) != null,
            STREAM_TIMEOUT_TICKS);
        String standing = context.computeOnClient(client -> Math.abs(client.player.getY() - (CEILING_MIN.getY() - CEILING_ROOM_HEIGHT)) < POSITION_EPSILON
            ? null : "the player stands at " + client.player.position() + " instead of the ceiling room floor");
        assertTrue(standing == null, standing);
        String failure = context.computeOnClient(ClientViewMirrorClientGameTest::reflectionMismatch);
        assertTrue(failure == null, "ceiling mirror: " + failure);
        boolean flipped = context.computeOnClient(client -> {
            Entity entity = WormholesClient.instance().reflections().entity(mirrorKey());
            return WormholesClient.instance().session().environment(mirrorKey()).transform().yAxis() == Face.D
                && WormholesClient.instance().reflections().meshEntity(entity.getId());
        });
        assertTrue(flipped, "the ceiling reflection is not drawn upside down");
        connection.waitForChunksRender();
        context.takeScreenshot("clientview-mirror-ceiling-" + label);
        server.runOnServer(minecraftServer -> runtime().portals().remove(player, ceilingId));
        context.waitFor(client -> WormholesClient.instance().reflections().size() == 0 && mirrorKey() == 0, STREAM_TIMEOUT_TICKS);
        int reflections = context.computeOnClient(client -> WormholesClient.instance().reflections().size());
        assertTrue(reflections == 0, "the ceiling reflection outlived its mirror");
    }

    private static void assertMirrorWithoutPlate(ClientGameTestContext context) {
        String failure = context.computeOnClient(client -> {
            WormholesClient wormholes = WormholesClient.instance();
            int key = mirrorKey();
            ClientPortal portal = wormholes.session().portal(key);
            if (portal == null || portal.plate() != null) {
                return "the mirror portal holds a streamed plate";
            }
            if (wormholes.session().plates().plate(key) != null) {
                return "the plate store holds bytes for the mirror";
            }
            return null;
        });
        assertTrue(failure == null, failure);
    }

    private static void assertMirrorParity(ClientGameTestContext context) {
        context.waitFor(client -> NativeClientViewAssertions.state(mirrorKey(), GOLD_MARKER) == Blocks.GOLD_BLOCK.defaultBlockState(), STREAM_TIMEOUT_TICKS);
        boolean reflected = context.computeOnClient(client -> {
            NativeClientViewAssertions.assertIsolated();
            return WormholesClient.instance().session().environment(mirrorKey()).transform().reflected()
                && client.level.getBlockState(GOLD_MARKER).is(Blocks.GOLD_BLOCK);
        });
        assertTrue(reflected, "native mirror lost its reflection transform or changed the physical gold marker");
    }

    private static void assertSelfReflectionFollows(ClientGameTestContext context) {
        for (int step = 0; step < FOLLOW_TICKS; step++) {
            boolean left = step % (FOLLOW_SWING_TICKS * 2) < FOLLOW_SWING_TICKS;
            context.getInput().holdKeyFor(options -> left ? options.keyLeft : options.keyRight, 1);
            String failure = context.computeOnClient(ClientViewMirrorClientGameTest::reflectionMismatch);
            assertTrue(failure == null, "step " + step + ": " + failure);
        }
    }

    private static String reflectionMismatch(Minecraft client) {
        WormholesClient wormholes = WormholesClient.instance();
        int key = mirrorKey();
        Entity entity = wormholes.reflections().entity(key);
        if (!(entity instanceof Mannequin)) {
            return "no self reflection for mirror " + key;
        }
        if (!ClientEntityIds.isReflection(entity.getId())) {
            return "the reflection uses entity id " + entity.getId();
        }
        if (!wormholes.reflections().meshEntity(entity.getId()) || !ClientMeshEntities.hiddenFromWorld(entity)) {
            return "reflection is not isolated to its native scene";
        }
        if (entity.position().distanceTo(client.player.position()) > POSITION_EPSILON
            || Math.abs(entity.getBbHeight() - client.player.getBbHeight()) > BOX_EPSILON) {
            return "native reflection pose differs from the physical player";
        }
        if (Math.abs(entity.xOld - client.player.xOld) > POSITION_EPSILON
            || Math.abs(entity.yOld - client.player.yOld) > POSITION_EPSILON
            || Math.abs(entity.zOld - client.player.zOld) > POSITION_EPSILON) {
            return "native reflection interpolation differs from the physical player";
        }
        return null;
    }

    private static void assertNestedContent(ClientGameTestContext context, TestServerConnection connection) {
        context.waitFor(client -> {
            for (ClientPortal portal : WormholesClient.instance().session().portals().values()) {
                if (!portal.nested() || portal.geometry().parentPortalKey() != mirrorKey()) {
                    continue;
                }
                ClientMeshSections.View view = WormholesClient.instance().session().meshes().view(portal.portalKey());
                if (view == null) {
                    continue;
                }
                for (long key : view.sectionKeys()) {
                    ClientMeshSections.Section section = view.section(key);
                    for (int cell = 0; cell < 4096; cell++) {
                        if (section.state(cell).is(Blocks.GOLD_BLOCK)) {
                            NativeClientViewAssertions.assertIsolated();
                            return true;
                        }
                    }
                }
            }
            return false;
        }, STREAM_TIMEOUT_TICKS);
    }

    private static int mirrorKey() {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            for (ClientPortal portal : client.session().portals().values()) {
                if (portal.geometry().mirror() && !portal.nested()) {
                    return portal.portalKey();
                }
            }
        }
        return 0;
    }

    private static UUID buildMirror(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime();
        ServerLevel level = server.overworld();
        fill(level, MIRROR_MIN.offset(-6, -1, -8), 15, 10, 8, Blocks.STONE.defaultBlockState());
        fill(level, MIRROR_MIN.offset(-6, 0, 1), 15, 8, 10, Blocks.AIR.defaultBlockState());
        fill(level, MIRROR_MIN.offset(-6, -1, 1), 15, 1, 10, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(GOLD_MARKER, Blocks.GOLD_BLOCK.defaultBlockState());
        MinecraftPortal mirror = runtime.portals().create(actor.getUUID(), level, cells(MIRROR_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        mirror.setMirrorMode(true);
        runtime.portals().save(mirror);
        return mirror.getId();
    }

    private static UUID buildCeilingMirror(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime();
        ServerLevel level = server.overworld();
        fill(level, CEILING_MIN.offset(-6, -CEILING_ROOM_HEIGHT - 1, -6), 15, 1, 15, Blocks.STONE.defaultBlockState());
        fill(level, CEILING_MIN.offset(-6, -CEILING_ROOM_HEIGHT, -6), 15, CEILING_ROOM_HEIGHT + 1, 15, Blocks.AIR.defaultBlockState());
        fill(level, CEILING_MIN.offset(-6, 1, -6), 15, 10, 15, Blocks.STONE.defaultBlockState());
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                cells.add(CEILING_MIN.offset(x, 0, z));
            }
        }
        MinecraftPortal mirror = runtime.portals().create(actor.getUUID(), level, cells, PortalType.PORTAL, new Vec3(0, 1, 0));
        mirror.setMirrorMode(true);
        runtime.portals().save(mirror);
        return mirror.getId();
    }

    private static UUID[] buildChild(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime();
        ServerLevel level = server.overworld();
        fill(level, CHILD_DESTINATION_MIN.offset(-6, -2, -12), 15, 10, 12, Blocks.GOLD_BLOCK.defaultBlockState());
        fill(level, CHILD_DESTINATION_MIN.offset(-6, -2, 1), 15, 10, 12, Blocks.GOLD_BLOCK.defaultBlockState());
        MinecraftPortal child = runtime.portals().create(actor.getUUID(), level, cells(CHILD_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal destination = runtime.portals().create(actor.getUUID(), level, cells(CHILD_DESTINATION_MIN), PortalType.PORTAL,
            new Vec3(0, 0, -1));
        assertTrue(runtime.portals().link(actor, child.getId(), destination.getId()), "child link rejected");
        return new UUID[] {child.getId(), destination.getId()};
    }

    private static void fill(ServerLevel level, BlockPos min, int sizeX, int sizeY, int sizeZ, BlockState state) {
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    level.setBlockAndUpdate(min.offset(x, y, z), state);
                }
            }
        }
    }

    private static void teleport(TestServerContext server, ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
        server.runOnServer(minecraftServer -> player.teleportTo(player.level(), x, y, z, Set.of(), yaw, pitch, false));
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
