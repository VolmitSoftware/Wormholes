package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientProjectedEntities;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class ClientViewSceneClientGameTest implements FabricClientGameTest {
    private static final int NEGOTIATION_TIMEOUT_TICKS = 400;
    private static final int STREAM_TIMEOUT_TICKS = 600;
    private static final int LIGHT_POLL_TICKS = 10;
    private static final BlockPos SOURCE_MIN = new BlockPos(0, 70, 20);
    private static final BlockPos DESTINATION_MIN = new BlockPos(0, 70, 220);
    private static final BlockPos RTP_MIN = new BlockPos(12, 70, 20);
    private static final BlockPos STAND = new BlockPos(1, 70, 216);
    private static final BlockPos GLOWSTONE = new BlockPos(0, 72, 214);
    private static final BlockPos LIT_CELL = new BlockPos(1, 71, 215);
    private static final int OFFSET_Z = SOURCE_MIN.getZ() - DESTINATION_MIN.getZ();
    private static final double POSITION_TOLERANCE = 0.1D;

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
        connection.waitForChunksRender();
        context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), NEGOTIATION_TIMEOUT_TICKS);
        ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
        Scene scene = server.computeOnServer(minecraftServer -> build(minecraftServer, player));
        server.runOnServer(minecraftServer -> player.teleportTo(player.level(), SOURCE_MIN.getX() + 1.5D, SOURCE_MIN.getY(),
            SOURCE_MIN.getZ() + 6.5D, Set.of(), 180.0F, 0.0F, false));
        connection.waitForChunksRender();
        context.waitFor(client -> spawnedEntities() > 0, STREAM_TIMEOUT_TICKS);
        assertProjectedStand(context);
        assertDestinationLight(context, server, connection);
        context.takeScreenshot("clientview-scene-" + label);
        context.waitFor(client -> rimEmitters() >= 8, STREAM_TIMEOUT_TICKS);
        long fired = context.computeOnClient(client -> WormholesClient.instance().tickState().fx().fired());
        context.waitTicks(20);
        long firedLater = context.computeOnClient(client -> WormholesClient.instance().tickState().fx().fired());
        assertTrue(firedLater > fired, "rim dust emitters did not fire locally");
        server.runOnServer(minecraftServer -> {
            WormholesModRuntime runtime = runtime();
            runtime.portals().remove(player, scene.source());
            runtime.portals().remove(player, scene.destination());
            runtime.portals().remove(player, scene.rtp());
        });
        context.waitFor(client -> trackedEntities() == 0, STREAM_TIMEOUT_TICKS);
        int leftovers = context.computeOnClient(client -> {
            int count = 0;
            for (Entity entity : client.level.entitiesForRendering()) {
                if (entity.getId() < 0 && entity instanceof ArmorStand) {
                    count++;
                }
            }
            return count;
        });
        assertTrue(leftovers == 0, leftovers + " projected armour stands survived the portal drop");
    }

    private static void assertProjectedStand(ClientGameTestContext context) {
        List<String> failures = context.computeOnClient(client -> {
            List<String> problems = new ArrayList<>();
            ArmorStand found = null;
            for (Entity entity : client.level.entitiesForRendering()) {
                if (entity.getId() < 0 && entity instanceof ArmorStand stand) {
                    found = stand;
                }
            }
            if (found == null) {
                problems.add("no projected armour stand in the client level");
                return problems;
            }
            if (!WormholesClient.instance().tickState().entities().meshEntity(found.getId())
                || !ClientMeshEntities.hiddenFromWorld(found)) {
                problems.add("projected stand is not isolated to its native mesh scene");
            }
            Vec3 expected = new Vec3(STAND.getX() + 0.5D, STAND.getY(), STAND.getZ() + 0.5D);
            if (found.position().distanceTo(expected) > POSITION_TOLERANCE) {
                problems.add("projected stand at " + found.position() + " instead of " + expected);
            }
            return problems;
        });
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }

    private static void assertDestinationLight(ClientGameTestContext context, TestServerContext server, TestServerConnection connection) {
        int destination = server.computeOnServer(minecraftServer -> connection.getServerLevel().getBrightness(LightLayer.BLOCK, LIT_CELL));
        assertTrue(destination > 0, "the destination glowstone produced no light");
        context.waitFor(client -> NativeClientViewAssertions.light(NativeClientViewAssertions.portalKey(SOURCE_MIN), LIT_CELL, false) == destination,
            STREAM_TIMEOUT_TICKS);
        context.computeOnClient(client -> { NativeClientViewAssertions.assertIsolated(); return true; });
    }

    private static Scene build(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime();
        ServerLevel level = server.overworld();
        fill(level, SOURCE_MIN.offset(-2, -1, 1), 6, 1, 8, Blocks.STONE.defaultBlockState());
        fill(level, DESTINATION_MIN.offset(-6, -2, -10), 13, 8, 10, Blocks.STONE.defaultBlockState());
        fill(level, new BlockPos(0, 70, 213), 3, 3, 7, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(GLOWSTONE, Blocks.GLOWSTONE.defaultBlockState());
        ArmorStand stand = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        assertTrue(stand != null, "armour stand could not be created");
        stand.snapTo(STAND.getX() + 0.5D, STAND.getY(), STAND.getZ() + 0.5D, 0.0F, 0.0F);
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        MinecraftPortal source = runtime.portals().create(actor.getUUID(), level, cells(SOURCE_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal destination = runtime.portals().create(actor.getUUID(), level, cells(DESTINATION_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        assertTrue(runtime.portals().link(actor, source.getId(), destination.getId()), "portal link rejected");
        MinecraftPortal rtp = runtime.portals().create(actor.getUUID(), level, cells(RTP_MIN), PortalType.RTP, new Vec3(0, 0, -1));
        RtpSettings settings = runtime.rtp().settings(rtp).toBuilder().radii(12, 24).rotationMode(RtpRotationMode.STATIC).soundEnabled(false).build();
        rtp.setRtpSettings(settings);
        runtime.portals().save(rtp);
        return new Scene(source.getId(), destination.getId(), rtp.getId());
    }

    private static int spawnedEntities() {
        WormholesClient wormholes = WormholesClient.instance();
        ClientProjectedEntities entities = wormholes == null ? null : wormholes.tickState().entities();
        return entities == null ? 0 : entities.spawned();
    }

    private static int trackedEntities() {
        WormholesClient wormholes = WormholesClient.instance();
        ClientProjectedEntities entities = wormholes == null ? null : wormholes.tickState().entities();
        return entities == null ? 0 : entities.tracked();
    }

    private static int rimEmitters() {
        WormholesClient wormholes = WormholesClient.instance();
        return wormholes == null || wormholes.tickState().fx() == null ? 0 : wormholes.tickState().fx().emitters();
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

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Scene(UUID source, UUID destination, UUID rtp) {
    }
}
