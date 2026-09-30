package art.arcane.wormholes.gametest;

import art.arcane.wormholes.modded.WormholesGameTests;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunctionLoader;
import net.minecraft.resources.ResourceKey;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class FabricTestBootstrap implements TestFunctionLoader, PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        TestFunctionLoader.registerLoader(this);
    }

    @Override
    public void load(BiConsumer<ResourceKey<Consumer<GameTestHelper>>, Consumer<GameTestHelper>> register) {
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.PORTAL_RUNTIME), WormholesGameTests::portalRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.INTERACTION_RUNTIME), WormholesGameTests::interactionRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.HANDOFF_RUNTIME), WormholesGameTests::handoffRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.COSTS_RUNTIME), WormholesGameTests::costsRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.RULES_RUNTIME), WormholesGameTests::rulesRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.LANGUAGE_RUNTIME), WormholesGameTests::languageRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.OPS_RUNTIME), WormholesGameTests::opsRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.RECIPE_BOOK_RUNTIME), WormholesGameTests::recipeBookRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.EFFECTS_RUNTIME), WormholesGameTests::effectsRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.LOOK_LABEL_RUNTIME), WormholesGameTests::lookLabelRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.ENTITY_TRANSFERS_RUNTIME), WormholesGameTests::entityTransfersRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.NEXUS_RUNTIME), WormholesGameTests::nexusRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.RTP_RUNTIME), WormholesGameTests::rtpRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.ENTITY_PROJECTION_RUNTIME), WormholesGameTests::entityProjectionRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.MENU_PARITY_RUNTIME), WormholesGameTests::menuParityRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.OCCLUSION_SKIN_RUNTIME), WormholesGameTests::occlusionSkinRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.PROJECTION_DIRT_RUNTIME), WormholesGameTests::projectionDirtRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.PROJECTION_GAZE_RUNTIME), WormholesGameTests::projectionGazeRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.PROJECTION_RETARGET_RUNTIME), WormholesGameTests::projectionRetargetRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.PROJECTION_SECTION_CACHE_RUNTIME), WormholesGameTests::projectionSectionCacheRuntime);
        register.accept(ResourceKey.create(Registries.TEST_FUNCTION, WormholesGameTests.PROJECTION_PLATE_CAPTURE_RUNTIME), WormholesGameTests::projectionPlateCaptureRuntime);
    }
}
