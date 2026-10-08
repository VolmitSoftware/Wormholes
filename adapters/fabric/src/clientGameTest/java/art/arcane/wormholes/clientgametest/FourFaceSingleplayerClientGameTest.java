package art.arcane.wormholes.clientgametest;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.function.Predicate;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class FourFaceSingleplayerClientGameTest implements FabricClientGameTest {
    private static final BlockPos SOURCE = new BlockPos(48, 70, 20);
    private static final BlockPos DESTINATION = new BlockPos(48, 70, 44);
    private static final int PREPARE_TICKS = 600;
    private static final int FACE_UPDATE_TICKS = 3;
    private static final int WALK_TICKS = 60;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            SeamlessClient client = new FabricSeamlessClient(context, singleplayer.getConnection());
            SeamlessServer server = new FabricSeamlessServer(singleplayer.getServer(), singleplayer.getConnection());
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            SeamlessScenario.Route route = server.build(new SeamlessScenario.RouteSpec(Level.OVERWORLD, SOURCE, Level.OVERWORLD,
                DESTINATION, OrientationPolicy.FRAME, false));
            Throwable failure = null;
            try {
                singleplayer.getServer().runOnServer(minecraftServer -> {
                    WormholesModRuntime runtime = runtime(minecraftServer);
                    runtime.nexus().pair(singleplayer.getConnection().getServerPlayer(), runtime.portals().get(route.source()),
                        runtime.portals().get(route.destination()), true);
                });
                approach(client, server, SOURCE, route.source());
                cross(client, DESTINATION, "source back to destination");
                approach(client, server, DESTINATION, route.destination());
                cross(client, SOURCE, "destination back to source");
                approach(client, server, SOURCE, route.source());
                aroundEdge(client, SOURCE, route.source());
                cross(client, DESTINATION, "source front to destination");
                approach(client, server, DESTINATION, route.destination());
                aroundEdge(client, DESTINATION, route.destination());
                cross(client, SOURCE, "destination front to source");
                singleplayer.getServer().runOnServer(minecraftServer -> {
                    WormholesModRuntime runtime = runtime(minecraftServer);
                    assertTrue(route.destination().equals(runtime.portals().get(route.source()).getDestinationId()), "source lost its destination");
                    assertTrue(route.source().equals(runtime.portals().get(route.destination()).getDestinationId()), "destination lost its return link");
                });
            } catch (RuntimeException | Error exception) {
                failure = exception;
                throw exception;
            } finally {
                try {
                    client.releaseForward();
                    SeamlessScenario.finish(client, server, route);
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure == null) {
                        throw cleanupFailure;
                    }
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
    }

    private static void approach(SeamlessClient client, SeamlessServer server, BlockPos portal, UUID id) {
        server.approachFrom(Level.OVERWORLD, new Vec3(portal.getX() + 1.5D, portal.getY(), portal.getZ() + 6.5D), 180.0F);
        client.waitForChunksRender();
        client.waitFor(minecraft -> face(minecraft, portal, id, false), PREPARE_TICKS);
        walk(client, 180.0F, minecraft -> minecraft.player.getZ() < portal.getZ() + 1.5D);
        assertTrue(client.computeOnClient(minecraft -> face(minecraft, portal, id, false)), "back face stopped rendering or accepting travel");
    }

    private static void aroundEdge(SeamlessClient client, BlockPos portal, UUID id) {
        walk(client, -90.0F, minecraft -> minecraft.player.getX() > portal.getX() + 4.5D);
        walk(client, 180.0F, minecraft -> minecraft.player.getZ() < portal.getZ() - 0.8D);
        walk(client, 90.0F, minecraft -> minecraft.player.getX() < portal.getX() + 1.6D);
        client.lookAt(0.0F, 0.0F);
        client.waitFor(minecraft -> face(minecraft, portal, id, true), FACE_UPDATE_TICKS);
        assertTrue(client.computeOnClient(minecraft -> Math.abs(minecraft.player.getZ() - (portal.getZ() + 0.5D)) < 2.0D),
            "edge walk left the near-plane distance before testing the changed face");
    }

    private static void walk(SeamlessClient client, float yaw, Predicate<Minecraft> reached) {
        client.lookAt(yaw, 0.0F);
        client.holdForward();
        try {
            client.waitFor(reached, WALK_TICKS);
        } finally {
            client.releaseForward();
        }
        client.waitTicks(2);
    }

    private static boolean face(Minecraft minecraft, BlockPos origin, UUID id, boolean front) {
        WormholesClient client = WormholesClient.instance();
        if (client == null || minecraft.player == null || client.seamlessTravel().pending()) {
            return false;
        }
        boolean armed = false;
        for (TravelMessage.TravelBegin arm : client.seamlessTravel().arms()) {
            if (arm.sourcePortal().equals(id) && arm.sourceGeometry().frontSide() == front) {
                armed = true;
                break;
            }
        }
        if (!armed) {
            return false;
        }
        Vec3 eye = minecraft.player.getEyePosition();
        Vec3d currentEye = new Vec3d(eye.x, eye.y, eye.z);
        for (PortalView view : client.portalViews().current()) {
            if (view.kind() == PortalView.Kind.ARM && view.surface().geometry().originX() == origin.getX()
                && view.surface().geometry().originY() == origin.getY() && view.surface().geometry().originZ() == origin.getZ()
                && view.surface().geometry().frontSide() == front && view.source() == minecraft.level
                && view.surface().servesEye(currentEye) && !view.failing(System.currentTimeMillis())) {
                return true;
            }
        }
        return false;
    }

    private static void cross(SeamlessClient client, BlockPos destination, String label) {
        SeamlessScenario.Crossing crossing = SeamlessScenario.walkThrough(client, label);
        client.waitFor(minecraft -> !WormholesClient.instance().seamlessTravel().pending(), 100);
        assertTrue(client.computeOnClient(minecraft -> minecraft.player.position().distanceTo(
            new Vec3(destination.getX() + 1.5D, destination.getY(), destination.getZ() + 0.5D)) < 8.0D), label + ": wrong destination");
        assertTrue(client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player) == crossing.player()), label + ": local player replaced");
        assertTrue(TravelTap.respawns() == 0 && TravelTap.positions() == 0 && TravelTap.accepts() == 0, label + ": travel used a vanilla teleport");
        assertTrue(!TravelTap.loadingScreenShown(), label + ": loading screen shown");
    }
}
