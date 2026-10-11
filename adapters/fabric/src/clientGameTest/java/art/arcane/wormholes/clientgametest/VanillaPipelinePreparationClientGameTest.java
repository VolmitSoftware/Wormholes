package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.clientgametest.VanillaPipelinePreparationTap.Counts;
import art.arcane.wormholes.clientgametest.VanillaPipelinePreparationTap.Snapshot;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;

import java.util.concurrent.CompletableFuture;

public final class VanillaPipelinePreparationClientGameTest implements FabricClientGameTest {
    private static final int TIMEOUT_TICKS = 600;
    private static final int MIN_ADVANCING_FRAMES = 8;

    @Override
    public void runTest(ClientGameTestContext context) {
        VanillaPipelinePreparationTap.require(!FabricLoader.getInstance().isModLoaded("sodium")
            && !FabricLoader.getInstance().isModLoaded("iris"), "fixed vanilla acceptance requires --renderer vanilla without Iris or Sodium");
        Options original = context.computeOnClient(client -> new Options(client.options.textureFiltering().get(),
            client.options.maxAnisotropyBit().get(), client.options.ambientOcclusion().get()));
        try {
            context.waitFor(client -> VanillaPipelinePreparationTap.ready(), TIMEOUT_TICKS);
            awaitFrames(context, "initial-native-resource-warm");
            Snapshot initial = context.computeOnClient(VanillaPipelinePreparationTap::initialSnapshot);
            context.runOnClient(client -> VanillaPipelinePreparationTap.verifyGetters(initial, "initial-native-resource-warm"));
            exerciseSettings(context, initial);
            context.runOnClient(client -> {
                ClientPortalRenderer.instance().clear();
                VanillaPipelinePreparationTap.require(VanillaPipelinePreparationTap.renderer().portalCount() == 0,
                    "scene clear retained portal scenes");
                VanillaPipelinePreparationTap.verifyGetters(initial, "scene-clear");
                VanillaPipelinePreparationTap.require(WormholesClient.instance() != null, "client session runtime is absent");
                WormholesClient.instance().disconnected();
                VanillaPipelinePreparationTap.require(WormholesClient.instance().portalViews().current().isEmpty(),
                    "disconnect-equivalent reset retained portal views");
                VanillaPipelinePreparationTap.verifyGetters(initial, "disconnect-equivalent-reset");
            });
            awaitFrames(context, "after-scene-and-session-reset");
            long completionsBefore = context.computeOnClient(client -> VanillaPipelinePreparationTap.resourceCompletions());
            Counts costBefore = context.computeOnClient(client -> VanillaPipelinePreparationTap.counts());
            CompletableFuture<Void> reload = context.computeOnClient(client -> {
                VanillaPipelinePreparationTap.require(client.level == null && client.player == null
                    && VanillaPipelinePreparationTap.renderer().portalCount() == 0, "actual resource reload requires no world or portal");
                return client.reloadResourcePacks();
            });
            context.waitFor(client -> reload.isDone(), TIMEOUT_TICKS);
            context.runOnClient(client -> reload.join());
            context.waitFor(client -> VanillaPipelinePreparationTap.ready()
                && VanillaPipelinePreparationTap.renderer().bank() != initial.bank(), TIMEOUT_TICKS);
            awaitFrames(context, "after-actual-resource-reload");
            context.runOnClient(client -> {
                Snapshot replacement = VanillaPipelinePreparationTap.initialSnapshot(client);
                VanillaPipelinePreparationTap.verifyReload(initial, replacement, completionsBefore, costBefore);
                VanillaPipelinePreparationTap.verifyGetters(replacement, "after-actual-resource-reload");
            });
            System.out.println("WORMHOLES_VANILLA_PIPELINE_PREPARATION_PASS fixedVariants=16 terrainVariants=12 auxiliaryVariants=4"
                + " nativeGetterCompiles=0 nativeGetterLinks=0 actualResourceReload=true hiddenRenderer=true"
                + " windowVisible=false windowFocused=false mouseGrabbed=false framesAdvance=true"
                + " scope=fixed-vanilla-bank");
        } finally {
            try {
                context.runOnClient(client -> restore(client, original));
            } finally {
                context.runOnClient(client -> VanillaPipelinePreparationTap.finish());
            }
        }
    }

    private static void exerciseSettings(ClientGameTestContext context, Snapshot bank) {
        for (TextureFilteringMethod method : new TextureFilteringMethod[]{TextureFilteringMethod.NONE,
            TextureFilteringMethod.RGSS, TextureFilteringMethod.NONE, TextureFilteringMethod.ANISOTROPIC}) {
            context.runOnClient(client -> client.options.textureFiltering().set(method));
            context.waitTicks(2);
            context.runOnClient(client -> {
                VanillaPipelinePreparationTap.require(client.options.textureFiltering().get() == method, "native filtering option did not change");
                VanillaPipelinePreparationTap.verifyGetters(bank, "filtering-" + method);
            });
        }
        for (int anisotropyBit : new int[]{1, 2, 1}) {
            context.runOnClient(client -> client.options.maxAnisotropyBit().set(anisotropyBit));
            context.waitTicks(2);
            context.runOnClient(client -> {
                VanillaPipelinePreparationTap.require(client.options.maxAnisotropyBit().get() == anisotropyBit,
                    "native anisotropic filtering option did not change");
                VanillaPipelinePreparationTap.verifyGetters(bank, "anisotropy-bit-" + anisotropyBit);
            });
        }
        for (boolean ambientOcclusion : new boolean[]{false, true, false}) {
            context.runOnClient(client -> client.options.ambientOcclusion().set(ambientOcclusion));
            context.waitTicks(2);
            context.runOnClient(client -> {
                VanillaPipelinePreparationTap.require(client.options.ambientOcclusion().get() == ambientOcclusion,
                    "native ambient occlusion option did not change");
                VanillaPipelinePreparationTap.verifyGetters(bank, "ambient-occlusion-" + ambientOcclusion);
            });
        }
    }

    private static void awaitFrames(ClientGameTestContext context, String phase) {
        long before = context.computeOnClient(client -> {
            VanillaPipelinePreparationTap.assertHidden(client);
            return VanillaPipelinePreparationTap.frames();
        });
        context.waitFor(client -> VanillaPipelinePreparationTap.frames() >= before + MIN_ADVANCING_FRAMES, TIMEOUT_TICKS);
        context.runOnClient(client -> {
            VanillaPipelinePreparationTap.assertHidden(client);
            System.out.println("Vanilla hidden GPU phase=" + phase + " advancingFrames=" + (VanillaPipelinePreparationTap.frames() - before)
                + " hiddenRenderer=true windowVisible=false windowFocused=false mouseGrabbed=false");
        });
    }

    private static void restore(Minecraft client, Options original) {
        client.options.textureFiltering().set(original.filtering());
        client.options.maxAnisotropyBit().set(original.anisotropyBit());
        client.options.ambientOcclusion().set(original.ambientOcclusion());
    }

    private record Options(TextureFilteringMethod filtering, int anisotropyBit, boolean ambientOcclusion) {
    }
}
