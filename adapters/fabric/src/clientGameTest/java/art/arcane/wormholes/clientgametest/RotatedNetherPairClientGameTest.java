package art.arcane.wormholes.clientgametest;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftScaleAccess;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.ScaleRuleSettings;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class RotatedNetherPairClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final BlockPos SOURCE = SeamlessWalkThrough.NETHER_FRAME;
    private static final int TIMEOUT_TICKS = 600;
    private static final int INTEGRITY_TICKS = 90;
    private static final double SCALE = 0.75D;
    private static final ScaleRule RULE = ScaleRule.ratio(SCALE, SCALE);
    private static final ShapeDescriptor CIRCLE = ShapeDescriptor.parse("circle");

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            FabricSeamlessClient client = new FabricSeamlessClient(context, world.getConnection());
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            client.runOnClient(minecraft -> {
                hidden(minecraft);
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                minecraft.options.pauseOnLostFocus = false;
            });
            world.getServer().runOnServer(server -> {
                ServerPlayer player = world.getConnection().getServerPlayer();
                player.setGameMode(GameType.SURVIVAL);
                SeamlessWalkThrough.light(server, player, new SeamlessWalkThrough.NetherFrameFixture(SOURCE, 3));
            });
            awaitIgnition(client, world);
            Pair pair = world.getServer().computeOnServer(server -> capture(server));
            try {
                edit(client, world, pair);
                roundTrip(context, client, world, pair);
                awaitIntegrity(client, world, pair, "after round trip");
                Pair normalChanged = new Pair(pair.source(), pair.destination(), pair.rotated().withNormal(Face.U), pair.gravity(), pair.leases());
                world.getServer().runOnServer(server -> {
                    assertTrue(runtime(server).portals().update(world.getConnection().getServerPlayer(), pair.source().id(),
                        portal -> portal.setFrame(normalChanged.rotated())), "Actual source frame normal change rejected");
                    assertSynced(server, normalChanged, RULE);
                });
                awaitIntegrity(client, world, normalChanged, "actual source normal changed to up");
                world.getServer().runOnServer(server -> {
                    assertPair(server, normalChanged);
                    assertTrue(server.overworld().destroyBlock(pair.source().border().getFirst(), false),
                        "Could not physically break the original source obsidian frame");
                });
                awaitRemoval(client, world, pair);
                context.takeScreenshot("rotated-nether-pair-broken");
                LOGGER.info("WORMHOLES_ROTATED_NETHER_PAIR_PASS source {} destination {} clockwise90 gravity {} scale {} loadedTicks {}",
                    pair.source().id(), pair.destination().id(), pair.gravity(), SCALE, INTEGRITY_TICKS);
            } finally {
                client.releaseForward();
                world.getServer().runOnServer(server -> cleanup(server, world.getConnection().getServerPlayer(), pair));
            }
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
            context.restoreDefaultGameOptions();
        }
    }

    private static void awaitIgnition(FabricSeamlessClient client, TestSingleplayerContext world) {
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (world.getServer().computeOnServer(server -> SeamlessWalkThrough.lit(server, SOURCE))) {
                return;
            }
            client.waitTicks(1);
        }
        throw new AssertionError("Real flint-and-steel ignition never produced a managed Nether pair");
    }

    private static Pair capture(MinecraftServer server) {
        MinecraftPortalRegistry registry = runtime(server).portals();
        MinecraftPortal source = Objects.requireNonNull(registry.at(server.overworld(), SOURCE), "Missing converted source");
        MinecraftPortal destination = Objects.requireNonNull(registry.get(source.getDestinationId()), "Missing generated destination");
        assertTrue(source.isManaged() && destination.isManaged(), "Ignition did not create managed endpoints");
        assertTrue(source.getId().equals(destination.getDestinationId()) && source.getId().equals(destination.getCounterpartId())
            && destination.getId().equals(source.getCounterpartId()), "Generated Nether pair is not reciprocal");
        Endpoint departure = endpoint(server, source);
        Endpoint arrival = endpoint(server, destination);
        assertTrue(Level.NETHER.identifier().toString().equals(arrival.world()), "Generated target is not in the Nether");
        Frame rotated = source.getFrame().rotateClockwise();
        GravityFrame gravity = GravityFrame.WORLD.transformed(AxisPermutation.between(rotated, destination.getFrame()));
        assertTrue(gravity.down() != Face.D, "Clockwise source rotation did not produce sideways destination gravity");
        List<ChunkLease> leases = new ArrayList<>();
        for (Endpoint endpoint : List.of(departure, arrival)) {
            ServerLevel level = Objects.requireNonNull(registry.resolveLevel(registry.get(endpoint.id())));
            UUID world = UUID.nameUUIDFromBytes(endpoint.world().getBytes(StandardCharsets.UTF_8));
            Set<BlockPos> chunks = new LinkedHashSet<>();
            for (BlockPos border : endpoint.border()) {
                chunks.add(new BlockPos(border.getX() >> 4, 0, border.getZ() >> 4));
            }
            for (BlockPos chunk : chunks) {
                leases.add(runtime(server).leases().retain(level, world, chunk.getX(), chunk.getZ()));
            }
        }
        return new Pair(departure, arrival, rotated, gravity, List.copyOf(leases));
    }

    private static Endpoint endpoint(MinecraftServer server, MinecraftPortal portal) {
        ServerLevel level = Objects.requireNonNull(runtime(server).portals().resolveLevel(portal));
        List<BlockPos> cells = new ArrayList<>();
        for (Vec3d cell : portal.getBuiltGeometry().getBlockPositions()) {
            cells.add(BlockPos.containing(cell.x(), cell.y(), cell.z()));
        }
        assertTrue(cells.size() == 9, "Expected genuine three-by-three Nether aperture: " + cells);
        boolean alongX = cells.stream().mapToInt(BlockPos::getX).max().orElseThrow()
            > cells.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        Direction[] directions = alongX ? new Direction[] {Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST}
            : new Direction[] {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH};
        Set<BlockPos> border = new LinkedHashSet<>();
        for (BlockPos cell : cells) {
            for (Direction direction : directions) {
                BlockPos adjacent = cell.relative(direction);
                if (!cells.contains(adjacent)) {
                    assertTrue(level.getBlockState(adjacent).is(Blocks.OBSIDIAN), "Original physical frame missing at " + adjacent);
                    border.add(adjacent);
                }
            }
        }
        return new Endpoint(portal.getId(), portal.getWorldKey(), portal.getFrame(), portal.getOrigin(), List.copyOf(cells), List.copyOf(border));
    }

    private static void edit(FabricSeamlessClient client, TestSingleplayerContext world, Pair pair) {
        world.getServer().runOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            MinecraftPortalRegistry registry = runtime(server).portals();
            assertTrue(registry.update(player, pair.source().id(), portal -> portal.setSettingsSyncEnabled(false)), "Source sync disable rejected");
            assertTrue(registry.update(player, pair.destination().id(), portal -> portal.setSettingsSyncEnabled(false)), "Target sync disable rejected");
            assertTrue(!registry.get(pair.source().id()).isSettingsSyncEnabled() && !registry.get(pair.destination().id()).isSettingsSyncEnabled(),
                "Fixture did not start with Settings sync disabled on both ends");
            assertTrue(registry.update(player, pair.source().id(), portal -> portal.setFrame(pair.rotated())), "Clockwise90 rotation rejected");
        });
        awaitIntegrity(client, world, pair, "clockwise90 with sync disabled");
        world.getServer().runOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            MinecraftPortalRegistry registry = runtime(server).portals();
            assertTrue(!registry.get(pair.destination().id()).isGravityRotationEnabled(), "Target gravity was enabled before source edit");
            assertTrue(registry.update(player, pair.source().id(), portal -> portal.setGravityRotationEnabled(true)), "Source gravity enable rejected");
            assertSynced(server, pair, ScaleRuleSettings.DEFAULT);
            assertTrue(registry.update(player, pair.source().id(), portal -> portal.setScaleRule(RULE)), "Source scale edit rejected");
            assertSynced(server, pair, RULE);
            assertTrue(registry.update(player, pair.source().id(), portal -> portal.setName("Rotated Nether source")), "Ordinary rename edit rejected");
        });
        awaitIntegrity(client, world, pair, "gravity, scale and ordinary edit");
        world.getServer().runOnServer(server -> {
            MinecraftPortalRegistry registry = runtime(server).portals();
            assertTrue(registry.update(world.getConnection().getServerPlayer(), pair.source().id(), portal -> {
                assertTrue(portal.setApertureShape(CIRCLE), "Circle aperture mask rejected");
            }), "Aperture update rejected");
            assertTrue(registry.get(pair.destination().id()).getApertureShape().isFull(), "Settings sync copied the source aperture mask");
        });
        awaitIntegrity(client, world, pair, "circle aperture mask");
        prepare(client, world, pair);
        awaitNative(client, world, pair.source().id(), CIRCLE);
        world.getServer().runOnServer(server -> {
            assertTrue(runtime(server).portals().update(world.getConnection().getServerPlayer(), pair.source().id(), portal -> {
                assertTrue(portal.setApertureShape(ShapeDescriptor.FULL), "Full aperture restore rejected");
                portal.setOrientation(OrientationPolicy.FRAME);
            }), "Full aperture update rejected");
            assertSynced(server, pair, RULE);
        });
        awaitIntegrity(client, world, pair, "full aperture restored");
    }

    private static void assertSynced(MinecraftServer server, Pair pair, ScaleRule rule) {
        for (Endpoint endpoint : List.of(pair.source(), pair.destination())) {
            MinecraftPortal portal = Objects.requireNonNull(runtime(server).portals().get(endpoint.id()));
            assertTrue(portal.isSettingsSyncEnabled(), "Directional gravity did not automatically enable Settings sync at " + endpoint.id());
            assertTrue(portal.isGravityRotationEnabled(), "Source gravity edit did not replicate to " + endpoint.id());
            assertTrue(rule.equals(portal.getScaleRule()), "Source scale edit did not replicate to " + endpoint.id() + ": " + portal.getScaleRule());
        }
    }

    private static void assertPair(MinecraftServer server, Pair pair) {
        MinecraftPortalRegistry registry = runtime(server).portals();
        MinecraftPortal source = Objects.requireNonNull(registry.get(pair.source().id()), "Edited source was removed");
        MinecraftPortal destination = Objects.requireNonNull(registry.get(pair.destination().id()), "Edited reciprocal destination was removed");
        assertTrue(source.getDestinationId().equals(destination.getId()) && destination.getDestinationId().equals(source.getId())
            && source.getCounterpartId().equals(destination.getId()) && destination.getCounterpartId().equals(source.getId()),
            "Edit replaced or detached the original reciprocal pair");
        assertTrue(source.getFrame().equals(pair.rotated()) && destination.getFrame().equals(pair.destination().frame()),
            "Source clockwise90 rotation was lost or copied to the physical target frame");
        for (Endpoint endpoint : List.of(pair.source(), pair.destination())) {
            MinecraftPortal portal = registry.get(endpoint.id());
            ServerLevel level = Objects.requireNonNull(registry.resolveLevel(portal));
            assertTrue(portal.getBuiltGeometry().getBlockPositions().size() == endpoint.cells().size(), "Edit changed the built physical aperture");
            for (BlockPos cell : endpoint.cells()) {
                assertTrue(level.hasChunk(cell.getX() >> 4, cell.getZ() >> 4), "Physical aperture chunk unloaded during integrity check");
                assertTrue(portal.getBuiltGeometry().containsBlock(cell.getX(), cell.getY(), cell.getZ()), "Edit changed a built aperture cell: " + cell);
            }
            for (BlockPos border : endpoint.border()) {
                assertTrue(level.hasChunk(border.getX() >> 4, border.getZ() >> 4) && level.getBlockState(border).is(Blocks.OBSIDIAN),
                    "Edit removed or unloaded original obsidian at " + endpoint.world() + " " + border);
            }
        }
    }

    private static void awaitIntegrity(FabricSeamlessClient client, TestSingleplayerContext world, Pair pair, String stage) {
        long[] start = world.getServer().computeOnServer(server -> {
            assertPair(server, pair);
            return new long[] {server.overworld().getGameTime(), Objects.requireNonNull(server.getLevel(Level.NETHER)).getGameTime()};
        });
        int frames = client.computeOnClient(minecraft -> TravelTap.frameIndex());
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            long elapsed = world.getServer().computeOnServer(server -> {
                assertPair(server, pair);
                return Math.min(server.overworld().getGameTime() - start[0],
                    Objects.requireNonNull(server.getLevel(Level.NETHER)).getGameTime() - start[1]);
            });
            client.runOnClient(RotatedNetherPairClientGameTest::hidden);
            if (elapsed >= INTEGRITY_TICKS) {
                assertTrue(client.computeOnClient(minecraft -> TravelTap.frameIndex()) > frames,
                    "Hidden GPU frames stopped advancing during " + stage);
                LOGGER.info("WORMHOLES_ROTATED_NETHER_INTEGRITY stage {} loadedServerTicks {}", stage, elapsed);
                return;
            }
            client.waitTicks(1);
        }
        throw new AssertionError("Loaded server ticks did not advance for " + stage);
    }

    private static void prepare(FabricSeamlessClient client, TestSingleplayerContext world, Pair pair) {
        Vec3 origin = point(pair.source().origin());
        Vec3 normal = point(pair.source().frame().getNormal().toVector());
        Vec3 feet = origin.add(normal.scale(4.0D)).add(0.0D, -1.0D, 0.0D);
        float yaw = (float) Math.toDegrees(Math.atan2(normal.x, -normal.z));
        world.getServer().runOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.teleportTo(server.overworld(), feet.x, feet.y, feet.z, Set.of(), yaw, 0.0F, false);
            player.setDeltaMovement(Vec3.ZERO);
        });
        client.waitFor(minecraft -> minecraft.player != null && minecraft.player.position().distanceTo(feet) < 0.05D
            && minecraft.player.getAbilities().flying, TIMEOUT_TICKS);
        client.lookAt(yaw, 0.0F);
        client.waitForChunksRender();
    }

    private static void awaitNative(FabricSeamlessClient client, TestSingleplayerContext world, UUID source, ShapeDescriptor shape) {
        Vec3 eye = client.computeOnClient(minecraft -> minecraft.player.getEyePosition());
        Route route = world.getServer().computeOnServer(server -> {
            MinecraftPortal departure = Objects.requireNonNull(runtime(server).portals().get(source));
            MinecraftPortal destination = Objects.requireNonNull(runtime(server).portals().get(departure.getDestinationId()));
            boolean front = eye.subtract(point(departure.getOrigin())).dot(point(departure.getFrame().getNormal().toVector())) > 0.0D;
            return new Route(source, departure.getWorldKey(), destination.getWorldKey(), departure.getFrame(),
                MinecraftPortalRegistry.towardDestination(departure, destination, front),
                endpoint(server, destination).border().getFirst(), front);
        });
        try {
            client.waitFor(minecraft -> nativeReady(minecraft, route, shape), TIMEOUT_TICKS);
        } catch (AssertionError failure) {
            String state = client.computeOnClient(minecraft -> "position=" + minecraft.player.position()
                + ", gravity=" + MinecraftGravity.frame(minecraft.player) + ", scale=" + minecraft.player.getScale()
                + ", arms=" + WormholesClient.instance().seamlessTravel().arms()
                + ", unprepared=" + WormholesClient.instance().seamlessTravel().unprepared()
                + ", streams=" + WormholesClient.instance().session().portals().size()
                + ", nativeViews=" + WormholesClient.instance().portalViews().current().size());
            throw new AssertionError("Edited Nether native route did not become ready: " + route + ", shape=" + shape + ", " + state, failure);
        }
    }

    private static boolean nativeReady(Minecraft minecraft, Route route, ShapeDescriptor shape) {
        hidden(minecraft);
        WormholesClient mod = WormholesClient.instance();
        if (mod.seamlessTravel().pending() || mod.seamlessTravel().unprepared() != null) {
            return false;
        }
        TravelMessage.TravelBegin prepared = null;
        for (TravelMessage.TravelBegin arm : mod.seamlessTravel().arms()) {
            if (arm.sourcePortal().equals(route.source()) && arm.sourceGeometry().frontSide() == route.front()) {
                prepared = arm;
                break;
            }
        }
        if (prepared == null || !prepared.resident() || !prepared.sourceGeometry().frame().equals(route.frame())
            || !prepared.sourceGeometry().shape().equals(shape) || !prepared.rules().rotateGravity() || !prepared.rules().scale().equals(RULE)
            || !prepared.world().dimension().equals(route.destinationWorld())
            || !prepared.destinationToSource().normalized().equals(route.toward().inverse().rigid().normalized())
            || Math.abs(prepared.scale() - route.toward().scale()) > 1.0E-6D) {
            return false;
        }
        if (mod.seamlessTravel().residents().level(prepared.levelHandle()) == null
            || !mod.seamlessTravel().residents().level(prepared.levelHandle()).getBlockState(route.marker()).is(Blocks.OBSIDIAN)) {
            return false;
        }
        boolean streamReady = false;
        for (ClientPortal stream : mod.session().portals().values()) {
            if (stream.nested() || !sameOpening(stream, prepared)
                || !route.sourceWorld().equals(mod.session().rootSourceWorld(stream.portalKey()))) {
                continue;
            }
            EnvironmentState environment = mod.session().environment(stream.portalKey());
            if (environment != null && route.destinationWorld().equals(environment.world().dimensionKey())
                && environment.transform().normalized().equals(route.toward().inverse().rigid().normalized())
                && Math.abs(environment.scale() - 1.0D / route.toward().scale()) < 1.0E-6D) {
                streamReady = true;
                break;
            }
        }
        if (!streamReady) {
            return false;
        }
        for (PortalView view : mod.portalViews().current()) {
            if (view.kind() == PortalView.Kind.ARM && view.surface().geometry().equals(prepared.sourceGeometry())
                && view.toDestination().rigid().normalized().equals(route.toward().rigid().normalized())
                && Math.abs(view.toDestination().scale() - route.toward().scale()) < 1.0E-6D
                && view.destination().dimension().identifier().toString().equals(route.destinationWorld())
                && PortalStencilRenderer.instance().claims(view.surface().geometry())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameOpening(ClientPortal stream, TravelMessage.TravelBegin arm) {
        return stream.geometry().frame().equals(arm.sourceGeometry().frame())
            && stream.geometry().frontSide() == arm.sourceGeometry().frontSide()
            && stream.geometry().originX() == arm.sourceGeometry().originX()
            && stream.geometry().originY() == arm.sourceGeometry().originY()
            && stream.geometry().originZ() == arm.sourceGeometry().originZ()
            && stream.geometry().apertureWidth() == arm.sourceGeometry().apertureWidth()
            && stream.geometry().apertureHeight() == arm.sourceGeometry().apertureHeight()
            && stream.geometry().shape().equals(arm.sourceGeometry().shape())
            && Arrays.equals(stream.geometry().apertureMask(), arm.sourceGeometry().apertureMask());
    }

    private static void roundTrip(ClientGameTestContext context, FabricSeamlessClient client, TestSingleplayerContext world, Pair pair) {
        awaitNative(client, world, pair.source().id(), ShapeDescriptor.FULL);
        context.takeScreenshot("rotated-nether-pair-source-ready");
        client.runOnClient(minecraft -> TravelTap.reset());
        int player = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player));
        int resets = client.computeOnClient(minecraft -> WormholesClient.instance().session().resets());
        cross(client, world, pair.destination(), pair.gravity());
        context.takeScreenshot("rotated-nether-pair-sideways-arrival");
        client.lookAt(client.computeOnClient(minecraft -> minecraft.player.getYRot() + 180.0F), 0.0F);
        awaitNative(client, world, pair.destination().id(), ShapeDescriptor.FULL);
        cross(client, world, pair.source(), GravityFrame.WORLD);
        context.takeScreenshot("rotated-nether-pair-return");
        client.runOnClient(minecraft -> {
            hidden(minecraft);
            assertTrue(System.identityHashCode(minecraft.player) == player, "Physical Nether round trip replaced the local player");
            assertTrue(!TravelTap.frames().isEmpty(), "Physical Nether round trip did not render GPU frames");
            assertTrue(TravelTap.respawns() == 0 && TravelTap.positions() == 0 && TravelTap.accepts() == 0,
                "Physical Nether round trip used vanilla teleport packets: " + TravelTap.events());
            assertTrue(!TravelTap.loadingScreenShown() && !TravelTap.clientUnloaded(), "Physical Nether round trip showed vanilla loading transition");
            assertTrue(WormholesClient.instance().session().resets() == resets && WormholesClient.instance().session().protocolFailures() == 0,
                "Updated Nether route reset or failed the native stream protocol");
        });
    }

    private static void cross(FabricSeamlessClient client, TestSingleplayerContext world, Endpoint destination, GravityFrame gravity) {
        boolean crossed = false;
        client.holdForward();
        try (TickStepper stepper = client.lockstep()) {
            for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
                crossed = stepper.step(minecraft -> {
                    hidden(minecraft);
                    if (!minecraft.player.level().dimension().identifier().toString().equals(destination.world())) {
                        return false;
                    }
                    assertTrue(MinecraftGravity.frame(minecraft.player).equals(gravity), "First arrival tick has stale client gravity");
                    assertTrue(Math.abs(minecraft.player.getScale() - SCALE) < 1.0E-6D, "First arrival tick has stale client traveller scale");
                    return true;
                });
                if (crossed) {
                    break;
                }
            }
        } finally {
            client.releaseForward();
        }
        assertTrue(crossed, "Native held-forward movement did not physically cross to " + destination.world());
        client.waitFor(minecraft -> !WormholesClient.instance().seamlessTravel().pending(), TIMEOUT_TICKS);
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            boolean confirmed = world.getServer().computeOnServer(server -> {
                ServerPlayer player = world.getConnection().getServerPlayer();
                return player.level().dimension().identifier().toString().equals(destination.world())
                    && MinecraftGravity.frame(player).equals(gravity) && Math.abs(player.getScale() - SCALE) < 1.0E-6D;
            });
            if (confirmed) {
                client.runOnClient(minecraft -> {
                    assertTrue(MinecraftGravity.frame(minecraft.player).equals(gravity), "Confirmed arrival changed local gravity");
                    assertTrue(Math.abs(minecraft.player.getScale() - SCALE) < 1.0E-6D, "Confirmed arrival changed local scale");
                });
                return;
            }
            client.waitTicks(1);
        }
        throw new AssertionError("Server did not confirm native gravity and scale at " + destination.world());
    }

    private static void awaitRemoval(FabricSeamlessClient client, TestSingleplayerContext world, Pair pair) {
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (world.getServer().computeOnServer(server -> runtime(server).portals().get(pair.source().id()) == null
                && runtime(server).portals().get(pair.destination().id()) == null)) {
                return;
            }
            client.waitTicks(1);
        }
        throw new AssertionError("Actual obsidian break did not remove both original reciprocal Nether endpoints");
    }

    private static void cleanup(MinecraftServer server, ServerPlayer player, Pair pair) {
        try {
            MinecraftGravity.set(player, GravityFrame.WORLD);
            MinecraftScaleAccess.scaleAttribute().reset(player);
            for (Endpoint endpoint : List.of(pair.source(), pair.destination())) {
                if (runtime(server).portals().get(endpoint.id()) != null) {
                    runtime(server).portals().remove(endpoint.id());
                }
            }
        } finally {
            for (ChunkLease lease : pair.leases()) {
                lease.close();
            }
        }
    }

    private static void hidden(Minecraft minecraft) {
        assertTrue(Boolean.parseBoolean(System.getProperty("automator.hidden", "true"))
            && NativeTestWindow.hiddenAndUnfocused(minecraft) && NativeTestWindow.mouseReleased(minecraft),
            "Rotated Nether acceptance requires a hidden unfocused GPU window with mouse released");
    }

    private static Vec3 point(Vec3d point) {
        return new Vec3(point.x(), point.y(), point.z());
    }

    private record Endpoint(UUID id, String world, Frame frame, Vec3d origin, List<BlockPos> cells, List<BlockPos> border) {
    }

    private record Pair(Endpoint source, Endpoint destination, Frame rotated, GravityFrame gravity, List<ChunkLease> leases) {
    }

    private record Route(UUID source, String sourceWorld, String destinationWorld, Frame frame, Similarity toward, BlockPos marker, boolean front) {
    }
}
