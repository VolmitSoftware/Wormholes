package art.arcane.wormholes.clientgametest;

import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class ArrivalTerrainClientGameTest implements FabricClientGameTest {
    private static final int PREPARE_TIMEOUT = 600;
    private static final int CROSS_TIMEOUT = 160;
    private static final BlockPos PORTAL = new BlockPos(0, 70, 0);
    private static final Vec3 START = new Vec3(1.5D, 70.0D, 8.0D);

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean hidden = context.computeOnClient(minecraft -> minecraft.gui.hud.isHidden());
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            FabricSeamlessClient client = new FabricSeamlessClient(context, world.getConnection());
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            Scene scene = world.getServer().computeOnServer(server -> build(server, world.getConnection().getServerPlayer()));
            world.getServer().runOnServer(server -> {
                ServerPlayer player = world.getConnection().getServerPlayer();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(server.overworld(), START.x, START.y, START.z, Set.of(), 180.0F, 0.0F, false);
            });
            client.waitFor(minecraft -> minecraft.player != null && minecraft.player.position().distanceTo(START) < 0.05D, PREPARE_TIMEOUT);
            client.runOnClient(minecraft -> {
                assertTrue(NativeTestWindow.hiddenAndUnfocused(minecraft) && NativeTestWindow.mouseReleased(minecraft),
                    "Arrival terrain requires a hidden, unfocused native GPU client with released mouse");
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                minecraft.options.fov().set(70);
                minecraft.options.bobView().set(false);
                minecraft.options.ambientOcclusion().set(false);
                minecraft.options.fovEffectScale().set(0.0D);
                minecraft.options.framerateLimit().set(20);
                minecraft.player.setSprinting(false);
                if (!minecraft.gui.hud.isHidden()) {
                    minecraft.gui.hud.toggle();
                }
                minecraft.gui.toastManager().clear();
            });
            client.lookAt(180.0F, 0.0F);
            client.waitFor(minecraft -> roomReceived(minecraft.level), PREPARE_TIMEOUT);
            client.waitForChunksRender();
            client.runOnClient(minecraft -> {
                TravelTap.reset();
                ArrivalTerrainTap.open(minecraft);
            });
            try {
                client.waitFor(minecraft -> ArrivalTerrainTap.sourceReady(), PREPARE_TIMEOUT);
                for (int round = 0; round < 2; round++) {
                    cross(client, scene.source(), "minecraft:the_nether");
                    approach(client);
                    cross(client, scene.destination(), "minecraft:overworld");
                    if (round == 0) {
                        approach(client);
                    }
                }
                client.waitFor(minecraft -> !WormholesClient.instance().seamlessTravel().pending(), PREPARE_TIMEOUT);
                assertTrue(client.computeOnClient(minecraft -> minecraft.player.level().dimension()) == Level.OVERWORLD,
                    "Arrival terrain round trips did not finish in Overworld");
            } finally {
                client.releaseForward();
                client.runOnClient(minecraft -> ArrivalTerrainTap.stop());
                try {
                    client.waitFor(minecraft -> ArrivalTerrainTap.complete(), PREPARE_TIMEOUT);
                } finally {
                    client.runOnClient(minecraft -> ArrivalTerrainTap.persist());
                }
            }
            client.runOnClient(minecraft -> ArrivalTerrainTap.verify());
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
            context.runOnClient(minecraft -> ArrivalTerrainTap.stop());
            context.runOnClient(minecraft -> {
                if (minecraft.gui.hud.isHidden() != hidden) {
                    minecraft.gui.hud.toggle();
                }
            });
            context.restoreDefaultGameOptions();
        }
    }

    private static void cross(FabricSeamlessClient client, UUID portal, String destination) {
        client.lookAt(client.computeOnClient(minecraft -> minecraft.player.getZ() > 0.5D ? 180.0F : 0.0F), 0.0F);
        client.waitTicks(2);
        client.waitFor(minecraft -> WormholesClient.instance().seamlessTravel().arms().stream().anyMatch(arm -> arm.sourcePortal().equals(portal))
            && WormholesClient.instance().seamlessTravel().unprepared() == null && !WormholesClient.instance().seamlessTravel().pending(), PREPARE_TIMEOUT);
        int ordinal = client.computeOnClient(minecraft -> ArrivalTerrainTap.crossings()) + 1;
        client.runOnClient(minecraft -> ArrivalTerrainTap.expect(portal, destination));
        client.holdForward();
        try {
            client.waitFor(minecraft -> ArrivalTerrainTap.crossings() == ordinal, CROSS_TIMEOUT);
        } finally {
            client.releaseForward();
        }
        client.waitFor(minecraft -> ArrivalTerrainTap.arrivalReady(ordinal), PREPARE_TIMEOUT);
        assertTrue(TravelTap.respawns() == 0 && TravelTap.positions() == 0 && TravelTap.accepts() == 0,
            "Arrival terrain native walk received respawn/correction/acknowledgement: " + TravelTap.events());
        assertTrue(!TravelTap.loadingScreenShown() && !TravelTap.clientUnloaded(), "Arrival terrain native walk unloaded or showed a loading screen");
    }

    private static void approach(FabricSeamlessClient client) {
        double side = client.computeOnClient(minecraft -> Math.signum(minecraft.player.getZ() - 0.5D));
        double target = 0.5D + side * (START.z - 0.5D);
        client.lookAt(side > 0.0D ? 0.0F : 180.0F, 0.0F);
        client.holdForward();
        try {
            client.waitFor(minecraft -> side * (minecraft.player.getZ() - target) >= -0.1D, CROSS_TIMEOUT);
        } finally {
            client.releaseForward();
        }
        assertTrue(client.computeOnClient(minecraft -> Math.abs(minecraft.player.getZ() - target) < 0.6D
            && Math.abs(minecraft.player.getX() - START.x) < 0.3D), "Arrival terrain physical return approach overshot");
    }

    private static Scene build(MinecraftServer server, ServerPlayer actor) {
        ServerLevel sourceLevel = server.overworld();
        ServerLevel destinationLevel = Objects.requireNonNull(server.getLevel(Level.NETHER));
        for (ServerLevel level : List.of(sourceLevel, destinationLevel)) {
            room(level);
            frame(level);
        }
        WormholesModRuntime runtime = runtime(server);
        MinecraftPortal source = portal(runtime, actor, sourceLevel);
        MinecraftPortal destination = portal(runtime, actor, destinationLevel);
        assertTrue(runtime.portals().link(actor, source.getId(), destination.getId()), "Arrival terrain outbound link rejected");
        assertTrue(runtime.portals().link(actor, destination.getId(), source.getId()), "Arrival terrain return link rejected");
        return new Scene(source.getId(), destination.getId());
    }

    private static MinecraftPortal portal(WormholesModRuntime runtime, ServerPlayer actor, ServerLevel level) {
        MinecraftPortal portal = Objects.requireNonNull(runtime.portals().create(actor.getUUID(), level, cells(PORTAL), PortalType.PORTAL,
            new Vec3(0.0D, 0.0D, -1.0D)), "Arrival terrain portal creation rejected");
        portal.setOrientation(OrientationPolicy.FRAME);
        portal.setAmbientStyle(AmbientParticleStyle.OFF);
        portal.setProjectionMode(ProjectionMode.ON);
        portal.setScaleRule(ScaleRule.OFF);
        assertTrue(portal.setApertureShape(ShapeDescriptor.FULL), "Arrival terrain full aperture rejected");
        runtime.portals().save(portal);
        return portal;
    }

    private static void room(ServerLevel level) {
        String world = level.dimension().identifier().toString();
        for (int x = -15; x <= 17; x++) {
            for (int z = -19; z <= 19; z++) {
                for (int y = 69; y <= 78; y++) {
                    int axis = x == -15 || x == 17 ? 0 : z == -19 || z == 19 ? 2 : 1;
                    int column = axis == 0 ? z : x;
                    int row = axis == 1 ? z : y;
                    BlockState state = y == 78 ? Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState()
                        : y == 69 || x == -15 || x == 17 || z == -19 || z == 19
                        ? color(ArrivalTerrainTap.tile(world, column, row)) : interior(x, y, z);
                    level.setBlockAndUpdate(new BlockPos(x, y, z), state);
                }
            }
        }
    }

    private static BlockState interior(int x, int y, int z) {
        return z == 0 && x >= 0 && x <= 2 && y >= 70 && y <= 72
            ? Blocks.AIR.defaultBlockState() : Blocks.LIGHT.defaultBlockState();
    }

    private static BlockState color(int color) {
        return switch (color) {
            case 0 -> Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState();
            case 1 -> Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState();
            case 2 -> Blocks.CONCRETE.pick(DyeColor.BLUE).defaultBlockState();
            default -> Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState();
        };
    }

    private static boolean roomReceived(ClientLevel level) {
        if (level == null) {
            return false;
        }
        String world = level.dimension().identifier().toString();
        for (int x = -15; x <= 17; x++) {
            for (int z = -19; z <= 19; z++) {
                for (int y = 69; y <= 78; y++) {
                    boolean frame = z == 0 && x >= -1 && x <= 3 && y >= 69 && y <= 73
                        && (x == -1 || x == 3 || y == 69 || y == 73);
                    int axis = x == -15 || x == 17 ? 0 : z == -19 || z == 19 ? 2 : 1;
                    int column = axis == 0 ? z : x;
                    int row = axis == 1 ? z : y;
                    BlockState expected = frame ? Blocks.OBSIDIAN.defaultBlockState()
                        : y == 78 ? Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState()
                        : y == 69 || x == -15 || x == 17 || z == -19 || z == 19
                        ? color(ArrivalTerrainTap.tile(world, column, row)) : interior(x, y, z);
                    if (level.getBlockState(new BlockPos(x, y, z)) != expected) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void frame(ServerLevel level) {
        for (int x = -1; x <= 3; x++) {
            for (int y = -1; y <= 3; y++) {
                if (x == -1 || x == 3 || y == -1 || y == 3) {
                    level.setBlockAndUpdate(PORTAL.offset(x, y, 0), Blocks.OBSIDIAN.defaultBlockState());
                }
            }
        }
    }

    private record Scene(UUID source, UUID destination) {
    }
}
