package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientViewSession;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class PaperMeshTimeoutClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final long OBSERVATION_NANOS = 65_000_000_000L;

    @Override
    public void runTest(ClientGameTestContext context) {
        String address = ExternalServerSession.configuredAddress();
        if (address.isEmpty()) {
            throw new IllegalArgumentException("Paper mesh acceptance requires --server and the reciprocal nested fixture");
        }
        try (ExternalServerSession ignored = ExternalServerSession.connect(new FabricTestClient(context), address)) {
            context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), 600);
            observe(context, new Observation("source", 8.5D, 13.5D, 180.0F, true));
            observe(context, new Observation("source-back", 8.5D, 3.5D, 0.0F, false));
            observe(context, new Observation("return", 104.5D, 13.5D, 180.0F, false));
        }
    }

    private void observe(ClientGameTestContext context, Observation observation) {
        context.runOnClient(client -> client.getConnection().sendCommand("tp @s " + observation.x() + " 191 " + observation.z()
            + " " + observation.yaw() + " 0"));
        context.waitFor(client -> Math.abs(client.player.getX() - observation.x()) < 0.1D
            && Math.abs(client.player.getZ() - observation.z()) < 0.1D && Math.abs(client.player.getY() - 191.0D) < 0.1D, 400);
        context.waitTicks(40);
        context.waitFor(client -> ready(observation), 1200);
        List<Integer> keys = context.computeOnClient(client -> selected(observation));
        int resets = context.computeOnClient(client -> WormholesClient.instance().session().resets());
        ClientPortalRenderer.NativeProof before = context.computeOnClient(client -> ClientPortalRenderer.instance().nativeProof());
        context.takeScreenshot("paper-mesh-" + observation.label() + "-start");
        long started = System.nanoTime();
        int samples = 0;
        while (System.nanoTime() - started < OBSERVATION_NANOS) {
            context.waitTick();
            context.runOnClient(client -> {
                ClientViewSession session = WormholesClient.instance().session();
                if (!session.active() || session.resets() != resets) {
                    throw new AssertionError("Paper mesh session reset during " + observation.label() + ": " + session.lastReset());
                }
                for (int key : keys) {
                    if (!NativeClientViewAssertions.ready(key)) {
                        throw new AssertionError("Paper mesh became unavailable during " + observation.label() + ": " + key);
                    }
                }
                ClientPortalRenderer.NativeProof proof = ClientPortalRenderer.instance().nativeProof();
                if (proof.preparedFrame() - proof.lastTerrainFrame() > 3L) {
                    throw new AssertionError("Paper mesh terrain stopped drawing during " + observation.label() + ": " + proof);
                }
            });
            if (++samples > 5000) {
                throw new AssertionError("Paper mesh observation exceeded its tick budget");
            }
        }
        context.takeScreenshot("paper-mesh-" + observation.label() + "-end");
        ClientPortalRenderer.NativeProof after = context.computeOnClient(client -> ClientPortalRenderer.instance().nativeProof());
        if (after.terrainFrames() - before.terrainFrames() < 100L) {
            throw new AssertionError("Paper mesh did not render advancing terrain frames: " + before + " -> " + after);
        }
        LOGGER.info("[paper-mesh-timeout] {} remained ready for {}ms across {} ticks; keys={} before={} after={}",
            observation.label(), (System.nanoTime() - started) / 1_000_000L, samples, keys, before, after);
    }

    private boolean ready(Observation observation) {
        List<Integer> keys = selected(observation);
        boolean root = false;
        boolean nested = false;
        for (int key : keys) {
            ClientPortal portal = WormholesClient.instance().session().portals().get(key);
            if (!NativeClientViewAssertions.ready(key)) {
                return false;
            }
            root |= !portal.nested();
            nested |= portal.nested();
        }
        ClientPortalRenderer.NativeProof proof = ClientPortalRenderer.instance().nativeProof();
        return root && (!observation.nested() || nested) && proof.terrainFrames() > 0L
            && proof.preparedFrame() - proof.lastTerrainFrame() <= 1L;
    }

    private List<Integer> selected(Observation observation) {
        List<Integer> keys = new ArrayList<>();
        ClientViewSession session = WormholesClient.instance().session();
        for (ClientPortal portal : session.portals().values()) {
            if (!portal.nested() && Math.abs(portal.geometry().originX() - observation.x()) < 8.0D
                && Math.abs(portal.geometry().originZ() - 8.0D) <= 1.0D) {
                keys.add(portal.portalKey());
            }
        }
        if (observation.nested()) {
            boolean changed = true;
            while (changed) {
                changed = false;
                for (ClientPortal portal : session.portals().values()) {
                    if (portal.nested() && !keys.contains(portal.portalKey()) && keys.contains(portal.geometry().parentPortalKey())) {
                        keys.add(portal.portalKey());
                        changed = true;
                    }
                }
            }
        }
        return keys;
    }

    private record Observation(String label, double x, double z, float yaw, boolean nested) {}
}
