package art.arcane.wormholes.clientgametest;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.portal.PortalDefinition;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class PortalRotationClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final Field MOUSE_DX = nativeField(MouseHandler.class, "accumulatedDX");
    private static final Field MOUSE_DY = nativeField(MouseHandler.class, "accumulatedDY");
    private static final Field TELEPORT_POSITION = nativeField(ServerGamePacketListenerImpl.class, "awaitingPositionFromClient");
    private static final Field TELEPORT_ID = nativeField(ServerGamePacketListenerImpl.class, "awaitingTeleport");
    private static final Field TELEPORT_TICK = nativeField(ServerGamePacketListenerImpl.class, "awaitingTeleportTime");
    private static final int TIMEOUT_TICKS = 600;
    private static final int STABLE_VIEW_TICKS = 16;
    private static final double POSITION_TOLERANCE = 0.05D;
    private static final float ANGLE_TOLERANCE = 0.1F;
    private static final int PROBE_ATTEMPTS = 8;
    private static final int CAPTURE_TICKS = 40;
    private static final double COLOR_DOMINANCE = 1.25D;
    private static final Vec3d SOURCE_CENTER = new Vec3d(48.5D, 85.5D, 48.5D);
    private static final Vec3d DESTINATION_CENTER = new Vec3d(48.5D, 85.5D, 96.5D);
    private static final BlockPos SOURCE_LIGHT_CELL = new BlockPos(40, 85, 40);
    private static final List<Marker> MARKERS = List.of(new Marker(-1.5D, -1.5D, DyeColor.RED),
        new Marker(1.5D, -1.5D, DyeColor.BLUE), new Marker(-1.5D, 1.5D, DyeColor.LIME),
        new Marker(1.5D, 1.5D, DyeColor.YELLOW));

    private static final List<BlockPos> LIGHT_CELLS = lightCells();

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksDownload();
            context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), TIMEOUT_TICKS);
            TestServerContext server = singleplayer.getServer();
            ServerPlayer player = server.computeOnServer(minecraftServer -> singleplayer.getConnection().getServerPlayer());
            GameType gameMode = server.computeOnServer(minecraftServer -> player.gameMode.getGameModeForPlayer());
            MotionState serverMotion = server.computeOnServer(minecraftServer -> motionState(player));
            List<UUID> created = new ArrayList<UUID>();
            ClientOptions options = context.computeOnClient(client -> new ClientOptions(client.gui.hud.isHidden(),
                client.options.getCameraType(), WormholesClient.instance().config().clientRecursion));
            MotionState clientMotion = context.computeOnClient(client -> motionState(client.player));
            try {
                PortalDefinition source = PortalDefinition.builder().facing(Face.N).center(SOURCE_CENTER).size(7, 7).build();
                PortalDefinition destination = PortalDefinition.builder().facing(Face.N).center(DESTINATION_CENTER).size(7, 7).build();
                server.runOnServer(minecraftServer -> build(minecraftServer, player));
                context.runOnClient(client -> {
                    client.player.setNoGravity(true);
                    client.player.setDeltaMovement(Vec3.ZERO);
                });
                awaitServerLighting(context, server);
                UUID target = server.computeOnServer(minecraftServer -> portal(minecraftServer, player, destination, created).getId());
                UUID sourceId = server.computeOnServer(minecraftServer -> install(minecraftServer, player, source, target, created));
                context.runOnClient(client -> {
                    PortalEntityRenderTap.start();
                    client.options.setCameraType(CameraType.FIRST_PERSON);
                    WormholesClient.instance().config().clientRecursion = false;
                    if (!client.gui.hud.isHidden()) {
                        client.gui.hud.toggle();
                    }
                });
                for (Phase phase : phases(source)) {
                    server.runOnServer(minecraftServer -> rotate(minecraftServer, sourceId, phase.definition(), target));
                    ViewTarget view = view(context, server, player, phase, sourceId, target);
                    awaitClientLighting(context, server, phase);
                    singleplayer.getConnection().waitForChunksRender();
                    awaitView(context, server, player, phase, destination, view);
                    assertPixels(context, server, player, phase, destination, view);
                    context.takeScreenshot("portal-rotation-" + phase.label());
                }
                LOGGER.info("WORMHOLES_ROTATION_PASS phases {} source {} destination {}", phases(source).size(), sourceId, target);
            } finally {
                try {
                    server.runOnServer(minecraftServer -> {
                        try {
                            WormholesModRuntime runtime = runtime(minecraftServer);
                            for (UUID id : created) {
                                if (runtime.portals().get(id) != null) {
                                    runtime.portals().remove(player, id);
                                }
                            }
                        } finally {
                            player.setGameMode(gameMode);
                            restoreMotion(player, serverMotion);
                            player.onUpdateAbilities();
                        }
                    });
                } finally {
                    context.runOnClient(client -> {
                        restoreMotion(client.player, clientMotion);
                        PortalEntityRenderTap.stop();
                        client.options.setCameraType(options.camera());
                        if (WormholesClient.instance() != null) {
                            WormholesClient.instance().config().clientRecursion = options.recursion();
                        }
                        if (client.gui.hud.isHidden() != options.hidden()) {
                            client.gui.hud.toggle();
                        }
                    });
                }
            }
        }
    }

    private static List<Phase> phases(PortalDefinition source) {
        List<Phase> phases = new ArrayList<Phase>();
        ViewPose regular = new ViewPose(false, 6.0D, 0.0D, 0.0D, true);
        for (Axis axis : Axis.values()) {
            PortalDefinition pose = source;
            for (int step = 0; step <= 4; step++) {
                if (step > 0) {
                    pose = pose.rotatedAbout(axis, QuarterTurn.DEGREES_90);
                }
                phases.add(new Phase(axis.name().toLowerCase() + "-" + step * 90, pose, regular));
            }
            assertTrue(pose.frame().equals(source.frame()) && pose.origin().distance(source.origin()) < 1.0E-9D,
                "a full cardinal rotation did not restore its source frame on " + axis);
        }
        PortalDefinition combined = source.rotatedAbout(Axis.X, QuarterTurn.DEGREES_90)
            .rotatedAbout(Axis.Y, QuarterTurn.DEGREES_90).rotatedAbout(Axis.Z, QuarterTurn.DEGREES_90);
        phases.add(new Phase("combined-xyz", combined, regular));
        phases.add(new Phase("combined-opposite-face", combined, new ViewPose(true, 6.0D, 0.0D, 0.0D, true)));
        phases.add(new Phase("combined-oblique", combined, new ViewPose(false, 6.0D, 0.65D, -0.35D, true)));
        phases.add(new Phase("near-plane-wall", source.rotatedAbout(Axis.Z, QuarterTurn.DEGREES_90),
            new ViewPose(false, 0.35D, 0.03D, -0.03D, false)));
        phases.add(new Phase("near-plane-floor", source.rotatedAbout(Axis.X, QuarterTurn.DEGREES_270),
            new ViewPose(false, 0.35D, 0.05D, 0.0D, false)));
        return phases;
    }

    private static void build(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = server.overworld();
        level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
        level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
        fill(level, new BlockPos(36, 73, 36), 25, 25, 25, Blocks.SEA_LANTERN.defaultBlockState());
        fill(level, new BlockPos(37, 74, 37), 23, 23, 23, Blocks.AIR.defaultBlockState());
        fill(level, new BlockPos(36, 73, 84), 25, 25, 25, Blocks.AIR.defaultBlockState());
        for (int z : new int[]{92, 100}) {
            for (int x = 45; x <= 51; x++) {
                for (int y = 82; y <= 88; y++) {
                    DyeColor color = x < 48 ? y < 85 ? DyeColor.RED : DyeColor.LIME : y < 85 ? DyeColor.BLUE : DyeColor.YELLOW;
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.CONCRETE.pick(color).defaultBlockState());
                }
            }
        }
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.setNoGravity(true);
        player.setDeltaMovement(Vec3.ZERO);
        player.onUpdateAbilities();
    }

    private static MotionState motionState(Player player) {
        return new MotionState(player.getAbilities().pack(), player.isNoGravity());
    }

    private static void restoreMotion(Player player, MotionState state) {
        player.getAbilities().apply(state.abilities());
        player.setNoGravity(state.noGravity());
    }

    private static List<BlockPos> lightCells() {
        List<BlockPos> cells = new ArrayList<BlockPos>(73);
        cells.add(SOURCE_LIGHT_CELL);
        for (int z : new int[]{93, 99}) {
            for (Marker marker : MARKERS) {
                BlockPos face = BlockPos.containing(DESTINATION_CENTER.x() + marker.right(),
                    DESTINATION_CENTER.y() + marker.up(), z);
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 1; y++) {
                        cells.add(face.offset(x, y, 0));
                    }
                }
            }
        }
        return List.copyOf(cells);
    }

    private static void awaitServerLighting(ClientGameTestContext context, TestServerContext server) {
        CompletableFuture<Void> pending = server.computeOnServer(minecraftServer -> {
            List<CompletableFuture<?>> tasks = new ArrayList<CompletableFuture<?>>(8);
            for (int x = 2; x <= 3; x++) {
                for (int z : new int[]{2, 3, 5, 6}) {
                    tasks.add(minecraftServer.overworld().getChunkSource().getLightEngine().waitForPendingTasks(x, z));
                }
            }
            return CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[0]));
        });
        for (int tick = 0; tick < TIMEOUT_TICKS && !pending.isDone(); tick++) {
            context.waitTicks(1);
        }
        if (!pending.isDone()) {
            logLighting(context, server, "server-tasks-timeout");
            throw new AssertionError("rotation native server lighting tasks did not complete");
        }
        pending.join();
        for (int tick = 0; tick < TIMEOUT_TICKS; tick += 5) {
            String failure = server.computeOnServer(minecraftServer -> serverLightFailure(minecraftServer.overworld()));
            if (failure == null) {
                logLighting(context, server, "server-ready");
                return;
            }
            context.waitTicks(5);
        }
        logLighting(context, server, "server-timeout");
        throw new AssertionError("rotation native server lighting not ready: "
            + server.computeOnServer(minecraftServer -> serverLightFailure(minecraftServer.overworld())));
    }

    private static String serverLightFailure(ServerLevel level) {
        for (BlockPos cell : LIGHT_CELLS) {
            if (!level.getChunkAt(cell).isLightCorrect()) {
                return "unlit chunk at " + cell.toShortString();
            }
            int sky = level.getBrightness(LightLayer.SKY, cell);
            int block = level.getBrightness(LightLayer.BLOCK, cell);
            if (cell.equals(SOURCE_LIGHT_CELL) ? sky != 0 || block <= 0 : sky != 15 || !level.getBlockState(cell).isAir()) {
                return cell.toShortString() + " sky " + sky + " block " + block + " state " + level.getBlockState(cell);
            }
        }
        return null;
    }

    private static void awaitClientLighting(ClientGameTestContext context, TestServerContext server, Phase phase) {
        List<CellLight> expected = server.computeOnServer(minecraftServer -> lighting(minecraftServer.overworld()));
        String failure = null;
        for (int tick = 0; tick < TIMEOUT_TICKS; tick += 5) {
            failure = context.computeOnClient(client -> clientLightFailure(client, expected));
            if (failure == null) {
                logLighting(context, server, phase.label() + "-native-ready");
                return;
            }
            context.waitTicks(5);
        }
        logLighting(context, server, phase.label() + "-native-timeout");
        context.takeScreenshot("portal-rotation-" + phase.label() + "-lighting-failed");
        throw new AssertionError(phase.label() + ": rotation native client lighting not ready: " + failure);
    }

    private static String clientLightFailure(Minecraft client, List<CellLight> expected) {
        for (CellLight light : expected) {
            BlockPos cell = light.cell();
            if (client.level.getChunkSource().getChunk(cell.getX() >> 4, cell.getZ() >> 4, ChunkStatus.FULL, false) == null) {
                return "missing native client chunk at " + cell.toShortString();
            }
            int sky = client.level.getBrightness(LightLayer.SKY, cell);
            int block = client.level.getBrightness(LightLayer.BLOCK, cell);
            if (sky != light.sky() || block != light.block()) {
                return cell.toShortString() + " sky " + sky + "/" + light.sky() + " block " + block + "/" + light.block();
            }
        }
        return null;
    }

    private static List<CellLight> lighting(Level level) {
        List<CellLight> lights = new ArrayList<CellLight>(LIGHT_CELLS.size());
        for (BlockPos cell : LIGHT_CELLS) {
            lights.add(new CellLight(cell, level.getBrightness(LightLayer.SKY, cell), level.getBrightness(LightLayer.BLOCK, cell)));
        }
        return List.copyOf(lights);
    }

    private static void logLighting(ClientGameTestContext context, TestServerContext server, String phase) {
        LOGGER.info("WORMHOLES_ROTATION_SERVER_LIGHT phase {} {}", phase,
            server.computeOnServer(minecraftServer -> "sky_darken " + minecraftServer.overworld().getSkyDarken()
                + " native " + lighting(minecraftServer.overworld())));
        LOGGER.info("WORMHOLES_ROTATION_CLIENT_LIGHT phase {} {}", phase, context.computeOnClient(client -> {
            ClientLevel home = client.level;
            LightmapRenderState lightmap = client.gameRenderer.gameRenderState().lightmapRenderState;
            StringBuilder state = new StringBuilder("home ").append(System.identityHashCode(home))
                .append(" main ").append(System.identityHashCode(ClientWorldLoader.mainLevel()))
                .append(" sky_darken ").append(home.getSkyDarken())
                .append(" light_work ").append(home.getChunkSource().getLightEngine().hasLightWork())
                .append(" sky_factor ").append(lightmap.skyFactor)
                .append(" block_factor ").append(lightmap.blockFactor)
                .append(" brightness ").append(lightmap.brightness)
                .append(" darkness ").append(lightmap.darknessEffectScale)
                .append(" native ").append(lighting(home));
            for (PortalView view : WormholesClient.instance().portalViews().current()) {
                if (view.kind() == PortalView.Kind.ARM) {
                    state.append(" destination ").append(System.identityHashCode(view.destination()))
                        .append(" source_is_home ").append(view.source() == home)
                        .append(" destination_is_home ").append(view.destination() == home)
                        .append(" destination_native ").append(lighting(view.destination()));
                }
            }
            return state.toString();
        }));
    }

    private static UUID install(MinecraftServer server, ServerPlayer player, PortalDefinition definition, UUID target,
                                List<UUID> created) {
        WormholesModRuntime runtime = runtime(server);
        MinecraftPortal source = portal(server, player, definition, created);
        assertTrue(runtime.portals().link(player, source.getId(), target), "rotated source link rejected");
        return source.getId();
    }

    private static void rotate(MinecraftServer server, UUID sourceId, PortalDefinition definition, UUID target) {
        WormholesModRuntime runtime = runtime(server);
        MinecraftPortal source = runtime.portals().get(sourceId);
        assertTrue(source != null, "live rotating source disappeared");
        List<Vec3d> cells = definition.aperture().getBlockPositions();
        source.getBuiltGeometry().setBlocks(cells);
        assertTrue(source.setApertureShape(ShapeDescriptor.FULL), "rotated source geometry rejected");
        source.setFrame(definition.frame());
        runtime.portals().save(source);
        assertTrue(runtime.portals().get(sourceId) == source && sourceId.equals(source.getId()),
            "rotation replaced its live source portal");
        assertTrue(target.equals(source.getDestinationId()), "rotation changed its destination link");
        assertTrue(source.getFrame().equals(definition.frame()), "rotation did not update its source frame");
        assertTrue(source.getOrigin().distance(SOURCE_CENTER) < 1.0E-9D
            && source.getOrigin().distance(definition.origin()) < 1.0E-9D, "rotation moved its source origin");
        assertTrue(source.getBuiltGeometry().getBlockPositions().size() == cells.size()
            && source.getGeometry().getBlockPositions().size() == cells.size(), "rotation changed its aperture cell count");
        for (Vec3d cell : cells) {
            assertTrue(source.getBuiltGeometry().containsBlock(cell.blockX(), cell.blockY(), cell.blockZ())
                && source.getGeometry().containsBlock(cell.blockX(), cell.blockY(), cell.blockZ()),
                "rotation did not synchronize its built and effective aperture cells");
        }
    }

    private static MinecraftPortal portal(MinecraftServer server, ServerPlayer player, PortalDefinition definition, List<UUID> created) {
        List<BlockPos> cells = new ArrayList<BlockPos>();
        for (Vec3d cell : definition.aperture().getBlockPositions()) {
            cells.add(new BlockPos(cell.blockX(), cell.blockY(), cell.blockZ()));
        }
        Frame frame = definition.frame();
        Face normal = frame.getNormal();
        MinecraftPortal portal = runtime(server).portals().create(player.getUUID(), server.overworld(), cells, PortalType.PORTAL,
            new Vec3(normal.x(), normal.y(), normal.z()));
        portal.setFrame(frame);
        portal.setAmbientStyle(AmbientParticleStyle.OFF);
        runtime(server).portals().save(portal);
        created.add(portal.getId());
        return portal;
    }

    private static ViewTarget view(ClientGameTestContext context, TestServerContext server, ServerPlayer player, Phase phase,
                                   UUID sourceId, UUID targetId) {
        Frame frame = phase.definition().frame();
        Face normal = frame.view(phase.camera().front()).getNormal();
        Face right = frame.getRight();
        Face up = frame.getUp();
        Vec3d offset = new Vec3d(normal.x(), normal.y(), normal.z()).multiply(phase.camera().distance())
            .add(new Vec3d(right.x(), right.y(), right.z()).multiply(phase.camera().right()))
            .add(new Vec3d(up.x(), up.y(), up.z()).multiply(phase.camera().up()));
        Vec3d eye = phase.definition().origin().add(offset);
        Angles.Look look = Angles.look(-offset.x(), -offset.y(), -offset.z());
        int positions = TravelTap.positions();
        int accepts = TravelTap.accepts();
        Vec3 feet = server.computeOnServer(minecraftServer -> {
            double height = player.getEyeY() - player.getY();
            Vec3 position = new Vec3(eye.x(), eye.y() - height, eye.z());
            assertTrue(player.teleportTo(player.level(), position.x, position.y, position.z, Set.of(), look.yaw(), look.pitch(), false),
                phase.label() + ": rotation fixture teleport failed");
            player.setDeltaMovement(Vec3.ZERO);
            return position;
        });
        ViewTarget target = new ViewTarget(feet, new Vec3(eye.x(), eye.y(), eye.z()), look, sourceId, targetId, positions, accepts);
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            boolean applied = context.computeOnClient(client -> TravelTap.positions() > positions && TravelTap.accepts() > accepts
                && client.player.position().distanceTo(feet) < POSITION_TOLERANCE
                && lookMatches(client.player.getYRot(), client.player.getXRot(), look));
            if (applied && server.computeOnServer(minecraftServer -> teleportState(player).position() == null)) {
                logView(context, server, player, phase, target, "teleport-applied");
                context.getInput().lookAt(look.yaw(), look.pitch());
                logView(context, server, player, phase, target, "input-applied");
                return target;
            }
            if (tick == 0 || tick % 100 == 0) {
                logView(context, server, player, phase, target, "teleport-wait-" + tick);
            }
            context.waitTicks(1);
        }
        logView(context, server, player, phase, target, "teleport-timeout");
        context.takeScreenshot("portal-rotation-" + phase.label() + "-teleport-failed");
        throw new AssertionError(phase.label() + ": native rotation fixture teleport was not applied");
    }

    private static void awaitView(ClientGameTestContext context, TestServerContext server, ServerPlayer player, Phase phase,
                                  PortalDefinition destination, ViewTarget target) {
        int stable = 0;
        int positions = TravelTap.positions();
        String failure = null;
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            failure = viewFailure(context, server, player, phase, destination, target);
            int currentPositions = TravelTap.positions();
            stable = failure == null && currentPositions == positions ? stable + 1 : 0;
            positions = currentPositions;
            if (stable >= STABLE_VIEW_TICKS) {
                logView(context, server, player, phase, target, "view-ready");
                return;
            }
            if (tick == 0 || tick % 100 == 0) {
                LOGGER.info("WORMHOLES_ROTATION_VIEW_WAIT phase {} tick {} stable {} failure {}", phase.label(), tick, stable, failure);
                logView(context, server, player, phase, target, "view-wait-" + tick);
            }
            context.waitTicks(1);
        }
        logView(context, server, player, phase, target, "view-timeout");
        logLighting(context, server, phase.label() + "-view-timeout");
        context.takeScreenshot("portal-rotation-" + phase.label() + "-view-failed");
        throw new AssertionError(phase.label() + ": rotation view did not remain ready for " + STABLE_VIEW_TICKS + " ticks: " + failure);
    }

    private static String viewFailure(ClientGameTestContext context, TestServerContext server, ServerPlayer player, Phase phase,
                                      PortalDefinition destination, ViewTarget target) {
        String failure = server.computeOnServer(minecraftServer -> {
            if (!player.isNoGravity() || player.getGravity() != 0.0D) {
                return "native server fixture gravity is not disabled";
            }
            if (teleportState(player).position() != null) {
                return "native server teleport acknowledgement remains pending";
            }
            if (player.level() != minecraftServer.overworld() || player.position().distanceTo(target.feet()) >= POSITION_TOLERANCE
                || !lookMatches(player.getYRot(), player.getXRot(), target.look())) {
                return "native server pose differs from fixture target";
            }
            if (!player.getLastClientInput().equals(Input.EMPTY) || player.getDeltaMovement().lengthSqr() > 1.0E-8D) {
                return "native server input or motion is not idle";
            }
            MinecraftPortal source = runtime(minecraftServer).portals().get(target.source());
            return source == null || !target.destination().equals(source.getDestinationId())
                || !source.getFrame().equals(phase.definition().frame()) ? "live server portal route differs from phase" : null;
        });
        return failure == null ? context.computeOnClient(client -> clientViewFailure(client, phase, destination, target)) : failure;
    }

    private static String clientViewFailure(Minecraft client, Phase phase, PortalDefinition destination, ViewTarget target) {
        LocalPlayer player = client.player;
        Camera camera = client.gameRenderer.mainCamera();
        if (client.level == null || player == null || player.level() != client.level || ClientWorldLoader.mainLevel() != client.level
            || client.getCameraEntity() != player || camera.entity() != player || !camera.isInitialized() || camera.isDetached()
            || client.options.getCameraType() != CameraType.FIRST_PERSON) {
            return "native main level or camera ownership differs from fixture";
        }
        if (player.position().distanceTo(target.feet()) >= POSITION_TOLERANCE
            || player.getEyePosition().distanceTo(target.eye()) >= POSITION_TOLERANCE
            || camera.position().distanceTo(target.eye()) >= POSITION_TOLERANCE
            || !lookMatches(player.getYRot(), player.getXRot(), target.look())
            || !lookMatches(camera.yRot(), camera.xRot(), target.look())) {
            return "native client player or camera pose differs from fixture target";
        }
        MouseInput mouse = mouseInput(client.mouseHandler);
        if (!player.isNoGravity() || player.getGravity() != 0.0D) {
            return "native client fixture gravity is not disabled";
        }
        if (!player.input.keyPresses.equals(Input.EMPTY) || player.getDeltaMovement().lengthSqr() > 1.0E-8D
            || mouse.dx() != 0.0D || mouse.dy() != 0.0D) {
            return "native client input or motion is not idle";
        }
        if (WormholesClient.instance().seamlessTravel().pending() || WormholesClient.instance().seamlessTravel().view().active()
            || WormholesClient.instance().seamlessTravel().crossing() != null || ClientWorldLoader.switching()
            || PortalStencilRenderer.instance().camera() != null) {
            return "portal travel or virtual camera remains active outside rendering";
        }
        Vec3d forward = Angles.direction(target.look().yaw(), target.look().pitch());
        if (new Vec3(camera.forwardVector().x(), camera.forwardVector().y(), camera.forwardVector().z())
            .distanceTo(new Vec3(forward.x(), forward.y(), forward.z())) > 0.005D) {
            return "native camera orientation differs from player angles";
        }
        CameraRenderState rendered = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (!rendered.initialized || rendered.pos.distanceTo(target.eye()) >= POSITION_TOLERANCE
            || !lookMatches(rendered.yRot, rendered.xRot, target.look())) {
            return "native extracted main camera pose differs from fixture target";
        }
        boolean armed = false;
        Similarity expected = phase.definition().toward(destination, phase.camera().front());
        for (TravelMessage.TravelBegin arm : WormholesClient.instance().seamlessTravel().arms()) {
            if (arm.sourcePortal().equals(target.source()) && arm.sourceGeometry().frame().equals(phase.definition().frame())
                && arm.sourceToDestination().rigid().normalized().equals(expected.rigid().normalized())) {
                armed = true;
                break;
            }
        }
        if (!armed || !ready(phase.definition(), destination, phase.camera().front())) {
            return "expected live portal route is not ready";
        }
        return null;
    }

    private static boolean lookMatches(float yaw, float pitch, Angles.Look expected) {
        return Math.abs(Mth.wrapDegrees(yaw - expected.yaw())) <= ANGLE_TOLERANCE
            && Math.abs(pitch - expected.pitch()) <= ANGLE_TOLERANCE;
    }

    private static void logView(ClientGameTestContext context, TestServerContext server, ServerPlayer player, Phase phase,
                                ViewTarget target, String stage) {
        LOGGER.info("WORMHOLES_ROTATION_SERVER_VIEW phase {} stage {} expected {} actual {}", phase.label(), stage, target,
            server.computeOnServer(minecraftServer -> "dimension " + player.level().dimension().identifier()
                + " feet " + player.position() + " eye " + player.getEyePosition() + " yaw " + player.getYRot()
                + " pitch " + player.getXRot() + " old_yaw " + player.yRotO + " old_pitch " + player.xRotO
                + " velocity " + player.getDeltaMovement() + " input " + player.getLastClientInput()
                + " abilities " + player.getAbilities().pack() + " no_gravity " + player.isNoGravity()
                + " gravity " + player.getGravity() + " on_ground " + player.onGround()
                + " teleport " + teleportState(player)));
        LOGGER.info("WORMHOLES_ROTATION_CLIENT_VIEW phase {} stage {} {}", phase.label(), stage,
            context.computeOnClient(client -> clientViewState(client, phase)));
    }

    private static String clientViewState(Minecraft client, Phase phase) {
        LocalPlayer player = client.player;
        Camera camera = client.gameRenderer.mainCamera();
        Camera virtual = PortalStencilRenderer.instance().camera();
        CameraRenderState rendered = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        StringBuilder state = new StringBuilder("home ").append(System.identityHashCode(client.level))
            .append(" main ").append(System.identityHashCode(ClientWorldLoader.mainLevel()))
            .append(" player ").append(System.identityHashCode(player))
            .append(" player_level ").append(System.identityHashCode(player.level()))
            .append(" camera_type ").append(client.options.getCameraType())
            .append(" paused ").append(client.isPaused()).append(" screen ").append(client.gui.screen())
            .append(" feet ").append(player.position()).append(" eye ").append(player.getEyePosition())
            .append(" yaw ").append(player.getYRot()).append(" pitch ").append(player.getXRot())
            .append(" old_feet ").append(new Vec3(player.xo, player.yo, player.zo))
            .append(" old_yaw ").append(player.yRotO).append(" old_pitch ").append(player.xRotO)
            .append(" head_yaw ").append(player.yHeadRot).append(" body_yaw ").append(player.yBodyRot)
            .append(" input ").append(player.input.keyPresses).append(" move_vector ").append(player.input.getMoveVector())
            .append(" velocity ").append(player.getDeltaMovement())
            .append(" abilities ").append(player.getAbilities().pack()).append(" no_gravity ").append(player.isNoGravity())
            .append(" gravity ").append(player.getGravity()).append(" on_ground ").append(player.onGround())
            .append(" mouse ").append(mouseInput(client.mouseHandler))
            .append(" mouse_grabbed ").append(client.mouseHandler.isMouseGrabbed())
            .append(" smooth_camera ").append(client.options.smoothCamera)
            .append(" camera ").append(cameraState(camera)).append(" virtual ").append(cameraState(virtual))
            .append(" rendered_eye ").append(rendered.pos).append(" rendered_yaw ").append(rendered.yRot)
            .append(" rendered_pitch ").append(rendered.xRot).append(" rendered_orientation ").append(rendered.orientation)
            .append(" rendered_view ").append(matrixState(rendered.viewRotationMatrix))
            .append(" rendered_projection ").append(matrixState(rendered.projectionMatrix))
            .append(" positions ").append(TravelTap.positions()).append(" accepts ").append(TravelTap.accepts())
            .append(" packet_events ").append(TravelTap.events())
            .append(" pending ").append(WormholesClient.instance().seamlessTravel().pending())
            .append(" crossing ").append(WormholesClient.instance().seamlessTravel().crossing())
            .append(" crossing_view ").append(WormholesClient.instance().seamlessTravel().view().active())
            .append(" switching ").append(ClientWorldLoader.switching())
            .append(" stencil ").append(PortalStencilRenderer.instance().debugLine());
        for (PortalView view : WormholesClient.instance().portalViews().current()) {
            state.append(" route ").append(view.key()).append(" kind ").append(view.kind())
                .append(" source ").append(System.identityHashCode(view.source()))
                .append(" destination ").append(System.identityHashCode(view.destination()))
                .append(" transform ").append(view.toDestination())
                .append(" claimed ").append(PortalStencilRenderer.instance().claims(view.surface().geometry()));
        }
        for (PortalEntityRenderTap.Sample sample : PortalEntityRenderTap.samples()) {
            state.append(" render_layer ").append(sample.frame()).append(" depth ").append(sample.depth())
                .append(" path ").append(sample.path()).append(" mapped_eye ").append(sample.eye())
                .append(" outer_eye ").append(sample.screen().eye()).append(" outer_matrix ").append(matrixState(sample.screen().viewProjection()));
        }
        Similarity display = phase.definition().toward(PortalDefinition.builder().facing(Face.N)
            .center(DESTINATION_CENTER).size(7, 7).build(), phase.camera().front()).inverse();
        for (Marker marker : MARKERS) {
            state.append(" projected ").append(marker.color()).append(' ');
            Vec3d point = display.point(new Vec3d(DESTINATION_CENTER.x() + marker.right(), DESTINATION_CENTER.y() + marker.up(),
                phase.camera().front() ? 100.0D : 93.0D));
            int[] pixel = FramebufferProbe.screen(client, new Vec3(point.x(), point.y(), point.z()));
            state.append(pixel == null ? "outside" : pixel[0] + "," + pixel[1]);
        }
        return state.toString();
    }

    private static Field nativeField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException failure) {
            throw new IllegalStateException("Missing native rotation diagnostic field " + owner.getName() + "." + name, failure);
        }
    }

    private static MouseInput mouseInput(MouseHandler mouse) {
        try {
            return new MouseInput(mouse.xpos(), mouse.ypos(), MOUSE_DX.getDouble(mouse), MOUSE_DY.getDouble(mouse));
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Unable to read native rotation mouse input", failure);
        }
    }

    private static TeleportState teleportState(ServerPlayer player) {
        try {
            return new TeleportState((Vec3) TELEPORT_POSITION.get(player.connection), TELEPORT_ID.getInt(player.connection),
                TELEPORT_TICK.getInt(player.connection));
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Unable to read native rotation teleport acknowledgement", failure);
        }
    }

    private static String cameraState(Camera camera) {
        return camera == null ? "none" : camera.getClass().getName() + " initialized " + camera.isInitialized() + " eye " + camera.position() + " yaw " + camera.yRot() + " pitch " + camera.xRot()
            + " entity " + System.identityHashCode(camera.entity()) + " detached " + camera.isDetached()
            + " orientation " + camera.rotation() + " forward " + camera.forwardVector() + " up " + camera.upVector()
            + " view_projection " + matrixState(camera.getViewRotationProjectionMatrix(new Matrix4f()));
    }

    private static String matrixState(Matrix4f matrix) {
        return Arrays.toString(matrix.get(new float[16]));
    }

    private static boolean ready(PortalDefinition source, PortalDefinition destination, boolean front) {
        Similarity expected = source.toward(destination, front);
        for (PortalView view : WormholesClient.instance().portalViews().current()) {
            if (view.kind() == PortalView.Kind.ARM && view.surface().geometry().frame().equals(source.frame())
                && view.surface().planePoint().distance(source.origin()) < 0.01D
                && view.toDestination().rigid().normalized().equals(expected.rigid().normalized())
                && PortalStencilRenderer.instance().claims(view.surface().geometry())) {
                return true;
            }
        }
        return false;
    }

    private static void assertPixels(ClientGameTestContext context, TestServerContext server, ServerPlayer player, Phase phase,
                                     PortalDefinition destination, ViewTarget target) {
        String failure = null;
        for (int attempt = 0; attempt < PROBE_ATTEMPTS; attempt++) {
            String prerequisite = viewFailure(context, server, player, phase, destination, target);
            logView(context, server, player, phase, target, "capture-" + attempt);
            if (prerequisite != null) {
                context.takeScreenshot("portal-rotation-" + phase.label() + "-view-lost");
                throw new AssertionError(phase.label() + ": rotation view changed before capture: " + prerequisite);
            }
            int positions = TravelTap.positions();
            AtomicReference<FramebufferProbe> captured = new AtomicReference<FramebufferProbe>();
            AtomicReference<String> requestFailure = new AtomicReference<String>();
            int captureAttempt = attempt;
            context.runOnClient(client -> {
                LOGGER.info("WORMHOLES_ROTATION_CAPTURE_STATE phase {} attempt {} {}", phase.label(), captureAttempt,
                    clientViewState(client, phase));
                requestFailure.set(clientViewFailure(client, phase, destination, target));
                if (requestFailure.get() == null) {
                    FramebufferProbe.capture(client, captured::set);
                }
            });
            if (requestFailure.get() != null) {
                context.takeScreenshot("portal-rotation-" + phase.label() + "-request-view-lost");
                throw new AssertionError(phase.label() + ": native view changed at framebuffer request: " + requestFailure.get());
            }
            context.waitFor(client -> captured.get() != null, CAPTURE_TICKS);
            prerequisite = viewFailure(context, server, player, phase, destination, target);
            if (prerequisite != null || TravelTap.positions() != positions) {
                logView(context, server, player, phase, target, "capture-changed-" + attempt);
                context.takeScreenshot("portal-rotation-" + phase.label() + "-capture-view-lost");
                throw new AssertionError(phase.label() + ": rotation view changed during capture: " + prerequisite
                    + " position packets " + positions + "/" + TravelTap.positions());
            }
            failure = context.computeOnClient(client -> pixelFailure(client, captured.get(), phase, destination));
            if (failure == null) {
                LOGGER.info("WORMHOLES_ROTATION_PHASE_PASS phase {} attempt {} {}", phase.label(), attempt,
                    context.computeOnClient(client -> pixelSamples(client, captured.get(), phase, destination)));
                return;
            }
            LOGGER.info("[portal-rotation {}] sample {}: {}", phase.label(), attempt, failure);
            context.waitTicks(8);
        }
        logView(context, server, player, phase, target, "pixel-failed");
        logLighting(context, server, phase.label() + "-pixel-failed");
        context.takeScreenshot("portal-rotation-" + phase.label() + "-failed");
        throw new AssertionError(phase.label() + ": " + failure);
    }

    private static String pixelFailure(Minecraft client, FramebufferProbe probe, Phase phase, PortalDefinition destination) {
        Similarity display = phase.definition().toward(destination, phase.camera().front()).inverse();
        for (Marker marker : MARKERS) {
            Vec3d point = display.point(new Vec3d(DESTINATION_CENTER.x() + marker.right(), DESTINATION_CENTER.y() + marker.up(),
                phase.camera().front() ? 100.0D : 93.0D));
            int[] pixel = FramebufferProbe.screen(client, new Vec3(point.x(), point.y(), point.z()));
            if (pixel == null) {
                return marker.color() + " landmark is outside the framebuffer";
            }
            int color = probe.average(pixel[0], pixel[1], 2);
            if (!matches(color, marker.color())) {
                return marker.color() + " landmark reads #" + Integer.toHexString(color & 0xFFFFFF) + " at " + pixel[0] + "," + pixel[1];
            }
        }
        if (phase.camera().clipping()) {
            Face normal = phase.definition().frame().getNormal();
            Vec3d tangent = normal.isVertical() ? new Vec3d(1, 0, 0) : new Vec3d(-normal.z(), 0, normal.x());
            Vec3d point = phase.definition().origin().add(tangent.multiply(4.0D));
            int[] pixel = FramebufferProbe.screen(client, new Vec3(point.x(), point.y(), point.z()));
            if (pixel == null) {
                return "outside-aperture clipping sample is outside the framebuffer";
            }
            int color = probe.average(pixel[0], pixel[1], 2);
            int low = Math.min(red(color), Math.min(green(color), blue(color)));
            int high = Math.max(red(color), Math.max(green(color), blue(color)));
            if (low < 65 || high > low * 1.5D) {
                return "outside the aperture reads #" + Integer.toHexString(color & 0xFFFFFF) + " instead of the source sea-lantern backdrop";
            }
        }
        return null;
    }

    private static String pixelSamples(Minecraft client, FramebufferProbe probe, Phase phase, PortalDefinition destination) {
        Similarity display = phase.definition().toward(destination, phase.camera().front()).inverse();
        StringBuilder samples = new StringBuilder();
        for (Marker marker : MARKERS) {
            Vec3d point = display.point(new Vec3d(DESTINATION_CENTER.x() + marker.right(), DESTINATION_CENTER.y() + marker.up(),
                phase.camera().front() ? 100.0D : 93.0D));
            int[] pixel = FramebufferProbe.screen(client, new Vec3(point.x(), point.y(), point.z()));
            samples.append(marker.color()).append(" #").append(Integer.toHexString(probe.average(pixel[0], pixel[1], 2) & 0xFFFFFF)).append(' ');
        }
        return samples.toString();
    }

    private static boolean matches(int color, DyeColor expected) {
        int red = red(color);
        int green = green(color);
        int blue = blue(color);
        return switch (expected) {
            case RED -> red > 35 && red > green * COLOR_DOMINANCE && red > blue * COLOR_DOMINANCE;
            case BLUE -> blue > 35 && blue > red * COLOR_DOMINANCE && blue > green * COLOR_DOMINANCE;
            case LIME -> green > 35 && green > red * COLOR_DOMINANCE && green > blue * COLOR_DOMINANCE;
            case YELLOW -> red > 35 && green > 35 && red > blue * COLOR_DOMINANCE && green > blue * COLOR_DOMINANCE;
            default -> throw new IllegalArgumentException("Unsupported landmark color " + expected);
        };
    }

    private static int red(int color) {
        return color >> 16 & 255;
    }

    private static int green(int color) {
        return color >> 8 & 255;
    }

    private static int blue(int color) {
        return color & 255;
    }

    private static void fill(ServerLevel level, BlockPos min, int width, int height, int depth, BlockState state) {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    level.setBlockAndUpdate(min.offset(x, y, z), state);
                }
            }
        }
    }

    private record MouseInput(double x, double y, double dx, double dy) {
    }

    private record TeleportState(Vec3 position, int id, int tick) {
    }

    private record ViewTarget(Vec3 feet, Vec3 eye, Angles.Look look, UUID source, UUID destination, int positions, int accepts) {
    }

    private record CellLight(BlockPos cell, int sky, int block) {
        @Override
        public String toString() {
            return cell.toShortString() + ":" + sky + "/" + block;
        }
    }

    private record Phase(String label, PortalDefinition definition, ViewPose camera) {
    }

    private record ViewPose(boolean front, double distance, double right, double up, boolean clipping) {
    }

    private record Marker(double right, double up, DyeColor color) {
    }

    private record ClientOptions(boolean hidden, CameraType camera, boolean recursion) {
    }

    private record MotionState(Abilities.Packed abilities, boolean noGravity) {
    }
}
