package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.fabric.WormholesFabric;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientPlate;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientProjectedEntities;
import art.arcane.wormholes.modded.client.ClientViewTick;
import art.arcane.wormholes.modded.client.ProjectionOverlay;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
        try (TestDedicatedServerContext server = context.worldBuilder().createServer();
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
            Vec3 expected = new Vec3(STAND.getX() + 0.5D, STAND.getY(), STAND.getZ() + 0.5D + OFFSET_Z);
            if (found.position().distanceTo(expected) > POSITION_TOLERANCE) {
                problems.add("projected stand at " + found.position() + " instead of " + expected);
            }
            return problems;
        });
        assertTrue(failures.isEmpty(), String.join("; ", failures));
    }

    private static void assertDestinationLight(ClientGameTestContext context, TestServerContext server, TestServerConnection connection) {
        int destination = server.computeOnServer(minecraftServer -> connection.getServerLevel().getBrightness(LightLayer.BLOCK, LIT_CELL));
        BlockPos local = LIT_CELL.offset(0, 0, OFFSET_Z);
        assertTrue(destination > 0, "the destination glowstone produced no light");
        int shown = -1;
        for (int waited = 0; waited < STREAM_TIMEOUT_TICKS; waited += LIGHT_POLL_TICKS) {
            shown = context.computeOnClient(client -> client.level.getBrightness(LightLayer.BLOCK, local));
            if (shown == destination) {
                return;
            }
            context.waitTicks(LIGHT_POLL_TICKS);
        }
        String state = context.computeOnClient(client -> describeLight(local));
        assertTrue(false, "projected block light " + shown + " differs from the destination " + destination + ": " + state);
    }

    private static String describeLight(BlockPos local) {
        WormholesClient wormholes = WormholesClient.instance();
        ClientViewTick tick = wormholes.tickState();
        ProjectionOverlay overlay = tick.overlay();
        long key = ProjectionCellKey.pack(local.getX(), local.getY(), local.getZ());
        ProjectionOverlay.Entry entry = overlay.get(key);
        StringBuilder out = new StringBuilder(256);
        out.append("overlay=").append(overlay.size()).append(" entry=").append(entry == null ? "none" : entry.portalKey() + "/" + entry.projected());
        out.append(" masked=").append(tick.light().maskedCells(local.getX() >> 4, local.getY() >> 4, local.getZ() >> 4));
        out.append(" lightSections=").append(tick.light().size()).append(" refreshes=").append(tick.light().refreshes());
        for (ClientPortal portal : wormholes.session().portals().values()) {
            ClientPlate plate = portal.plate();
            out.append(" portal ").append(portal.portalKey()).append(" ready=").append(portal.ready())
                .append(" applied=").append(portal.sweep() != null && portal.sweep().applied(local.getX(), local.getY(), local.getZ()));
            if (plate != null) {
                int brick = plate.brickIndexOf(local.getX(), local.getY(), local.getZ());
                out.append(" brick=").append(brick).append(" hasLight=").append(brick >= 0 && plate.hasLight(brick));
                if (brick >= 0 && plate.hasLight(brick)) {
                    int cell = ClientViewProtocol.brickCellIndex(local.getX(), local.getY(), local.getZ());
                    out.append(" block=").append(BrickLightSource.nibble(plate.blockLight(brick), cell))
                        .append(" sky=").append(BrickLightSource.nibble(plate.skyLight(brick), cell));
                }
            }
        }
        return out.toString();
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

    private static List<BlockPos> cells(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                cells.add(min.offset(x, y, 0));
            }
        }
        return cells;
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

    private static WormholesModRuntime runtime() {
        for (ModInitializer initializer : FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class)) {
            if (initializer instanceof WormholesFabric fabric) {
                try {
                    Field field = WormholesFabric.class.getDeclaredField("runtime");
                    field.setAccessible(true);
                    return (WormholesModRuntime) field.get(fabric);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Wormholes runtime is not reachable", failure);
                }
            }
        }
        throw new IllegalStateException("Wormholes main entrypoint is not loaded");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Scene(UUID source, UUID destination, UUID rtp) {
    }
}
