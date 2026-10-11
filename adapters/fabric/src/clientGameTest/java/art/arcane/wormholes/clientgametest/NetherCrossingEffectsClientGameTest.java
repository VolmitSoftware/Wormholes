package art.arcane.wormholes.clientgametest;

import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.mixin.client.ParticleEngineAccess;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleGroup;
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

public final class NetherCrossingEffectsClientGameTest implements FabricClientGameTest {
    private static final int TIMEOUT_TICKS = 600;
    private static final BlockPos SOURCE = SeamlessWalkThrough.NETHER_FRAME;

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean hidden = context.computeOnClient(minecraft -> minecraft.gui.hud.isHidden());
        assertTrue(context.computeOnClient(minecraft -> NativeTestWindow.hiddenAndUnfocused(minecraft)
            && NativeTestWindow.mouseReleased(minecraft) && Boolean.parseBoolean(System.getProperty("automator.hidden", "true"))),
            "Nether camera-effects fixture requires a client hidden before window creation");
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
                source.setAmbientStyle(AmbientParticleStyle.OFF);
                destination.setAmbientStyle(AmbientParticleStyle.OFF);
                runtime(server).portals().save(source);
                runtime(server).portals().save(destination);
                assertTrue(source.getAmbientStyle() == AmbientParticleStyle.OFF && destination.getAmbientStyle() == AmbientParticleStyle.OFF,
                    "Spatial fixture portal ambient settings did not apply");
                System.out.println("WORMHOLES_NETHER_EFFECTS_SCENE sourceAmbient=" + source.getAmbientStyle()
                    + " destinationAmbient=" + destination.getAmbientStyle() + " globalParticlesUnchanged=true");
                List<NetherCrossingEffectsTap.Marker> markers = new ArrayList<>();
                List<NetherCrossingEffectsTap.Occluder> occluders = new ArrayList<>();
                decorate(server.overworld(), source, false, markers, occluders);
                decorate(target, destination, true, markers, occluders);
                return new Scene(source.getId(), point(source.getOrigin()), vector(source.getFrame().getNormal()),
                    vector(source.getFrame().getRight()), List.copyOf(markers), List.copyOf(occluders));
            });
            client.runOnClient(minecraft -> {
                if (!minecraft.gui.hud.isHidden()) {
                    minecraft.gui.hud.toggle();
                }
                minecraft.gui.toastManager().clear();
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                minecraft.options.fov().set(70);
                minecraft.options.bobView().set(true);
                minecraft.options.fovEffectScale().set(1.0D);
                minecraft.options.framerateLimit().set(20);
                minecraft.player.setSprinting(false);
            });
            roundTrip(client, world, scene, 0.0D, 6.5D, "center");
            roundTrip(client, world, scene, 75.0D, 2.0D, "75-immediate-reverse");
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
            context.runOnClient(minecraft -> NetherCrossingEffectsTap.close());
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
            .add(scene.normal().scale(side * gap));
        float yaw = yaw(direction);
        float leadYaw = yaw(scene.normal().scale(-side));
        world.getServer().runOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            player.teleportTo(server.overworld(), start.x, start.y, start.z, Set.of(), leadYaw, 0.0F, false);
        });
        client.waitFor(minecraft -> minecraft.player != null && minecraft.player.position().distanceTo(start) < 0.05D, TIMEOUT_TICKS);
        client.lookAt(leadYaw, 0.0F);
        client.waitForChunksRender();
        client.waitFor(minecraft -> WormholesClient.instance().seamlessTravel().arms().stream()
            .anyMatch(arm -> arm.sourcePortal().equals(scene.source()) && arm.sourceGeometry().frontSide() == (side > 0.0D))
            && WormholesClient.instance().seamlessTravel().unprepared() == null
            && !WormholesClient.instance().seamlessTravel().pending(), TIMEOUT_TICKS);
        int quietTicks = 0;
        int particleWaitTicks = 0;
        while (quietTicks < 3) {
            assertTrue(particleWaitTicks++ < TIMEOUT_TICKS, label + ": native setup particles or opening animations did not finish");
            quietTicks = client.computeOnClient(NetherCrossingEffectsClientGameTest::particlesQuiet) ? quietTicks + 1 : 0;
            client.waitTicks(1);
        }
        client.runOnClient(minecraft -> System.out.println("WORMHOLES_NETHER_EFFECTS_PARTICLE_QUIET " + label
            + " ambient=OFF mainWorldPending=0 mainWorldEmitters=0 mainWorldLiveParticles=0 animations=0 consecutiveTicks=3 residentParticleExpiryProof=false"));
        client.runOnClient(minecraft -> NetherCrossingEffectsTap.prepare(minecraft, label, scene.markers(), scene.occluders()));
        try {
            client.waitFor(minecraft -> NetherCrossingEffectsTap.ready(), TIMEOUT_TICKS);
        } finally {
            client.runOnClient(minecraft -> {
                NetherCrossingEffectsTap.stop();
                NetherCrossingEffectsTap.close();
            });
            client.waitFor(minecraft -> NetherCrossingEffectsTap.complete(), TIMEOUT_TICKS);
        }
        client.runOnClient(minecraft -> TravelTap.reset());
        int player = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player));
        int sourceLevel = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player.level()));
        client.runOnClient(minecraft -> NetherCrossingEffectsTap.arm(minecraft, label, scene.markers(), scene.occluders()));
        try {
            if (angle != 0.0D) {
                client.holdForward();
                client.waitFor(minecraft -> System.identityHashCode(minecraft.player.level()) == sourceLevel
                    && Math.abs(minecraft.player.getEyePosition().subtract(scene.origin()).dot(scene.normal())) <= 0.30D, TIMEOUT_TICKS);
                client.lookAt(yaw, 0.0F);
            }
            cross(client, sourceLevel);
            assertTrue(NetherCrossingEffectsTap.walkingLeadIn(), label + ": crossing lacked a nonzero walking lead-in");
            assertTrue(client.computeOnClient(minecraft -> minecraft.player.level().dimension()) == Level.NETHER,
                label + ": outbound traversal did not reach the Nether");
            if (angle == 0.0D) {
                client.waitFor(minecraft -> NetherCrossingEffectsTap.framesAfterSwap() >= 16, TIMEOUT_TICKS);
            }
            float reverseYaw = client.computeOnClient(minecraft -> minecraft.player.getYRot() + 180.0F);
            client.lookAt(reverseYaw, 0.0F);
            int destinationLevel = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player.level()));
            cross(client, destinationLevel);
            client.waitFor(minecraft -> NetherCrossingEffectsTap.framesAfterSwap() >= 16, TIMEOUT_TICKS);
            client.waitFor(minecraft -> !WormholesClient.instance().seamlessTravel().pending(), TIMEOUT_TICKS);
            assertTrue(client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player)) == player, label + ": local player replaced");
            assertTrue(client.computeOnClient(minecraft -> minecraft.player.level().dimension()) == Level.OVERWORLD,
                label + ": immediate reverse did not return to Overworld");
            assertTrue(TravelTap.respawns() == 0 && TravelTap.positions() == 0 && TravelTap.accepts() == 0,
                label + ": native respawn/correction/teleport acknowledgement " + TravelTap.events());
            assertTrue(!TravelTap.loadingScreenShown() && !TravelTap.clientUnloaded(), label + ": loading screen or unloaded connection");
            client.runOnClient(minecraft -> NetherCrossingEffectsTap.stop());
            client.waitFor(minecraft -> NetherCrossingEffectsTap.complete(), TIMEOUT_TICKS);
            NetherCrossingEffectsTap.verify(yaw);
        } finally {
            client.releaseForward();
            client.runOnClient(minecraft -> {
                NetherCrossingEffectsTap.stop();
                NetherCrossingEffectsTap.close();
            });
            client.waitFor(minecraft -> NetherCrossingEffectsTap.complete(), TIMEOUT_TICKS);
        }
    }

    private static boolean particlesQuiet(Minecraft minecraft) {
        ParticleEngineAccess engine = (ParticleEngineAccess) minecraft.particleEngine;
        if (!engine.wormholes$pending().isEmpty() || !engine.wormholes$emitters().isEmpty()
            || WormholesClient.instance().tickState().fx().animations() != 0) {
            return false;
        }
        for (ParticleGroup<?> group : engine.wormholes$particles().values()) {
            if (!group.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void cross(FabricSeamlessClient client, int departure) {
        client.holdForward();
        try {
            client.waitFor(minecraft -> {
                NetherCrossingEffectsTap.walkingLeadIn();
                return System.identityHashCode(minecraft.player.level()) != departure;
            }, TIMEOUT_TICKS);
        } finally {
            client.releaseForward();
        }
    }

    private static void decorate(ServerLevel level, MinecraftPortal portal, boolean destination,
                                 List<NetherCrossingEffectsTap.Marker> markers, List<NetherCrossingEffectsTap.Occluder> occluders) {
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
            for (int column = -11; column <= 11; column++) {
                for (int row = 0; row < 7; row++) {
                    Vec3 position = origin.add(normal.scale(side * 10.0D)).add(right.scale(column));
                    BlockPos cell = BlockPos.containing(position.x, floor + 1 + row, position.z);
                    int color = Math.floorMod(column + row * 2 + (destination ? 1 : 0), 3);
                    level.setBlockAndUpdate(cell, color(color));
                    markers.add(new NetherCrossingEffectsTap.Marker(portal.getWorldKey(), cell.getX(), cell.getY(), cell.getZ(), color,
                        Math.abs(normal.x) > 0.5D ? 0 : 2));
                }
            }
            for (int column = -11; column <= 11; column++) {
                for (int row = 0; row < 7; row++) {
                    Vec3 position = origin.add(right.scale(side * 12.0D)).add(normal.scale(column));
                    BlockPos cell = BlockPos.containing(position.x, floor + 1 + row, position.z);
                    int color = Math.floorMod(column * 2 + row + (destination ? 2 : 0), 3);
                    level.setBlockAndUpdate(cell, color(color));
                    markers.add(new NetherCrossingEffectsTap.Marker(portal.getWorldKey(), cell.getX(), cell.getY(), cell.getZ(), color,
                        Math.abs(right.x) > 0.5D ? 0 : 2));
                }
            }
        }
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                for (int y = 0; y <= 7; y++) {
                    BlockPos cell = center.offset(x, y, z);
                    if (level.getBlockState(cell).is(Blocks.OBSIDIAN)) {
                        occluders.add(new NetherCrossingEffectsTap.Occluder(portal.getWorldKey(), cell.getX(), cell.getY(), cell.getZ()));
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

    private record Scene(UUID source, Vec3 origin, Vec3 normal, Vec3 right,
                         List<NetherCrossingEffectsTap.Marker> markers, List<NetherCrossingEffectsTap.Occluder> occluders) {
    }
}
