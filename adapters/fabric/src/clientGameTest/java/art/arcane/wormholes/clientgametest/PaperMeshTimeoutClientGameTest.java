package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientViewSession;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class PaperMeshTimeoutClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final long OBSERVATION_NANOS = 65_000_000_000L;

    @Override
    public void runTest(ClientGameTestContext context) {
        String address = ExternalServerSession.configuredAddress();
        if (address.isEmpty()) {
            throw new IllegalArgumentException("Paper mesh acceptance requires --server and the reciprocal nested fixture");
        }
        try (ExternalServerSession server = ExternalServerSession.connect(new FabricTestClient(context), address)) {
            context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), 600);
            server.command("whnested mesh", "NESTED ready=true");
            observe(context, new Observation("source", 8.5D, 13.5D, 180.0F, true, 96, 5));
            observe(context, new Observation("source-back", 8.5D, 3.5D, 0.0F, false, 96, 12));
            observe(context, new Observation("return", 104.5D, 13.5D, 180.0F, false, 0, 5));
        }
    }

    private void observe(ClientGameTestContext context, Observation observation) {
        context.runOnClient(client -> client.getConnection().sendCommand("tp @s " + observation.x() + " 191 " + observation.z()
            + " " + observation.yaw() + " 0"));
        context.waitFor(client -> Math.abs(client.player.getX() - observation.x()) < 0.1D
            && Math.abs(client.player.getZ() - observation.z()) < 0.1D && Math.abs(client.player.getY() - 191.0D) < 0.1D, 400);
        context.waitTicks(40);
        long initialDraws = context.computeOnClient(client -> ClientPortalRenderer.instance().nativeProof().terrainDraws());
        await(context, observation, "readiness", 1200, client -> ready(observation, initialDraws));
        List<Integer> keys = context.computeOnClient(client -> selected(observation));
        int root = context.computeOnClient(client -> keys.stream()
            .filter(key -> !WormholesClient.instance().session().portal(key).nested()).findFirst().orElseThrow());
        await(context, observation, "containers", 400,
            client -> StreamedContainerAssertions.captured(root, observation.destinationOffset(), observation.containerZ()));
        context.waitTicks(5);
        context.runOnClient(client -> StreamedContainerAssertions.rendered(root, observation.destinationOffset(), observation.containerZ()));
        int resets = context.computeOnClient(client -> WormholesClient.instance().session().resets());
        ClientPortalRenderer.NativeProof before = context.computeOnClient(client -> ClientPortalRenderer.instance().nativeProof());
        TerrainProgress progress = new TerrainProgress(before.terrainDraws());
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
                progress.observe(proof, observation.label());
            });
            if (++samples > 5000) {
                throw new AssertionError("Paper mesh observation exceeded its tick budget");
            }
        }
        context.takeScreenshot("paper-mesh-" + observation.label() + "-end");
        context.runOnClient(client -> StreamedContainerAssertions.rendered(root, observation.destinationOffset(), observation.containerZ()));
        ClientPortalRenderer.NativeProof after = context.computeOnClient(client -> ClientPortalRenderer.instance().nativeProof());
        if (progress.advancingSamples < 100) {
            throw new AssertionError("Paper mesh terrain advanced on only " + progress.advancingSamples + " samples: " + before + " -> " + after);
        }
        LOGGER.info("[paper-mesh-timeout] {} remained ready for {}ms across {} ticks; keys={} before={} after={}",
            observation.label(), (System.nanoTime() - started) / 1_000_000L, samples, keys, before, after);
    }

    private boolean ready(Observation observation, long initialDraws) {
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
        return root && (!observation.nested() || nested) && proof.terrainDraws() > initialDraws;
    }

    private void await(ClientGameTestContext context, Observation observation, String phase, int timeoutTicks,
                       Predicate<Minecraft> condition) {
        LOGGER.info("[paper-mesh-timeout] {} {} waiting: {}", observation.label(), phase,
            context.computeOnClient(client -> readinessState(observation)));
        try {
            context.waitFor(condition, timeoutTicks);
        } catch (AssertionError failure) {
            String state = context.computeOnClient(client -> readinessState(observation));
            LOGGER.error("[paper-mesh-timeout] {} {} failed: {}", observation.label(), phase, state);
            try {
                context.takeScreenshot("paper-mesh-" + observation.label() + "-" + phase + "-failed");
            } catch (RuntimeException | AssertionError screenshotFailure) {
                failure.addSuppressed(screenshotFailure);
            }
            throw new AssertionError("Paper mesh " + observation.label() + " " + phase + " timed out: " + state, failure);
        }
    }

    private String readinessState(Observation observation) {
        ClientViewSession session = WormholesClient.instance().session();
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        StringBuilder state = new StringBuilder();
        state.append("active=").append(session.active()).append(" resets=").append(session.resets())
            .append(" lastReset=").append(session.lastReset()).append(" selected=").append(selected(observation))
            .append(" proof=").append(renderer.nativeProof()).append(" renderer=").append(renderer.debugLine());
        for (ClientPortal portal : session.portals().values()) {
            int key = portal.portalKey();
            state.append(" portal=").append(key).append(" parent=").append(portal.geometry().parentPortalKey())
                .append(" origin=").append(portal.geometry().originX()).append(",").append(portal.geometry().originY())
                .append(",").append(portal.geometry().originZ()).append(" sections=").append(NativeClientViewAssertions.sections(key))
                .append(" available=").append(renderer.available(key)).append(" containers=")
                .append(StreamedContainerAssertions.describe(key, observation.destinationOffset(), observation.containerZ()));
        }
        return state.toString();
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

    private record Observation(String label, double x, double z, float yaw, boolean nested, int destinationOffset, int containerZ) {}

    private static final class TerrainProgress {
        private long draws;
        private int advancingSamples;
        private int unchangedSamples;

        private TerrainProgress(long draws) {
            this.draws = draws;
        }

        private void observe(ClientPortalRenderer.NativeProof proof, String label) {
            if (proof.terrainDraws() > draws) {
                draws = proof.terrainDraws();
                advancingSamples++;
                unchangedSamples = 0;
            } else if (++unchangedSamples > 20) {
                throw new AssertionError("Paper mesh terrain stopped drawing during " + label + ": " + proof);
            }
        }
    }
}
