package art.arcane.wormholes.clientgametest;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
import java.util.function.Predicate;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class AngledCrossingClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final BlockPos SOURCE = new BlockPos(80, 70, 24);
    private static final BlockPos DESTINATION = new BlockPos(80, 70, 80);
    private static final int PREPARE_TICKS = 600;
    private static final int WALK_TICKS = 60;
    private static final int FACE_UPDATE_TICKS = 3;
    private static final double JUMP = 20.0D;
    private static final double POSITION_TOLERANCE = 0.7D;
    private static final float LOOK_TOLERANCE = 0.05F;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            SeamlessClient client = new FabricSeamlessClient(context, world.getConnection());
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            assertTrue(client.computeOnClient(minecraft -> WormholesClient.instance().session().nativeSelected()),
                "angled crossings require native singleplayer portal travel");
            for (Face normal : new Face[]{Face.N, Face.S, Face.E, Face.W}) {
                Route route = world.getServer().computeOnServer(server -> build(server, world.getConnection().getServerPlayer(), normal));
                try {
                    for (boolean front : new boolean[]{true, false}) {
                        roundTrip(client, world, route, front, 45.0D, 0.65D, 0.0D, "oblique");
                        roundTrip(client, world, route, front, 75.0D, 0.3D, 0.0D, "glancing");
                        roundTrip(client, world, route, front, 0.0D, 0.55D, -1.4D, "inside-rim");
                        outsideRim(client, world, route, front);
                        lateralEntry(client, world, route, front);
                    }
                    aroundEdge(client, world, route);
                    world.getServer().runOnServer(server -> {
                        WormholesModRuntime runtime = runtime(server);
                        assertTrue(route.destination().equals(runtime.portals().get(route.source()).getDestinationId()), "source link changed");
                        assertTrue(route.source().equals(runtime.portals().get(route.destination()).getDestinationId()), "return link changed");
                    });
                } finally {
                    client.releaseForward();
                    world.getServer().runOnServer(server -> {
                        WormholesModRuntime runtime = runtime(server);
                        ServerPlayer player = world.getConnection().getServerPlayer();
                        runtime.portals().remove(player, route.source());
                        runtime.portals().remove(player, route.destination());
                    });
                }
            }
            client.restoreDefaultGameOptions();
        }
    }

    private static Route build(MinecraftServer server, ServerPlayer player, Face normal) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel level = server.overworld();
        prepareRoom(level, SOURCE);
        prepareRoom(level, DESTINATION);
        MinecraftPortal source = runtime.portals().create(player.getUUID(), level, cells(SOURCE, normal), PortalType.PORTAL, vector(normal));
        MinecraftPortal destination = runtime.portals().create(player.getUUID(), level, cells(DESTINATION, normal), PortalType.PORTAL, vector(normal));
        assertTrue(source != null && destination != null, "angled route portal creation rejected");
        source.setOrientation(OrientationPolicy.FRAME);
        destination.setOrientation(OrientationPolicy.FRAME);
        assertTrue(source.getFrame().equals(Frame.canonical(normal)) && source.getFrame().equals(destination.getFrame()),
            "angled route does not use matching cardinal frames");
        assertTrue(runtime.portals().link(player, source.getId(), destination.getId()), "angled source link rejected");
        assertTrue(runtime.portals().link(player, destination.getId(), source.getId()), "angled return link rejected");
        runtime.nexus().pair(player, source, destination, true);
        player.setGameMode(GameType.SURVIVAL);
        return new Route(source.getId(), destination.getId(), point(source.getOrigin()), point(destination.getOrigin()),
            vector(normal), vector(source.getFrame().getRight()));
    }

    private static void prepareRoom(ServerLevel level, BlockPos base) {
        for (int x = -5; x <= 7; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = -1; y <= 4; y++) {
                    level.setBlockAndUpdate(base.offset(x, y, z), y == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static List<BlockPos> cells(BlockPos base, Face normal) {
        List<BlockPos> result = new ArrayList<>(9);
        for (int lateral = 0; lateral < 3; lateral++) {
            for (int y = 0; y < 3; y++) {
                result.add(normal.x() == 0 ? base.offset(lateral, y, 0) : base.offset(1, y, lateral - 1));
            }
        }
        return result;
    }

    private static void roundTrip(SeamlessClient client, TestSingleplayerContext world, Route route, boolean front, double angle,
                                  double gap, double rim, String mode) {
        double side = front ? 1.0D : -1.0D;
        double radians = Math.toRadians(angle);
        Vec3 direction = route.normal().scale(-side * Math.cos(radians)).add(route.right().scale(Math.sin(radians)));
        Vec3 start = feet(route.origin()).add(route.normal().scale(side * gap)).add(route.right().scale(rim - gap * Math.tan(radians)));
        float yaw = Angles.yaw(direction.x, direction.z);
        float pitch = front ? 17.0F : -13.0F;
        String label = route.normal() + " " + (front ? "front " : "back ") + mode;
        setup(client, world, route, start, yaw, pitch, front);
        Snapshot original = client.computeOnClient(AngledCrossingClientGameTest::snapshot);
        ClientLevel originalLevel = client.computeOnClient(minecraft -> minecraft.level);
        Snapshot outbound = cross(client, original, route.source(), route.exit().subtract(route.origin()), yaw, pitch, label);
        boolean originalCached = client.computeOnClient(minecraft -> WormholesClient.instance().seamlessTravel().residents().resident(originalLevel));
        client.lookAt(yaw + 180.0F, pitch);
        Snapshot returned = cross(client, outbound, route.destination(), route.origin().subtract(route.exit()), yaw + 180.0F, pitch,
            label + " immediate reverse");
        if (originalCached) {
            assertLevel(returned, original.level(), label + ": reverse did not reuse the cached source level");
        }
        assertTrue(returned.position().distanceTo(original.position()) < 2.0D, label + ": return missed the source aperture");
        settled(client, world, returned, label);
        LOGGER.info("[angled-crossing] {} passed with immediate reverse", label);
    }

    private static Snapshot cross(SeamlessClient client, Snapshot initial, UUID portal, Vec3 translation, float yaw, float pitch, String label) {
        client.holdForward();
        Snapshot previous = initial;
        try (TickStepper stepper = client.lockstep()) {
            for (int tick = 0; tick < WALK_TICKS; tick++) {
                PreparedTarget target = client.computeOnClient(minecraft -> preparedTarget(minecraft, portal));
                Snapshot next = stepper.step(AngledCrossingClientGameTest::snapshot);
                assertLocal(next, initial, yaw, pitch, label);
                if (next.position().distanceTo(previous.position()) > JUMP) {
                    assertTrue(target != null, label + ": crossed without a prepared target; " + identities(initial, next));
                    assertLevel(next, target.level(), label + ": wrong cached destination for handle " + target.handle()
                        + "; " + identities(initial, next));
                    assertTrue(next.position().distanceTo(previous.position().add(translation)) < POSITION_TOLERANCE,
                        label + ": wrong mapped destination " + next.position());
                    assertTrue(Math.abs(next.position().y - SOURCE.getY()) < 0.05D, label + ": crossing changed feet height");
                    LOGGER.info("[angled-crossing] {} player {} -> {}, cached level {} -> {}, route handle {}, {}", label,
                        initial.player(), next.player(), initial.level(), next.level(), target.handle(), TravelTap.events());
                    return next;
                }
                assertLevel(next, initial.level(), label + ": active level changed before crossing; " + identities(initial, next));
                previous = next;
            }
        } finally {
            client.releaseForward();
        }
        throw new AssertionError(label + ": no physical crossing from " + initial.position() + "; stopped at " + previous.position());
    }

    private static void outsideRim(SeamlessClient client, TestSingleplayerContext world, Route route, boolean front) {
        double side = front ? 1.0D : -1.0D;
        Vec3 start = feet(route.origin()).add(route.normal().scale(side * 0.55D)).add(route.right().scale(-1.6D));
        Vec3 direction = route.normal().scale(-side);
        float yaw = Angles.yaw(direction.x, direction.z);
        String label = route.normal() + " outside rim " + front;
        setup(client, world, route, start, yaw, 0.0F, front);
        Snapshot initial = client.computeOnClient(AngledCrossingClientGameTest::snapshot);
        walk(client, yaw, minecraft -> distance(snapshot(minecraft).position(), route.origin(), route.normal()) * side < -0.65D, WALK_TICKS);
        Snapshot after = client.computeOnClient(AngledCrossingClientGameTest::snapshot);
        assertLocal(after, initial, yaw, 0.0F, label);
        assertLevel(after, initial.level(), label + ": outside-rim movement changed active level");
        assertTrue(after.position().distanceTo(start) < 3.0D, label + ": crossing outside the aperture teleported the player");
        settled(client, world, after, label);
    }

    private static void lateralEntry(SeamlessClient client, TestSingleplayerContext world, Route route, boolean front) {
        double side = front ? 1.0D : -1.0D;
        Vec3 start = feet(route.origin()).add(route.normal().scale(side * 0.04D)).add(route.right().scale(-2.3D));
        float yaw = Angles.yaw(route.right().x, route.right().z);
        String label = route.normal() + " lateral entry " + front;
        setup(client, world, route, start, yaw, 0.0F, front);
        Snapshot initial = client.computeOnClient(AngledCrossingClientGameTest::snapshot);
        walk(client, yaw, minecraft -> distance(snapshot(minecraft).position(), route.origin(), route.right()) > 2.1D, WALK_TICKS);
        Snapshot after = client.computeOnClient(AngledCrossingClientGameTest::snapshot);
        assertLocal(after, initial, yaw, 0.0F, label);
        assertLevel(after, initial.level(), label + ": lateral movement changed active level");
        assertTrue(Math.abs(distance(after.position(), route.origin(), route.normal()) - side * 0.04D) < 0.01D,
            label + ": lateral movement crossed the portal plane");
        assertTrue(after.position().distanceTo(start) < 6.0D, label + ": entering from the side teleported the player");
        settled(client, world, after, label);
    }

    private static void aroundEdge(SeamlessClient client, TestSingleplayerContext world, Route route) {
        Vec3 start = feet(route.origin()).subtract(route.normal());
        float inward = Angles.yaw(route.normal().x, route.normal().z);
        setup(client, world, route, start, inward, 0.0F, false);
        Snapshot initial = client.computeOnClient(AngledCrossingClientGameTest::snapshot);
        walk(client, Angles.yaw(route.right().x, route.right().z),
            minecraft -> distance(snapshot(minecraft).position(), route.origin(), route.right()) > 2.2D, WALK_TICKS);
        walk(client, inward, minecraft -> distance(snapshot(minecraft).position(), route.origin(), route.normal()) > 0.8D, WALK_TICKS);
        walk(client, Angles.yaw(-route.right().x, -route.right().z),
            minecraft -> distance(snapshot(minecraft).position(), route.origin(), route.right()) < 0.1D, WALK_TICKS);
        float outward = Angles.yaw(-route.normal().x, -route.normal().z);
        client.lookAt(outward, 0.0F);
        waitFaceReady(client, world, route);
        Snapshot changed = client.computeOnClient(AngledCrossingClientGameTest::snapshot);
        assertTrue(Math.abs(distance(changed.position(), route.origin(), route.normal())) < 2.0D,
            "edge walk left the near-plane face-switching distance");
        assertTrue(changed.position().distanceTo(initial.position()) < 3.0D, "edge walk unexpectedly teleported");
        assertLevel(changed, initial.level(), "edge walk changed active level");
        ClientLevel originalLevel = client.computeOnClient(minecraft -> minecraft.level);
        Snapshot crossed = cross(client, changed, route.source(), route.exit().subtract(route.origin()), outward, 0.0F, route.normal() + " changed face");
        boolean originalCached = client.computeOnClient(minecraft -> WormholesClient.instance().seamlessTravel().residents().resident(originalLevel));
        client.lookAt(outward + 180.0F, 0.0F);
        Snapshot returned = cross(client, crossed, route.destination(), route.origin().subtract(route.exit()), outward + 180.0F, 0.0F,
            route.normal() + " changed face immediate reverse");
        if (originalCached) {
            assertLevel(returned, changed.level(), "changed-face reverse did not reuse the cached source level");
        }
        settled(client, world, returned, "edge face change");
    }

    private static void setup(SeamlessClient client, TestSingleplayerContext world, Route route, Vec3 start, float yaw, float pitch, boolean front) {
        client.releaseForward();
        Vec3 staging = feet(route.origin()).add(route.normal().scale(front ? 4.0D : -4.0D));
        place(client, world, staging, yaw, pitch);
        waitReady(client, world, route, staging, front, "staging");
        place(client, world, start, yaw, pitch);
        waitReady(client, world, route, start, front, "near aperture");
        client.waitTicks(2);
        client.runOnClient(minecraft -> TravelTap.reset());
    }

    private static void place(SeamlessClient client, TestSingleplayerContext world, Vec3 start, float yaw, float pitch) {
        world.getServer().runOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            player.teleportTo(server.overworld(), start.x, start.y, start.z, Set.of(), yaw, pitch, false);
            player.setDeltaMovement(Vec3.ZERO);
        });
        client.waitFor(minecraft -> minecraft.player != null && minecraft.player.position().distanceTo(start) < 0.05D, PREPARE_TICKS);
        client.waitForChunksRender();
        client.lookAt(yaw, pitch);
    }

    private static void waitReady(SeamlessClient client, TestSingleplayerContext world, Route route, Vec3 start, boolean front, String phase) {
        try {
            client.waitFor(minecraft -> ready(minecraft, route.source(), front, false)
                && minecraft.player.getDeltaMovement().horizontalDistanceSqr() < 0.0001D, PREPARE_TICKS);
        } catch (AssertionError | RuntimeException failure) {
            String local = client.computeOnClient(minecraft -> readinessDiagnostic(minecraft, route));
            String authority = authorityDiagnostic(world, route);
            throw new AssertionError("route readiness failed during " + phase + ": requested=" + start + " front=" + front
                + " source=" + route.source() + " normal=" + route.normal() + "; client=" + local + "; server=" + authority, failure);
        }
    }

    private static void waitFaceReady(SeamlessClient client, TestSingleplayerContext world, Route route) {
        int startedTick = client.computeOnClient(minecraft -> minecraft.player.tickCount);
        long started = System.nanoTime();
        try {
            client.waitFor(minecraft -> {
                if (!ready(minecraft, route.source(), true, true)) {
                    return false;
                }
                LOGGER.info("[angled-crossing] {} edge face ready elapsedClientTicks={} limitTicks={} elapsedMs={}", route.normal(),
                    minecraft.player.tickCount - startedTick, FACE_UPDATE_TICKS, (System.nanoTime() - started) / 1_000_000L);
                return true;
            }, FACE_UPDATE_TICKS);
        } catch (AssertionError | RuntimeException failure) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            int elapsedTicks = client.computeOnClient(minecraft -> minecraft.player.tickCount - startedTick);
            String local = client.computeOnClient(minecraft -> readinessDiagnostic(minecraft, route));
            String authority = authorityDiagnostic(world, route);
            throw new AssertionError("edge face readiness failed: source=" + route.source() + " normal=" + route.normal()
                + " expectedFront=true limitTicks=" + FACE_UPDATE_TICKS + " elapsedClientTicks=" + elapsedTicks
                + " elapsedMs=" + elapsedMs + "; client=" + local + "; server=" + authority, failure);
        }
    }

    private static String authorityDiagnostic(TestSingleplayerContext world, Route route) {
        return world.getServer().computeOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            WormholesModRuntime runtime = runtime(server);
            MinecraftPortal source = runtime.portals().get(route.source());
            MinecraftPortal destination = runtime.portals().get(route.destination());
            Vec3 eye = player.getEyePosition();
            return "tick=" + server.getTickCount() + " position=" + player.position() + " eye=" + eye + " delta=" + player.getDeltaMovement()
                + " sourceDistance=" + distance(eye, route.origin(), route.normal()) + " lateral=" + distance(eye, route.origin(), route.right())
                + " yaw=" + player.getYRot() + " pitch=" + player.getXRot() + " look=" + player.getLookAngle()
                + " observedVelocity=" + runtime.portals().observedVelocity(player) + " world=" + player.level().dimension().identifier()
                + " armed=" + runtime.clientViews().seamlessTravel(player.getUUID(), route.source())
                + " crossing=" + runtime.clientViews().crossing(player.getUUID())
                + " preparation=" + runtime.clientViews().preparation(player.getUUID()).map(begin -> begin.token() + "/" + begin.generation()
                    + " source=" + begin.sourcePortal() + ' ' + geometryDiagnostic(begin.sourceGeometry(), new Vec3d(eye.x, eye.y, eye.z)))
                + " source=" + portalDiagnostic(source) + " destination=" + portalDiagnostic(destination);
        });
    }

    private static String readinessDiagnostic(Minecraft minecraft, Route route) {
        WormholesClient client = WormholesClient.instance();
        Vec3d eye = new Vec3d(minecraft.player.getX(), minecraft.player.getEyeY(), minecraft.player.getZ());
        StringBuilder result = new StringBuilder("snapshot=" + snapshot(minecraft) + " eye=" + eye
            + " sourceDistance=" + distance(minecraft.player.getEyePosition(), route.origin(), route.normal())
            + " delta=" + minecraft.player.getDeltaMovement() + " pending=" + client.seamlessTravel().pending()
            + " unprepared=" + client.seamlessTravel().unprepared() + " events=" + TravelTap.events());
        int count = 0;
        for (TravelMessage.TravelBegin arm : client.seamlessTravel().arms()) {
            if (count++ == 8) {
                result.append(" additional arms omitted");
                break;
            }
            ClientLevel target = arm.resident() ? client.seamlessTravel().residents().level(arm.levelHandle()) : minecraft.level;
            result.append(" arm{source=").append(arm.sourcePortal()).append(" token=").append(arm.token())
                .append('/').append(arm.generation()).append(" handle=").append(arm.levelHandle())
                .append(" targetLevel=").append(System.identityHashCode(target))
                .append(' ').append(geometryDiagnostic(arm.sourceGeometry(), eye)).append('}');
        }
        count = 0;
        for (PortalView view : client.portalViews().current()) {
            if (count++ == 8) {
                result.append(" additional views omitted");
                break;
            }
            result.append(" view{kind=").append(view.kind()).append(" key=").append(view.key())
                .append(" keyType=").append(view.key().getClass().getName())
                .append(" sourceLevel=").append(System.identityHashCode(view.source()))
                .append(" targetLevel=").append(System.identityHashCode(view.destination()))
                .append(" servesEye=").append(view.surface().servesEye(eye))
                .append(" failing=").append(view.failing(System.currentTimeMillis()))
                .append(' ').append(geometryDiagnostic(view.surface().geometry(), eye)).append('}');
        }
        return result.toString();
    }

    private static String geometryDiagnostic(ApertureDescriptor geometry, Vec3d eye) {
        double signed = geometry.signedDistance(eye.x(), eye.y(), eye.z());
        Face normal = geometry.facingDirection();
        Vec3d projected = eye.subtract(new Vec3d(normal.x(), normal.y(), normal.z()).multiply(signed));
        return "front=" + geometry.frontSide() + " normal=" + normal + " turns=" + geometry.quarterTurns()
            + " origin=" + geometry.originX() + ',' + geometry.originY() + ',' + geometry.originZ()
            + " planeOffset=" + geometry.planeOffset() + " distance=" + signed
            + " projectedInside=" + geometry.containsPoint(projected.x(), projected.y(), projected.z());
    }

    private static String portalDiagnostic(MinecraftPortal portal) {
        return portal == null ? "missing" : "{id=" + portal.getId() + " origin=" + portal.getOrigin()
            + " normal=" + portal.getFrame().getNormal() + " up=" + portal.getFrame().getUp()
            + " right=" + portal.getFrame().getRight() + '}';
    }

    private static boolean ready(Minecraft minecraft, UUID source, boolean front, boolean rendered) {
        WormholesClient client = WormholesClient.instance();
        if (client.seamlessTravel().pending()) {
            return false;
        }
        for (TravelMessage.TravelBegin arm : client.seamlessTravel().arms()) {
            if (!arm.sourcePortal().equals(source) || arm.sourceGeometry().frontSide() != front) {
                continue;
            }
            if (!rendered) {
                return true;
            }
            ClientLevel target = arm.resident() ? client.seamlessTravel().residents().level(arm.levelHandle()) : minecraft.level;
            for (PortalView view : client.portalViews().current()) {
                if (view.kind() == PortalView.Kind.ARM && view.source() == minecraft.level && view.destination() == target
                    && view.surface().geometry().equals(arm.sourceGeometry()) && view.toDestination().equals(arm.sourceToDestination())
                    && view.surface().geometry().frontSide() == front && view.surface().servesEye(
                        new Vec3d(minecraft.player.getX(), minecraft.player.getEyeY(), minecraft.player.getZ()))
                    && !view.failing(System.currentTimeMillis())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void walk(SeamlessClient client, float yaw, Predicate<Minecraft> reached, int timeout) {
        client.lookAt(yaw, 0.0F);
        client.holdForward();
        try {
            client.waitFor(reached, timeout);
        } finally {
            client.releaseForward();
        }
    }

    private static void settled(SeamlessClient client, TestSingleplayerContext world, Snapshot expected, String label) {
        client.waitFor(minecraft -> !WormholesClient.instance().seamlessTravel().pending(), 100);
        client.waitTicks(3);
        Snapshot local = client.computeOnClient(AngledCrossingClientGameTest::snapshot);
        Snapshot authority = world.getServer().computeOnServer(server -> {
            ServerPlayer player = world.getConnection().getServerPlayer();
            return new Snapshot(player.position(), player.getYRot(), player.getXRot(), player.level().dimension().identifier().toString(),
                System.identityHashCode(player), System.identityHashCode(player.level()));
        });
        assertLocal(local, expected, expected.yaw(), expected.pitch(), label);
        assertLevel(local, expected.level(), label + ": destination level changed while settling");
        assertTrue(authority.world().equals(local.world()) && authority.position().distanceTo(local.position()) < POSITION_TOLERANCE,
            label + ": client/server world or position differs " + authority.position() + " / " + local.position());
        assertTrue(Math.abs(Angles.unwrap(authority.yaw() - local.yaw(), 0)) < LOOK_TOLERANCE
            && Math.abs(authority.pitch() - local.pitch()) < LOOK_TOLERANCE, label + ": client/server look differs");
        assertTrue(TravelTap.respawns() == 0 && TravelTap.positions() == 0 && TravelTap.accepts() == 0,
            label + ": crossing used respawn, correction, or vanilla teleport acknowledgement; " + TravelTap.events());
        assertTrue(!TravelTap.loadingScreenShown() && !TravelTap.clientUnloaded(), label + ": crossing displayed loading state");
    }

    private static void assertLocal(Snapshot current, Snapshot original, float yaw, float pitch, String label) {
        assertTrue(current.world().equals(Level.OVERWORLD.identifier().toString()), label + ": wrong client world");
        assertTrue(current.player() == original.player(), label + ": local player replaced; " + identities(original, current));
        assertTrue(Math.abs(Angles.unwrap(current.yaw() - yaw, 0)) < LOOK_TOLERANCE && Math.abs(current.pitch() - pitch) < LOOK_TOLERANCE,
            label + ": crossing changed look from " + yaw + " / " + pitch + " to " + current.yaw() + " / " + current.pitch());
    }

    private static Snapshot snapshot(Minecraft minecraft) {
        return new Snapshot(minecraft.player.position(), minecraft.player.getYRot(), minecraft.player.getXRot(),
            minecraft.level.dimension().identifier().toString(), System.identityHashCode(minecraft.player), System.identityHashCode(minecraft.level));
    }

    private static PreparedTarget preparedTarget(Minecraft minecraft, UUID portal) {
        WormholesClient client = WormholesClient.instance();
        for (TravelMessage.TravelBegin arm : client.seamlessTravel().arms()) {
            if (!arm.sourcePortal().equals(portal)) {
                continue;
            }
            ClientLevel target = arm.resident() ? client.seamlessTravel().residents().level(arm.levelHandle()) : minecraft.level;
            return target == null ? null : new PreparedTarget(arm.levelHandle(), System.identityHashCode(target));
        }
        return null;
    }

    private static void assertLevel(Snapshot current, int expected, String label) {
        assertTrue(current.level() == expected, label + ": expected level=" + expected + " actual level=" + current.level()
            + " player=" + current.player() + " " + TravelTap.events());
    }

    private static String identities(Snapshot before, Snapshot after) {
        return "player " + before.player() + " -> " + after.player() + ", level " + before.level() + " -> " + after.level()
            + ", position " + before.position() + " -> " + after.position() + ", respawns=" + TravelTap.respawns()
            + ", corrections=" + TravelTap.positions() + ", acknowledgements=" + TravelTap.accepts() + ", " + TravelTap.events();
    }

    private static Vec3 feet(Vec3 origin) {
        return new Vec3(origin.x, SOURCE.getY(), origin.z);
    }

    private static double distance(Vec3 position, Vec3 origin, Vec3 axis) {
        return position.subtract(origin).dot(axis);
    }

    private static Vec3 vector(Face face) {
        return new Vec3(face.x(), face.y(), face.z());
    }

    private static Vec3 point(Vec3d point) {
        return new Vec3(point.x(), point.y(), point.z());
    }

    private record Route(UUID source, UUID destination, Vec3 origin, Vec3 exit, Vec3 normal, Vec3 right) {
    }

    private record Snapshot(Vec3 position, float yaw, float pitch, String world, int player, int level) {
    }

    private record PreparedTarget(int handle, int level) {
    }
}
