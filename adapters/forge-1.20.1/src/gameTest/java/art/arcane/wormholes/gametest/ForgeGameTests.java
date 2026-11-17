package art.arcane.wormholes.gametest;

import art.arcane.wormholes.modded.RuntimeBaselineEnvironment;
import art.arcane.wormholes.modded.WormholesGameTests;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Set;

// Forge resolves templates as <holder namespace>:<template>, so this names the same wormholes:empty structure the Fabric suite uses.
@GameTestHolder("wormholes")
@PrefixGameTestTemplate(false)
public final class ForgeGameTests {
    private static final String STRUCTURE = "empty";
    private static final Set<String> ONLY = Set.of(System.getProperty("wormholes.gametest.only", "").split(","));

    private static boolean skipped(GameTestHelper helper, String test) {
        if (ONLY.contains("") || ONLY.contains(test)) {
            return false;
        }
        helper.succeed();
        return true;
    }

    @BeforeBatch(batch = "portal_runtime")
    public void portalRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "portal_runtime", timeoutTicks = 3600, setupTicks = 5L)
    public void portalRuntime(GameTestHelper helper) {
        if (skipped(helper, "portalRuntime")) {
            return;
        }
        WormholesGameTests.portalRuntime(helper);
    }

    @AfterBatch(batch = "portal_runtime")
    public void portalRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "interaction_runtime")
    public void interactionRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "interaction_runtime", timeoutTicks = 1200, setupTicks = 5L)
    public void interactionRuntime(GameTestHelper helper) {
        if (skipped(helper, "interactionRuntime")) {
            return;
        }
        WormholesGameTests.interactionRuntime(helper);
    }

    @AfterBatch(batch = "interaction_runtime")
    public void interactionRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "handoff_runtime")
    public void handoffRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "handoff_runtime", timeoutTicks = 1200, setupTicks = 5L)
    public void handoffRuntime(GameTestHelper helper) {
        if (skipped(helper, "handoffRuntime")) {
            return;
        }
        WormholesGameTests.handoffRuntime(helper);
    }

    @AfterBatch(batch = "handoff_runtime")
    public void handoffRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "costs_runtime")
    public void costsRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "costs_runtime", timeoutTicks = 1200, setupTicks = 5L)
    public void costsRuntime(GameTestHelper helper) {
        if (skipped(helper, "costsRuntime")) {
            return;
        }
        WormholesGameTests.costsRuntime(helper);
    }

    @AfterBatch(batch = "costs_runtime")
    public void costsRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "rules_runtime")
    public void rulesRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "rules_runtime", timeoutTicks = 1200, setupTicks = 5L)
    public void rulesRuntime(GameTestHelper helper) {
        if (skipped(helper, "rulesRuntime")) {
            return;
        }
        WormholesGameTests.rulesRuntime(helper);
    }

    @AfterBatch(batch = "rules_runtime")
    public void rulesRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "language_runtime")
    public void languageRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "language_runtime", timeoutTicks = 1200, setupTicks = 5L)
    public void languageRuntime(GameTestHelper helper) {
        if (skipped(helper, "languageRuntime")) {
            return;
        }
        WormholesGameTests.languageRuntime(helper);
    }

    @AfterBatch(batch = "language_runtime")
    public void languageRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "ops_runtime")
    public void opsRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "ops_runtime", timeoutTicks = 600, setupTicks = 5L)
    public void opsRuntime(GameTestHelper helper) {
        if (skipped(helper, "opsRuntime")) {
            return;
        }
        WormholesGameTests.opsRuntime(helper);
    }

    @AfterBatch(batch = "ops_runtime")
    public void opsRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "recipe_book_runtime")
    public void recipeBookRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "recipe_book_runtime", timeoutTicks = 1200, setupTicks = 5L)
    public void recipeBookRuntime(GameTestHelper helper) {
        if (skipped(helper, "recipeBookRuntime")) {
            return;
        }
        WormholesGameTests.recipeBookRuntime(helper);
    }

    @AfterBatch(batch = "recipe_book_runtime")
    public void recipeBookRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "effects_runtime")
    public void effectsRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "effects_runtime", timeoutTicks = 600, setupTicks = 5L)
    public void effectsRuntime(GameTestHelper helper) {
        if (skipped(helper, "effectsRuntime")) {
            return;
        }
        WormholesGameTests.effectsRuntime(helper);
    }

    @AfterBatch(batch = "effects_runtime")
    public void effectsRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "look_label_runtime")
    public void lookLabelRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "look_label_runtime", timeoutTicks = 600, setupTicks = 5L)
    public void lookLabelRuntime(GameTestHelper helper) {
        if (skipped(helper, "lookLabelRuntime")) {
            return;
        }
        WormholesGameTests.lookLabelRuntime(helper);
    }

    @AfterBatch(batch = "look_label_runtime")
    public void lookLabelRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "entity_transfers_runtime")
    public void entityTransfersRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "entity_transfers_runtime", timeoutTicks = 1200, setupTicks = 5L)
    public void entityTransfersRuntime(GameTestHelper helper) {
        if (skipped(helper, "entityTransfersRuntime")) {
            return;
        }
        WormholesGameTests.entityTransfersRuntime(helper);
    }

    @AfterBatch(batch = "entity_transfers_runtime")
    public void entityTransfersRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "nexus_runtime")
    public void nexusRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "nexus_runtime", timeoutTicks = 1200, setupTicks = 5L)
    public void nexusRuntime(GameTestHelper helper) {
        if (skipped(helper, "nexusRuntime")) {
            return;
        }
        WormholesGameTests.nexusRuntime(helper);
    }

    @AfterBatch(batch = "nexus_runtime")
    public void nexusRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "rtp_runtime")
    public void rtpRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "rtp_runtime", timeoutTicks = 1200, setupTicks = 5L)
    public void rtpRuntime(GameTestHelper helper) {
        if (skipped(helper, "rtpRuntime")) {
            return;
        }
        WormholesGameTests.rtpRuntime(helper);
    }

    @AfterBatch(batch = "rtp_runtime")
    public void rtpRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "entity_projection_runtime")
    public void entityProjectionRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "entity_projection_runtime", timeoutTicks = 1200, setupTicks = 5L)
    public void entityProjectionRuntime(GameTestHelper helper) {
        if (skipped(helper, "entityProjectionRuntime")) {
            return;
        }
        WormholesGameTests.entityProjectionRuntime(helper);
    }

    @AfterBatch(batch = "entity_projection_runtime")
    public void entityProjectionRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "menu_parity_runtime")
    public void menuParityRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "menu_parity_runtime", timeoutTicks = 12000, setupTicks = 5L)
    public void menuParityRuntime(GameTestHelper helper) {
        if (skipped(helper, "menuParityRuntime")) {
            return;
        }
        WormholesGameTests.menuParityRuntime(helper);
    }

    @AfterBatch(batch = "menu_parity_runtime")
    public void menuParityRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "native_menu_protocol_runtime")
    public void nativeMenuProtocolRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "native_menu_protocol_runtime", timeoutTicks = 600, setupTicks = 5L)
    public void nativeMenuProtocolRuntime(GameTestHelper helper) {
        if (skipped(helper, "nativeMenuProtocolRuntime")) {
            return;
        }
        WormholesGameTests.nativeMenuProtocolRuntime(helper);
    }

    @AfterBatch(batch = "native_menu_protocol_runtime")
    public void nativeMenuProtocolRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "occlusion_skin_runtime")
    public void occlusionSkinRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "occlusion_skin_runtime", timeoutTicks = 200, setupTicks = 5L)
    public void occlusionSkinRuntime(GameTestHelper helper) {
        if (skipped(helper, "occlusionSkinRuntime")) {
            return;
        }
        WormholesGameTests.occlusionSkinRuntime(helper);
    }

    @AfterBatch(batch = "occlusion_skin_runtime")
    public void occlusionSkinRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "projection_dirt_runtime")
    public void projectionDirtRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "projection_dirt_runtime", timeoutTicks = 600, setupTicks = 5L)
    public void projectionDirtRuntime(GameTestHelper helper) {
        if (skipped(helper, "projectionDirtRuntime")) {
            return;
        }
        WormholesGameTests.projectionDirtRuntime(helper);
    }

    @AfterBatch(batch = "projection_dirt_runtime")
    public void projectionDirtRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "projection_gaze_runtime")
    public void projectionGazeRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "projection_gaze_runtime", timeoutTicks = 600, setupTicks = 5L)
    public void projectionGazeRuntime(GameTestHelper helper) {
        if (skipped(helper, "projectionGazeRuntime")) {
            return;
        }
        WormholesGameTests.projectionGazeRuntime(helper);
    }

    @AfterBatch(batch = "projection_gaze_runtime")
    public void projectionGazeRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "projection_retarget_runtime")
    public void projectionRetargetRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "projection_retarget_runtime", timeoutTicks = 600, setupTicks = 5L)
    public void projectionRetargetRuntime(GameTestHelper helper) {
        if (skipped(helper, "projectionRetargetRuntime")) {
            return;
        }
        WormholesGameTests.projectionRetargetRuntime(helper);
    }

    @AfterBatch(batch = "projection_retarget_runtime")
    public void projectionRetargetRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "projection_section_cache_runtime")
    public void projectionSectionCacheRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "projection_section_cache_runtime", timeoutTicks = 600, setupTicks = 5L)
    public void projectionSectionCacheRuntime(GameTestHelper helper) {
        if (skipped(helper, "projectionSectionCacheRuntime")) {
            return;
        }
        WormholesGameTests.projectionSectionCacheRuntime(helper);
    }

    @AfterBatch(batch = "projection_section_cache_runtime")
    public void projectionSectionCacheRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "projection_plate_capture_runtime")
    public void projectionPlateCaptureRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "projection_plate_capture_runtime", timeoutTicks = 600, setupTicks = 5L)
    public void projectionPlateCaptureRuntime(GameTestHelper helper) {
        if (skipped(helper, "projectionPlateCaptureRuntime")) {
            return;
        }
        WormholesGameTests.projectionPlateCaptureRuntime(helper);
    }

    @AfterBatch(batch = "projection_plate_capture_runtime")
    public void projectionPlateCaptureRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "clientview_negotiation")
    public void clientViewNegotiationSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "clientview_negotiation", timeoutTicks = 600, setupTicks = 5L)
    public void clientViewNegotiation(GameTestHelper helper) {
        if (skipped(helper, "clientViewNegotiation")) {
            return;
        }
        WormholesGameTests.clientViewNegotiation(helper);
    }

    @AfterBatch(batch = "clientview_negotiation")
    public void clientViewNegotiationTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "clientview_stream")
    public void clientViewStreamSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "clientview_stream", timeoutTicks = 600, setupTicks = 5L)
    public void clientViewStream(GameTestHelper helper) {
        if (skipped(helper, "clientViewStream")) {
            return;
        }
        WormholesGameTests.clientViewStream(helper);
    }

    @AfterBatch(batch = "clientview_stream")
    public void clientViewStreamTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "seamless_validation")
    public void seamlessValidationSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "seamless_validation", timeoutTicks = 4000, setupTicks = 5L)
    public void seamlessValidation(GameTestHelper helper) {
        if (skipped(helper, "seamlessValidation")) {
            return;
        }
        WormholesGameTests.seamlessValidation(helper);
    }

    @AfterBatch(batch = "seamless_validation")
    public void seamlessValidationTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "remote_view")
    public void remoteViewSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "remote_view", timeoutTicks = 4000, setupTicks = 5L)
    public void remoteView(GameTestHelper helper) {
        if (skipped(helper, "remoteView")) {
            return;
        }
        WormholesGameTests.remoteView(helper);
    }

    @AfterBatch(batch = "remote_view")
    public void remoteViewTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "aperture_shape_runtime")
    public void apertureShapeRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "aperture_shape_runtime", timeoutTicks = 600, setupTicks = 5L)
    public void apertureShapeRuntime(GameTestHelper helper) {
        if (skipped(helper, "apertureShapeRuntime")) {
            return;
        }
        WormholesGameTests.apertureShapeRuntime(helper);
    }

    @AfterBatch(batch = "aperture_shape_runtime")
    public void apertureShapeRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }

    @BeforeBatch(batch = "look_continuity_runtime")
    public void lookContinuityRuntimeSetup(ServerLevel level) {
        RuntimeBaselineEnvironment.setup(level);
    }

    @GameTest(template = STRUCTURE, batch = "look_continuity_runtime", timeoutTicks = 600, setupTicks = 5L)
    public void lookContinuityRuntime(GameTestHelper helper) {
        if (skipped(helper, "lookContinuityRuntime")) {
            return;
        }
        WormholesGameTests.lookContinuityRuntime(helper);
    }

    @AfterBatch(batch = "look_continuity_runtime")
    public void lookContinuityRuntimeTeardown(ServerLevel level) {
        RuntimeBaselineEnvironment.teardown(level);
    }
}
