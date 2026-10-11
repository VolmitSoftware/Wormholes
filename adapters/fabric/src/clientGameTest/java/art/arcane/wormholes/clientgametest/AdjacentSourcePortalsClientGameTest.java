package art.arcane.wormholes.clientgametest;

import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class AdjacentSourcePortalsClientGameTest implements FabricClientGameTest {
    private static final int PREPARE_TIMEOUT = 600;
    private static final int CROSS_TIMEOUT = 160;
    private static final BlockPos SOURCE_A = new BlockPos(0, 70, 20);
    private static final BlockPos SOURCE_B = new BlockPos(4, 70, 20);
    private static final BlockPos DESTINATION_A = new BlockPos(0, 70, 0);
    private static final BlockPos DESTINATION_B = new BlockPos(64, 70, 0);

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean capture = Boolean.parseBoolean(System.getProperty("wormholes.clientgametest.adjacentPortals.capture", "true"));
        boolean secondFirst = Boolean.getBoolean("wormholes.clientgametest.adjacentPortals.secondFirst");
        boolean repeatFirst = Boolean.getBoolean("wormholes.clientgametest.adjacentPortals.repeatFirst");
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            FabricSeamlessClient client = new FabricSeamlessClient(context, world.getConnection());
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            AdjacentSourcePortalsScene scene = world.getServer().computeOnServer(server -> build(server, world.getConnection().getServerPlayer()));
            world.getServer().runOnServer(server -> {
                ServerPlayer player = world.getConnection().getServerPlayer();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(server.overworld(), 3.5D, 70.0D, 29.0D, Set.of(), 180.0F, 0.0F, false);
            });
            client.waitFor(minecraft -> minecraft.player != null && minecraft.player.position().distanceTo(new Vec3(3.5D, 70.0D, 29.0D)) < 0.1D,
                PREPARE_TIMEOUT);
            client.runOnClient(minecraft -> {
                assertTrue(NativeTestWindow.hiddenAndUnfocused(minecraft) && NativeTestWindow.mouseReleased(minecraft)
                    && Boolean.parseBoolean(System.getProperty("automator.hidden", "true")), "Adjacent fixture requires a hidden native client");
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                minecraft.options.fov().set(70);
                minecraft.options.bobView().set(!capture);
                minecraft.options.fovEffectScale().set(capture ? 0.0D : 1.0D);
                minecraft.options.framerateLimit().set(capture ? 20 : 120);
                if (!minecraft.gui.hud.isHidden()) {
                    minecraft.gui.hud.toggle();
                }
                minecraft.gui.toastManager().clear();
                minecraft.player.setSprinting(false);
            });
            client.lookAt(180.0F, 0.0F);
            client.waitForChunksRender();
            client.waitTicks(20);
            client.runOnClient(minecraft -> {
                TravelTap.reset();
                AdjacentSourcePortalsTap.open(minecraft, scene, capture);
            });
            try {
                client.waitFor(minecraft -> AdjacentSourcePortalsTap.overviewReady(), PREPARE_TIMEOUT);
                assertTrue(AdjacentSourcePortalsTap.crossings() == 0, "Overview preparation already crossed a portal");
                int first = secondFirst ? 1 : 0;
                int second = repeatFirst ? first : 1 - first;
                roundTrip(client, scene, first);
                roundTrip(client, scene, second);
                assertTrue(AdjacentSourcePortalsTap.crossings() == 4, "Adjacent test needs exactly four real accepted crossings");
            } finally {
                client.releaseForward();
                client.runOnClient(minecraft -> AdjacentSourcePortalsTap.stop());
                try {
                    client.waitFor(minecraft -> AdjacentSourcePortalsTap.complete(), 100);
                } finally {
                    client.runOnClient(minecraft -> AdjacentSourcePortalsTap.persist());
                }
            }
            client.runOnClient(minecraft -> AdjacentSourcePortalsTap.verify());
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
            context.runOnClient(minecraft -> AdjacentSourcePortalsTap.stop());
            context.restoreDefaultGameOptions();
        }
    }

    private static void roundTrip(FabricSeamlessClient client, AdjacentSourcePortalsScene scene, int index) {
        AdjacentSourcePortalsScene.Endpoint source = scene.endpoints().get(index);
        AdjacentSourcePortalsScene.Endpoint destination = scene.endpoint(source.destination());
        moveX(client, source.minimum().getX() + 1.5D);
        moveZ(client, 29.0D);
        client.lookAt(180.0F, 0.0F);
        cross(client, source.id());
        client.lookAt(0.0F, 0.0F);
        cross(client, destination.id());
        client.holdForwardFor(12);
    }

    private static void cross(FabricSeamlessClient client, UUID portal) {
        client.waitFor(minecraft -> WormholesClient.instance().seamlessTravel().arms().stream().anyMatch(arm -> arm.sourcePortal().equals(portal))
            && WormholesClient.instance().seamlessTravel().unprepared() == null && !WormholesClient.instance().seamlessTravel().pending(), PREPARE_TIMEOUT);
        int before = AdjacentSourcePortalsTap.crossings();
        client.runOnClient(minecraft -> AdjacentSourcePortalsTap.expect(portal));
        client.holdForward();
        try {
            client.waitFor(minecraft -> AdjacentSourcePortalsTap.crossings() == before + 1, CROSS_TIMEOUT);
        } finally {
            client.releaseForward();
        }
        client.waitFor(minecraft -> AdjacentSourcePortalsTap.framesAfterCrossing() >= 50
            && !WormholesClient.instance().seamlessTravel().pending(), PREPARE_TIMEOUT);
        assertTrue(TravelTap.respawns() == 0 && TravelTap.positions() == 0 && TravelTap.accepts() == 0,
            "Adjacent first-four walks received native respawn/correction/acknowledgement: " + TravelTap.events());
        assertTrue(!TravelTap.loadingScreenShown() && !TravelTap.clientUnloaded(), "Adjacent first-four walks unloaded or showed a loading screen");
    }

    private static void moveX(FabricSeamlessClient client, double target) {
        double current = client.computeOnClient(minecraft -> minecraft.player.getX());
        if (Math.abs(current - target) <= 0.15D) {
            return;
        }
        boolean increasing = current < target;
        client.lookAt(increasing ? -90.0F : 90.0F, 0.0F);
        client.holdForward();
        try {
            client.waitFor(minecraft -> increasing ? minecraft.player.getX() >= target - 0.1D : minecraft.player.getX() <= target + 0.1D, CROSS_TIMEOUT);
        } finally {
            client.releaseForward();
        }
        assertTrue(client.computeOnClient(minecraft -> Math.abs(minecraft.player.getX() - target) < 0.5D), "Source lateral approach overshot");
    }

    private static void moveZ(FabricSeamlessClient client, double target) {
        double current = client.computeOnClient(minecraft -> minecraft.player.getZ());
        if (Math.abs(current - target) <= 0.15D) {
            return;
        }
        boolean increasing = current < target;
        client.lookAt(increasing ? 0.0F : 180.0F, 0.0F);
        client.holdForward();
        try {
            client.waitFor(minecraft -> increasing ? minecraft.player.getZ() >= target - 0.1D : minecraft.player.getZ() <= target + 0.1D, CROSS_TIMEOUT);
        } finally {
            client.releaseForward();
        }
    }

    private static AdjacentSourcePortalsScene build(MinecraftServer server, ServerPlayer actor) {
        ServerLevel source = server.overworld();
        ServerLevel destination = Objects.requireNonNull(server.getLevel(Level.NETHER));
        List<AdjacentSourcePortalsScene.Wall> walls = new ArrayList<>();
        List<AdjacentSourcePortalsScene.Cuboid> opaque = new ArrayList<>();
        room(source, -12, 19, 8, 33, 0, 0, walls, opaque);
        room(destination, -12, 15, -12, 33, 3, 1, walls, opaque);
        room(destination, 52, 79, -12, 33, 3, 2, walls, opaque);
        for (BlockPos minimum : List.of(SOURCE_A, SOURCE_B)) {
            frame(source, minimum, opaque);
        }
        for (BlockPos minimum : List.of(DESTINATION_A, DESTINATION_B)) {
            frame(destination, minimum, opaque);
        }
        WormholesModRuntime runtime = runtime(server);
        MinecraftPortal sourceA = portal(runtime, actor, source, SOURCE_A);
        MinecraftPortal sourceB = portal(runtime, actor, source, SOURCE_B);
        MinecraftPortal destinationA = portal(runtime, actor, destination, DESTINATION_A);
        MinecraftPortal destinationB = portal(runtime, actor, destination, DESTINATION_B);
        link(runtime, actor, sourceA, destinationA);
        link(runtime, actor, sourceB, destinationB);
        return new AdjacentSourcePortalsScene(List.of(endpoint(sourceA, destinationA, SOURCE_A), endpoint(sourceB, destinationB, SOURCE_B),
            endpoint(destinationA, sourceA, DESTINATION_A), endpoint(destinationB, sourceB, DESTINATION_B)), walls, opaque);
    }

    private static MinecraftPortal portal(WormholesModRuntime runtime, ServerPlayer actor, ServerLevel level, BlockPos minimum) {
        MinecraftPortal portal = Objects.requireNonNull(runtime.portals().create(actor.getUUID(), level, cells(minimum), PortalType.PORTAL,
            new Vec3(0.0D, 0.0D, -1.0D)), "Adjacent portal creation rejected");
        portal.setOrientation(OrientationPolicy.FRAME);
        portal.setAmbientStyle(AmbientParticleStyle.OFF);
        portal.setProjectionMode(ProjectionMode.ON);
        portal.setScaleRule(ScaleRule.OFF);
        assertTrue(portal.setApertureShape(ShapeDescriptor.FULL), "Adjacent full aperture rejected");
        runtime.portals().save(portal);
        return portal;
    }

    private static void link(WormholesModRuntime runtime, ServerPlayer actor, MinecraftPortal source, MinecraftPortal destination) {
        assertTrue(runtime.portals().link(actor, source.getId(), destination.getId()), "Adjacent outbound link rejected");
        assertTrue(runtime.portals().link(actor, destination.getId(), source.getId()), "Adjacent return link rejected");
        assertTrue(source.getDestinationId().equals(destination.getId()) && destination.getDestinationId().equals(source.getId()),
            "Adjacent endpoints linked to the wrong pair");
    }

    private static AdjacentSourcePortalsScene.Endpoint endpoint(MinecraftPortal source, MinecraftPortal destination, BlockPos minimum) {
        Face normal = source.getFrame().getNormal();
        assertTrue(Math.abs(normal.z()) == 1, "Adjacent oracle supports only axis-aligned vertical Z portals");
        return new AdjacentSourcePortalsScene.Endpoint(source.getId(), destination.getId(), source.getWorldKey(), minimum,
            source.getOrigin(), new Vec3d(normal.x(), normal.y(), normal.z()),
            OpticTransform.between(source.getFrame().view(true), source.getOrigin(), destination.getFrame().view(true), destination.getOrigin()),
            OpticTransform.between(source.getFrame().view(false), source.getOrigin(), destination.getFrame().view(false), destination.getOrigin()));
    }

    private static void room(ServerLevel level, int left, int right, int back, int front, int palette, int offset,
                             List<AdjacentSourcePortalsScene.Wall> walls, List<AdjacentSourcePortalsScene.Cuboid> opaque) {
        String world = level.dimension().identifier().toString();
        for (int x = left; x <= right; x++) {
            for (int z = back; z <= front; z++) {
                for (int y = 69; y <= 77; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), y == 69 || y == 77 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
        opaque.add(new AdjacentSourcePortalsScene.Cuboid(world, left, 69, back, right - left + 1, 1, front - back + 1));
        opaque.add(new AdjacentSourcePortalsScene.Cuboid(world, left, 77, back, right - left + 1, 1, front - back + 1));
        for (int x : new int[] {left, right}) {
            paint(level, new AdjacentSourcePortalsScene.Wall(world, 0, x, back, front + 1, 70, 77, palette, offset), walls);
        }
        for (int z : new int[] {back, front}) {
            paint(level, new AdjacentSourcePortalsScene.Wall(world, 2, z, left + 1, right, 70, 77, palette, offset + 1), walls);
        }
    }

    private static void paint(ServerLevel level, AdjacentSourcePortalsScene.Wall wall, List<AdjacentSourcePortalsScene.Wall> walls) {
        walls.add(wall);
        for (int column = wall.minimum(); column < wall.maximum(); column++) {
            for (int row = wall.bottom(); row < wall.top(); row++) {
                BlockPos position = wall.axis() == 0 ? new BlockPos(wall.coordinate(), row, column) : new BlockPos(column, row, wall.coordinate());
                DyeColor color = switch (wall.color(column, row)) {
                    case 0 -> DyeColor.RED;
                    case 1 -> DyeColor.LIME;
                    case 2 -> DyeColor.BLUE;
                    case 3 -> DyeColor.YELLOW;
                    case 4 -> DyeColor.CYAN;
                    default -> DyeColor.MAGENTA;
                };
                BlockState state = Blocks.CONCRETE.pick(color).defaultBlockState();
                level.setBlockAndUpdate(position, state);
            }
        }
    }

    private static void frame(ServerLevel level, BlockPos minimum, List<AdjacentSourcePortalsScene.Cuboid> opaque) {
        for (int x = -1; x <= 3; x++) {
            for (int y = -1; y <= 3; y++) {
                if (x != -1 && x != 3 && y != -1 && y != 3) {
                    continue;
                }
                BlockPos cell = minimum.offset(x, y, 0);
                level.setBlockAndUpdate(cell, Blocks.OBSIDIAN.defaultBlockState());
                opaque.add(new AdjacentSourcePortalsScene.Cuboid(level.dimension().identifier().toString(), cell.getX(), cell.getY(), cell.getZ(), 1, 1, 1));
            }
        }
    }
}
