package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.portal.PortalType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class EmbeddedFloorPortalClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final BlockPos SOURCE = new BlockPos(224, 80, 224);
    private static final BlockPos DESTINATION = new BlockPos(256, 100, 224);
    private static final Vec3 RIM = new Vec3(223.5D, 82.0D, 225.5D);
    private static final Vec3 DROP = new Vec3(225.5D, 81.0D, 225.5D);
    private static final int PREPARE_TICKS = 600;
    private static final int OBSERVE_TICKS = 100;
    private static final float ENTRY_YAW = 37.0F;
    private static final float ENTRY_PITCH = 42.0F;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        run(context, false);
        run(context, true);
    }

    private static void run(ClientGameTestContext context, boolean blocked) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            SeamlessClient client = new FabricSeamlessClient(context, world.getConnection());
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            context.runOnClient(EmbeddedFloorPortalClientGameTest::assertHidden);
            Scene scene = world.getServer().computeOnServer(server -> build(server, world.getConnection().getServerPlayer(), blocked));
            try {
                client.waitFor(minecraft -> minecraft.player.position().distanceTo(RIM) < 2.0D, PREPARE_TICKS);
                client.waitForChunksDownload();
                client.waitForChunksRender();
                awaitInitialSolids(context, blocked);
                assertSolids(context, world, blocked);
                rim(client, world);
                if (!blocked) {
                    client.waitFor(minecraft -> WormholesClient.instance().seamlessTravel().armed(scene.source())
                        && !WormholesClient.instance().seamlessTravel().pending(), PREPARE_TICKS);
                }
                world.getServer().runOnServer(server -> {
                    ServerPlayer player = world.getConnection().getServerPlayer();
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    park(player, server.overworld(), DROP);
                });
                client.waitFor(minecraft -> minecraft.player.getAbilities().flying && minecraft.player.position().distanceTo(DROP) < 0.1D, PREPARE_TICKS);
                int identity = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player));
                client.runOnClient(minecraft -> TravelTap.reset());
                world.getServer().runOnServer(server -> {
                    ServerPlayer player = world.getConnection().getServerPlayer();
                    player.getAbilities().flying = false;
                    player.onUpdateAbilities();
                });
                client.waitFor(minecraft -> !minecraft.player.getAbilities().flying, PREPARE_TICKS);
                List<Observation> observations = observe(client);
                context.takeScreenshot(blocked ? "embedded-floor-blocked-destination" : "embedded-floor-after-gravity-crossing");
                if (blocked) {
                    blocked(client, world, observations);
                } else {
                    crossed(client, world, observations, identity);
                }
                assertLook(client, world);
                assertSolids(context, world, blocked);
                context.runOnClient(EmbeddedFloorPortalClientGameTest::assertHidden);
                LOGGER.info("WORMHOLES_EMBEDDED_FLOOR_PASS blocked {} intact_four_layer_source outside_rim_support {}",
                    blocked, observations);
            } finally {
                world.getServer().runOnServer(server -> {
                    ServerPlayer player = world.getConnection().getServerPlayer();
                    runtime(server).portals().remove(player, scene.source());
                    runtime(server).portals().remove(player, scene.destination());
                });
                client.restoreDefaultGameOptions();
            }
        }
    }

    private static Scene build(MinecraftServer server, ServerPlayer player, boolean blocked) {
        ServerLevel level = server.overworld();
        for (int x = -3; x <= 5; x++) {
            for (int z = -3; z <= 5; z++) {
                for (int y = -3; y <= 0; y++) {
                    level.setBlockAndUpdate(SOURCE.offset(x, y, z), Blocks.STONE.defaultBlockState());
                }
                for (int y = 1; y <= 8; y++) {
                    level.setBlockAndUpdate(SOURCE.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
                level.setBlockAndUpdate(DESTINATION.offset(x, -6, z), Blocks.SEA_LANTERN.defaultBlockState());
                for (int y = -5; y <= 5; y++) {
                    level.setBlockAndUpdate(DESTINATION.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        WormholesModRuntime runtime = runtime(server);
        MinecraftPortal source = runtime.portals().create(player.getUUID(), level, cells(SOURCE), PortalType.PORTAL, new Vec3(1, -1, 0));
        MinecraftPortal destination = runtime.portals().create(player.getUUID(), level, cells(DESTINATION), PortalType.PORTAL, new Vec3(0, 1, -1));
        assertTrue(source != null && destination != null, "embedded floor or ceiling portal could not be created");
        assertTrue(source.getFrame().equals(destination.getFrame()), "default embedded floor and ceiling frames differ");
        assertTrue(runtime.portals().link(player, source.getId(), destination.getId()), "embedded source link rejected");
        assertTrue(runtime.portals().link(player, destination.getId(), source.getId()), "embedded destination link rejected");
        if (blocked) {
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    for (int y = -2; y <= 3; y++) {
                        level.setBlockAndUpdate(DESTINATION.offset(x, y, z), Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        park(player, level, RIM);
        return new Scene(source.getId(), destination.getId());
    }

    private static void rim(SeamlessClient client, TestSingleplayerContext world) {
        client.waitTicks(40);
        assertTrue(client.computeOnClient(minecraft -> support(minecraft.player.position(), RIM)), "the solid rim did not support the client player");
        assertTrue(world.getServer().computeOnServer(server -> support(world.getConnection().getServerPlayer().position(), RIM)),
            "the solid rim did not support the server player");
    }

    private static List<Observation> observe(SeamlessClient client) {
        List<Observation> observations = new ArrayList<>(OBSERVE_TICKS);
        try (TickStepper stepper = client.lockstep()) {
            for (int tick = 0; tick < OBSERVE_TICKS; tick++) {
                observations.add(stepper.step(minecraft -> new Observation(minecraft.player.position(), minecraft.player.getDeltaMovement().y,
                    minecraft.player.onGround())));
            }
        }
        return observations;
    }

    private static void crossed(SeamlessClient client, TestSingleplayerContext world, List<Observation> observations, int identity) {
        boolean crossed = false;
        for (Observation observation : observations) {
            if (observation.position().x > DESTINATION.getX() - 1.0D && observation.position().y > SOURCE.getY() + 5.0D
                && observation.velocity() < 0.0D) {
                crossed = true;
                break;
            }
        }
        assertTrue(crossed, "gravity did not carry the client through the solid embedded floor: " + observations);
        assertTrue(client.computeOnClient(minecraft -> arrived(minecraft.player.position())), "the client did not remain in the destination landing shaft");
        assertTrue(world.getServer().computeOnServer(server -> arrived(world.getConnection().getServerPlayer().position())),
            "the server did not confirm the embedded floor crossing");
        assertTrue(client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player)) == identity, "embedded floor crossing replaced the local player");
        assertTrue(TravelTap.respawns() == 0 && TravelTap.positions() == 0 && TravelTap.accepts() == 0,
            "embedded floor crossing used respawn or position correction: " + TravelTap.events());
        assertTrue(!TravelTap.loadingScreenShown() && !TravelTap.clientUnloaded(), "embedded floor crossing unloaded the client world");
    }

    private static void blocked(SeamlessClient client, TestSingleplayerContext world, List<Observation> observations) {
        for (Observation observation : observations) {
            assertTrue(blockedSupport(observation.position()),
                "solid destination allowed entry or a fall through the source ground: " + observations);
        }
        assertTrue(client.computeOnClient(minecraft -> blockedSupport(minecraft.player.position())), "blocked client entry lost its source support");
        assertTrue(world.getServer().computeOnServer(server -> blockedSupport(world.getConnection().getServerPlayer().position())),
            "solid destination allowed server entry or removed source support");
    }

    private static void awaitInitialSolids(ClientGameTestContext context, boolean blocked) {
        Missing missing = null;
        for (int tick = 0; tick < PREPARE_TICKS; tick++) {
            missing = context.computeOnClient(minecraft -> firstMissing(minecraft.level, blocked));
            if (missing == null) {
                return;
            }
            if (tick == 0) {
                LOGGER.info("Embedded floor awaiting initial client blocks: {}", missing);
            }
            context.waitTicks(1);
        }
        LOGGER.error("Embedded floor initial client block synchronization timed out: {}", missing);
        context.takeScreenshot("embedded-floor-initial-block-sync-failure");
        throw new AssertionError("Initial embedded floor client blocks did not synchronize within " + PREPARE_TICKS + " ticks: " + missing);
    }

    private static void assertSolids(ClientGameTestContext context, TestSingleplayerContext world, boolean blocked) {
        Missing serverMissing = world.getServer().computeOnServer(server -> firstMissing(server.overworld(), blocked));
        Missing clientMissing = context.computeOnClient(minecraft -> firstMissing(minecraft.level, blocked));
        assertTrue(serverMissing == null, "server portal crossing removed physical stone blocks: " + serverMissing);
        assertTrue(clientMissing == null, "client portal crossing removed physical stone blocks: " + clientMissing);
    }

    private static Missing firstMissing(Level level, boolean blocked) {
        for (int x = -1; x <= 3; x++) {
            for (int z = -1; z <= 3; z++) {
                for (int y = -3; y <= 0; y++) {
                    BlockPos block = SOURCE.offset(x, y, z);
                    if (!level.getBlockState(block).is(Blocks.STONE)) {
                        return new Missing(block, level.getBlockState(block).toString(), level.hasChunkAt(block));
                    }
                }
            }
        }
        if (blocked) {
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    for (int y = -2; y <= 3; y++) {
                        BlockPos block = DESTINATION.offset(x, y, z);
                        if (!level.getBlockState(block).is(Blocks.STONE)) {
                            return new Missing(block, level.getBlockState(block).toString(), level.hasChunkAt(block));
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean support(Vec3 position, Vec3 start) {
        return Math.abs(position.x - start.x) < 0.05D && Math.abs(position.z - start.z) < 0.05D
            && Math.abs(position.y - (SOURCE.getY() + 1.0D)) < 0.05D;
    }

    private static boolean blockedSupport(Vec3 position) {
        return Math.abs(position.x - DROP.x) < 0.05D && Math.abs(position.z - DROP.z) < 0.05D
            && position.y >= SOURCE.getY() + 0.5D - 1.0E-4D && position.y <= SOURCE.getY() + 1.05D;
    }

    private static boolean arrived(Vec3 position) {
        return position.x > DESTINATION.getX() && position.x < DESTINATION.getX() + 3.0D
            && position.z > DESTINATION.getZ() && position.z < DESTINATION.getZ() + 3.0D
            && position.y >= DESTINATION.getY() - 5.05D && position.y <= DESTINATION.getY() + 1.0D;
    }

    private static void assertLook(SeamlessClient client, TestSingleplayerContext world) {
        assertTrue(client.computeOnClient(minecraft -> Math.abs(minecraft.player.getYRot() - ENTRY_YAW) < 0.5F
            && Math.abs(minecraft.player.getXRot() - ENTRY_PITCH) < 0.5F), "horizontal crossing changed the client look direction");
        assertTrue(world.getServer().computeOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            return Math.abs(player.getYRot() - ENTRY_YAW) < 0.5F && Math.abs(player.getXRot() - ENTRY_PITCH) < 0.5F;
        }), "horizontal crossing changed the server look direction");
    }

    private static void park(ServerPlayer player, ServerLevel level, Vec3 position) {
        player.teleportTo(level, position.x, position.y, position.z, Set.of(), ENTRY_YAW, ENTRY_PITCH, false);
        player.setDeltaMovement(Vec3.ZERO);
    }

    private static List<BlockPos> cells(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                cells.add(min.offset(x, 0, z));
            }
        }
        return cells;
    }

    private static void assertHidden(Minecraft client) {
        assertTrue(NativeTestWindow.hiddenAndUnfocused(client),
            "embedded floor acceptance requires a hidden, unfocused native GPU window");
        assertTrue(NativeTestWindow.mouseReleased(client), "embedded floor acceptance grabbed the desktop mouse");
    }

    private record Scene(UUID source, UUID destination) {
    }

    private record Observation(Vec3 position, double velocity, boolean onGround) {
    }

    private record Missing(BlockPos position, String state, boolean chunkLoaded) {
    }
}
