package art.arcane.wormholes.clientgametest;

import art.arcane.optics.stream.BiomeVisual;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.wormholes.modded.client.ClientMeshSections;
import art.arcane.wormholes.modded.client.ClientMeshWorld;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientViewSession;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

public final class PaperBiomeProjectionClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String BIOME = "iris:biomes/qa_visual";
    private static final long OBSERVATION_NANOS = 20_000_000_000L;

    @Override
    public void runTest(ClientGameTestContext context) {
        String address = ExternalServerSession.configuredAddress();
        if (address.isEmpty()) {
            throw new IllegalArgumentException("Biome projection acceptance requires the external custom-biome fixture");
        }
        try {
            run(context, address, BiomeRegistryFaults.Mode.NONE, "native", false);
            run(context, address, BiomeRegistryFaults.Mode.MISSING_KEYS, "missing-keys", true);
            run(context, address, BiomeRegistryFaults.Mode.INVALID_ONE, "invalid-one", false);
            run(context, address, BiomeRegistryFaults.Mode.MISSING_KEYS, "recovered", false);
            context.runOnClient(client -> {
                if (client.level != null || WormholesClient.instance().session().active()
                    || !WormholesClient.instance().session().portals().isEmpty()) {
                    throw new AssertionError("Biome acceptance left a connected session or retained portals");
                }
                LOGGER.info("[paper-biome] all six observation windows passed; disconnected session and portals cleared");
            });
        } finally {
            context.runOnClient(client -> BiomeRegistryFaults.mode(BiomeRegistryFaults.Mode.NONE));
        }
    }

    private void run(ClientGameTestContext context, String address, BiomeRegistryFaults.Mode mode, String label, boolean bothSides) {
        context.runOnClient(client -> BiomeRegistryFaults.mode(mode));
        try (ExternalServerSession connection = ExternalServerSession.connect(new FabricTestClient(context), address)) {
            context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), 600);
            if (mode == BiomeRegistryFaults.Mode.NONE) {
                connection.command("whnested biome setup", "biome");
            }
            observe(context, mode, new Observation(label + "-front", 8.5D, 7.5D, 180F, 4, 10));
            if (bothSides) {
                observe(context, mode, new Observation(label + "-back", 8.5D, -7.5D, 0F, 4, 10));
                observe(context, mode, new Observation(label + "-return", 520.5D, 7.5D, 180F, 516, 522));
            }
            context.runOnClient(client -> {
                if (mode != BiomeRegistryFaults.Mode.NONE && BiomeRegistryFaults.renamed() == 0
                    || mode == BiomeRegistryFaults.Mode.INVALID_ONE && BiomeRegistryFaults.rejected() == 0) {
                    throw new AssertionError("Biome fault injection did not run for " + mode);
                }
            });
        }
    }

    private void observe(ClientGameTestContext context, BiomeRegistryFaults.Mode mode, Observation observation) {
        context.runOnClient(client -> client.getConnection().sendCommand("tp @s " + observation.x() + " 200 " + observation.z()
            + " " + observation.yaw() + " 0"));
        context.waitFor(client -> Math.abs(client.player.getX() - observation.x()) < 0.1D
            && Math.abs(client.player.getZ() - observation.z()) < 0.1D && Math.abs(client.player.getY() - 200D) < 0.1D, 400);
        context.waitTicks(40);
        context.waitFor(client -> ready(mode, observation), 1200);
        if (mode == BiomeRegistryFaults.Mode.INVALID_ONE) {
            context.waitTicks(100);
            context.waitFor(client -> ready(mode, observation), 400);
        }
        List<Integer> healthy = context.computeOnClient(client -> healthy(mode, observation));
        int resets = context.computeOnClient(client -> WormholesClient.instance().session().resets());
        context.runOnClient(client -> assertColors(mode, healthy));
        context.takeScreenshot("paper-biome-" + observation.label() + "-start");
        ClientPortalRenderer.NativeProof before = context.computeOnClient(client -> ClientPortalRenderer.instance().nativeProof());
        long started = System.nanoTime();
        int samples = 0;
        while (System.nanoTime() - started < OBSERVATION_NANOS) {
            context.waitTick();
            context.runOnClient(client -> {
                ClientViewSession session = WormholesClient.instance().session();
                if (!client.player.isAlive() || ClientGui.screen(client) != null) {
                    throw new AssertionError("Biome observation interrupted by player death or GUI: alive="
                        + client.player.isAlive() + " screen=" + ClientGui.screen(client));
                }
                if (!session.active() || session.resets() != resets || !ready(mode, observation)) {
                    throw new AssertionError("Biome portal availability changed during " + observation.label()
                        + ": active=" + session.active() + " resets=" + resets + "->" + session.resets()
                        + " portals=" + portalStates(observation) + " proof=" + ClientPortalRenderer.instance().nativeProof());
                }
                ClientPortalRenderer.NativeProof proof = ClientPortalRenderer.instance().nativeProof();
                if (proof.preparedFrame() - proof.lastTerrainFrame() > 3L) {
                    throw new AssertionError("Biome terrain stopped drawing: " + proof);
                }
            });
            if (++samples > 3000) {
                throw new AssertionError("Biome observation tick budget exceeded");
            }
        }
        context.takeScreenshot("paper-biome-" + observation.label() + "-end");
        ClientPortalRenderer.NativeProof after = context.computeOnClient(client -> ClientPortalRenderer.instance().nativeProof());
        if (after.terrainFrames() - before.terrainFrames() < 100) {
            throw new AssertionError("Biome terrain frames did not advance: " + before + " -> " + after);
        }
        LOGGER.info("[paper-biome] {} passed {}ms samples={} healthy={} resets={} before={} after={}", observation.label(),
            (System.nanoTime() - started) / 1_000_000L, samples, healthy, resets, before, after);
    }

    private String portalStates(Observation observation) {
        List<String> states = new ArrayList<>();
        for (ClientPortal portal : WormholesClient.instance().session().portals().values()) {
            if (selected(portal, observation)) {
                ClientMeshSections.View view = WormholesClient.instance().session().meshes().view(portal.portalKey());
                states.add(portal.portalKey() + ":available=" + ClientPortalRenderer.instance().available(portal.portalKey())
                    + ":sections=" + NativeClientViewAssertions.sections(portal.portalKey())
                    + ":generation=" + (view == null ? -1 : view.generation()));
            }
        }
        return states.toString();
    }

    private boolean ready(BiomeRegistryFaults.Mode mode, Observation observation) {
        ClientViewSession session = WormholesClient.instance().session();
        int found = 0;
        for (ClientPortal portal : session.portals().values()) {
            if (!selected(portal, observation)) {
                continue;
            }
            found++;
            boolean invalid = mode == BiomeRegistryFaults.Mode.INVALID_ONE && Math.abs(portal.geometry().originX() - 4) <= 1;
            if (NativeClientViewAssertions.sections(portal.portalKey()) == 0
                || ClientPortalRenderer.instance().available(portal.portalKey()) == invalid) {
                return false;
            }
        }
        return found >= 2 && ClientPortalRenderer.instance().nativeProof().terrainFrames() > 0;
    }

    private List<Integer> healthy(BiomeRegistryFaults.Mode mode, Observation observation) {
        List<Integer> keys = new ArrayList<>();
        for (ClientPortal portal : WormholesClient.instance().session().portals().values()) {
            if (selected(portal, observation) && (mode != BiomeRegistryFaults.Mode.INVALID_ONE
                || Math.abs(portal.geometry().originX() - 4) > 1)) {
                keys.add(portal.portalKey());
            }
        }
        return keys;
    }

    private boolean selected(ClientPortal portal, Observation observation) {
        return !portal.nested() && Math.abs(portal.geometry().originZ()) <= 1
            && (Math.abs(portal.geometry().originX() - observation.first()) <= 1
            || Math.abs(portal.geometry().originX() - observation.second()) <= 1);
    }

    private void assertColors(BiomeRegistryFaults.Mode mode, List<Integer> keys) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientViewSession session = WormholesClient.instance().session();
        String expectedKey = mode == BiomeRegistryFaults.Mode.NONE ? BIOME : BiomeRegistryFaults.PREFIX + BIOME.replace(':', '/');
        if (mode != BiomeRegistryFaults.Mode.NONE
            && minecraft.level.registryAccess().lookupOrThrow(Registries.BIOME).getOptional(Identifier.parse(expectedKey)).isPresent()) {
            throw new AssertionError("Controlled missing biome unexpectedly exists in the client registry");
        }
        for (int key : keys) {
            ClientMeshSections.View view = session.meshes().view(key);
            boolean matched = false;
            for (LongIterator iterator = view.sectionKeys().iterator(); iterator.hasNext() && !matched;) {
                long sectionKey = iterator.nextLong();
                SectionBiomes biomes = view.section(sectionKey).biomes();
                BiomeVisual visual = biomes.visuals().get(expectedKey);
                if (visual == null) {
                    continue;
                }
                if (visual.waterColor() != 0x123456 || visual.foliageColor() != 0x456789
                    || visual.dryFoliageColor() != 0x789ABC || visual.grassColor() != 0xABCDEF) {
                    throw new AssertionError("Server biome colors changed in transit: " + visual);
                }
                for (int y = 0; y < 16 && !matched; y += 4) {
                    for (int z = 0; z < 16 && !matched; z += 4) {
                        for (int x = 0; x < 16 && !matched; x += 4) {
                            if (!expectedKey.equals(biomes.biome(SectionBiomes.cell(x, y, z)))) {
                                continue;
                            }
                            ClientMeshWorld world = new ClientMeshWorld(new ClientMeshWorld.Snapshot(view, sectionKey,
                                minecraft.level.registryAccess(), session.environment(key), 0));
                            BlockPos position = SectionPos.of(sectionKey).origin().offset(x, y, z);
                            if (world.getBlockTint(position, (biome, px, pz) -> biome.getWaterColor()) != 0xFF123456
                                || world.getBlockTint(position, (biome, px, pz) -> biome.getFoliageColor()) != 0xFF456789
                                || world.getBlockTint(position, (biome, px, pz) -> biome.getDryFoliageColor()) != 0xFF789ABC
                                || world.getBlockTint(position, Biome::getGrassColor) != 0xFFABCDEF) {
                                throw new AssertionError("Native mesh tints differ from the authoritative biome");
                            }
                            matched = true;
                        }
                    }
                }
            }
            if (!matched) {
                throw new AssertionError("No custom biome tint sample found in portal " + key);
            }
        }
    }

    private record Observation(String label, double x, double z, float yaw, int first, int second) {}
}
