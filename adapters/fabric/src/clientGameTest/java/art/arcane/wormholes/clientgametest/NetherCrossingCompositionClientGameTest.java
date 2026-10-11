package art.arcane.wormholes.clientgametest;

import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.client.WormholesClient;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
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

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class NetherCrossingCompositionClientGameTest implements FabricClientGameTest {
    private static final int TIMEOUT_TICKS = 600;
    private static final BlockPos SOURCE = SeamlessWalkThrough.NETHER_FRAME;

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean hidden = context.computeOnClient(minecraft -> minecraft.gui.hud.isHidden());
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            FabricSeamlessClient client = new FabricSeamlessClient(context, world.getConnection());
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            world.getServer().runOnServer(server -> {
                ServerPlayer player = world.getConnection().getServerPlayer();
                player.setGameMode(GameType.SURVIVAL);
                SeamlessWalkThrough.light(server, player, new SeamlessWalkThrough.NetherFrameFixture(SOURCE, 3));
            });
            int waited = 0;
            while (!world.getServer().computeOnServer(server -> SeamlessWalkThrough.lit(server, SOURCE))) {
                assertTrue(waited++ < TIMEOUT_TICKS, "Native Nether ignition was never converted");
                client.waitTicks(1);
            }
            Scene scene = world.getServer().computeOnServer(server -> {
                MinecraftPortal source = Objects.requireNonNull(runtime(server).portals().at(server.overworld(), SOURCE));
                MinecraftPortal destination = Objects.requireNonNull(runtime(server).portals().get(source.getDestinationId()));
                ServerLevel target = Objects.requireNonNull(server.getLevel(Level.NETHER));
                List<NetherCrossingCompositionTap.Marker> markers = new ArrayList<>();
                decorate(server.overworld(), source, false, markers);
                decorate(target, destination, true, markers);
                return new Scene(source.getId(), destination.getId(), point(source.getOrigin()), vector(source.getFrame().getNormal()),
                    vector(source.getFrame().getRight()), List.copyOf(markers));
            });
            client.runOnClient(minecraft -> {
                if (!minecraft.gui.hud.isHidden()) {
                    minecraft.gui.hud.toggle();
                }
                minecraft.gui.toastManager().clear();
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                minecraft.options.fov().set(70);
                minecraft.options.bobView().set(false);
                minecraft.options.fovEffectScale().set(0.0D);
                minecraft.options.framerateLimit().set(120);
                minecraft.player.setSprinting(false);
            });
            roundTrip(client, world, scene, 0.0D, 6.5D, "center");
            roundTrip(client, world, scene, 75.0D, 0.30D, "75-immediate-reverse");
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
            context.runOnClient(minecraft -> NetherCrossingCompositionTap.close());
            context.runOnClient(minecraft -> {
                if (minecraft.gui.hud.isHidden() != hidden) {
                    minecraft.gui.hud.toggle();
                }
            });
            context.restoreDefaultGameOptions();
        }
    }

    private static void roundTrip(FabricSeamlessClient client, TestSingleplayerContext world, Scene scene, double angle, double gap, String label) {
        double side = client.computeOnClient(minecraft -> Math.signum(minecraft.player.position().subtract(scene.origin()).dot(scene.normal())));
        assertTrue(side != 0.0D, "Nether ignition actor was placed on the source plane");
        Vec3 direction = scene.normal().scale(-side * Math.cos(Math.toRadians(angle)))
            .add(scene.right().scale(Math.sin(Math.toRadians(angle))));
        Vec3 start = new Vec3(scene.origin().x, SOURCE.getY(), scene.origin().z)
            .add(scene.normal().scale(side * gap)).add(scene.right().scale(-gap * Math.tan(Math.toRadians(angle))));
        float yaw = yaw(direction);
        world.getServer().runOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            player.teleportTo(server.overworld(), start.x, start.y, start.z, Set.of(), yaw, 0.0F, false);
        });
        client.waitFor(minecraft -> minecraft.player != null && minecraft.player.position().distanceTo(start) < 0.05D, TIMEOUT_TICKS);
        client.lookAt(yaw, 0.0F);
        client.waitForChunksRender();
        client.waitFor(minecraft -> WormholesClient.instance().seamlessTravel().arms().stream()
            .anyMatch(arm -> arm.sourcePortal().equals(scene.source()) && arm.sourceGeometry().frontSide() == (side > 0.0D))
            && WormholesClient.instance().seamlessTravel().unprepared() == null
            && !WormholesClient.instance().seamlessTravel().pending(), TIMEOUT_TICKS);
        client.runOnClient(minecraft -> TravelTap.reset());
        int player = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player));
        int sourceLevel = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player.level()));
        client.runOnClient(minecraft -> NetherCrossingCompositionTap.arm(minecraft, label, scene.markers()));
        try {
            cross(client, sourceLevel);
            if (angle == 0.0D) {
                client.waitFor(minecraft -> NetherCrossingCompositionTap.framesAfterSwap() >= 16, TIMEOUT_TICKS);
            }
            float reverseYaw = client.computeOnClient(minecraft -> minecraft.player.getYRot() + 180.0F);
            client.lookAt(reverseYaw, 0.0F);
            int destinationLevel = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player.level()));
            cross(client, destinationLevel);
            client.waitFor(minecraft -> NetherCrossingCompositionTap.framesAfterSwap() >= 16, TIMEOUT_TICKS);
            client.waitFor(minecraft -> !WormholesClient.instance().seamlessTravel().pending(), TIMEOUT_TICKS);
            assertTrue(client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player)) == player, label + ": local player replaced");
            assertTrue(client.computeOnClient(minecraft -> minecraft.player.level().dimension()) == Level.OVERWORLD,
                label + ": immediate reverse did not return to Overworld");
            assertTrue(TravelTap.respawns() == 0 && TravelTap.positions() == 0 && TravelTap.accepts() == 0,
                label + ": native respawn/correction/teleport acknowledgement " + TravelTap.events());
            assertTrue(!TravelTap.loadingScreenShown() && !TravelTap.clientUnloaded(), label + ": loading screen or unloaded connection");
            client.runOnClient(minecraft -> NetherCrossingCompositionTap.stop());
            client.waitFor(minecraft -> NetherCrossingCompositionTap.complete(), TIMEOUT_TICKS);
            NetherCrossingCompositionTap.verify();
        } finally {
            client.releaseForward();
            client.runOnClient(minecraft -> NetherCrossingCompositionTap.close());
        }
    }

    private static void cross(FabricSeamlessClient client, int departure) {
        client.holdForward();
        try {
            client.waitFor(minecraft -> System.identityHashCode(minecraft.player.level()) != departure, TIMEOUT_TICKS);
        } finally {
            client.releaseForward();
        }
    }

    private static void decorate(ServerLevel level, MinecraftPortal portal, boolean destination, List<NetherCrossingCompositionTap.Marker> markers) {
        Vec3 origin = point(portal.getOrigin());
        Vec3 normal = vector(portal.getFrame().getNormal());
        Vec3 right = vector(portal.getFrame().getRight());
        int floor = (int) Math.floor(origin.y - 1.5D) - 1;
        BlockPos center = BlockPos.containing(origin.x, floor, origin.z);
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                for (int y = 0; y <= 7; y++) {
                    BlockPos cell = center.offset(x, y, z);
                    if (!level.getBlockState(cell).is(Blocks.OBSIDIAN)) {
                        level.setBlockAndUpdate(cell, y == 0 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        for (int side : new int[] {-1, 1}) {
            for (int column = 0; column < 3; column++) {
                int color = destination ? (column + 1) % 3 : column;
                double lateral = switch (column) {
                    case 0 -> -3.0D;
                    case 1 -> 2.0D;
                    default -> -0.5D;
                };
                double depth = column == 2 ? 8.0D : 4.0D;
                for (int row = 0; row < 4; row++) {
                    Vec3 position = origin.add(normal.scale(side * depth)).add(right.scale(lateral));
                    BlockPos cell = BlockPos.containing(position.x, floor + 1 + row, position.z);
                    BlockState state = row % 2 == 0 ? color(color) : Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState();
                    level.setBlockAndUpdate(cell, state);
                    if (row % 2 == 0) {
                        markers.add(new NetherCrossingCompositionTap.Marker(portal.getWorldKey(), cell.getX(), cell.getY(), cell.getZ(), color));
                    }
                }
            }
        }
    }

    private static BlockState color(int color) {
        return switch (color) {
            case 0 -> Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState();
            case 1 -> Blocks.CONCRETE.pick(DyeColor.BLUE).defaultBlockState();
            default -> Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState();
        };
    }

    private static float yaw(Vec3 direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    private static Vec3 point(Vec3d point) {
        return new Vec3(point.x(), point.y(), point.z());
    }

    private static Vec3 vector(Face face) {
        return new Vec3(face.x(), face.y(), face.z());
    }

    private record Scene(UUID source, UUID destination, Vec3 origin, Vec3 normal, Vec3 right,
                         List<NetherCrossingCompositionTap.Marker> markers) {
    }
}
