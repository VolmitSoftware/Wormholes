package art.arcane.wormholes.render.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.ProjectionClaimArbiter;
import art.arcane.wormholes.render.ProjectorBlackoutDisplayRendererBedrockProbe;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;

final class BedrockProfileTest {
    @AfterEach
    void restore() {
        FidelitySettings.bedrockDisplayEntities = false;
        FidelitySettings.bedrockLightingFidelity = false;
        FidelitySettings.bedrockEntityCap = 8;
        ClientProfileService.install(null);
    }

    @Test
    void theBedrockProfileMirrorsTheConfigAndCapsTheJavaLimits() {
        FidelitySettings.bedrockDisplayEntities = false;
        FidelitySettings.bedrockLightingFidelity = false;
        FidelitySettings.bedrockEntityCap = 3;
        BedrockProfile profile = BedrockProfile.forBedrock();

        assertTrue(profile.bedrock());
        assertTrue(profile.withholdsDisplays());
        assertFalse(profile.lightingFidelity());
        assertEquals(3, profile.entityLimit(24));
        assertEquals(BedrockProfile.BLOCK_BATCH_LIMIT, profile.blockBatchLimit());

        assertFalse(BedrockProfile.JAVA.withholdsDisplays());
        assertTrue(BedrockProfile.JAVA.lightingFidelity());
        assertEquals(24, BedrockProfile.JAVA.entityLimit(24));
        assertEquals(Integer.MAX_VALUE, BedrockProfile.JAVA.blockBatchLimit());

        FidelitySettings.bedrockDisplayEntities = true;
        assertFalse(BedrockProfile.forBedrock().withholdsDisplays(), "operators can opt Bedrock viewers into display entities");
    }

    @Test
    void multiBlockChangeSectionsAreSplitToTheConservativeBatchSize() {
        WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks = new WrapperPlayServerMultiBlockChange.EncodedBlock[150];
        for (int index = 0; index < blocks.length; index++) {
            blocks[index] = new WrapperPlayServerMultiBlockChange.EncodedBlock(1, index & 15, 64 + (index >> 4), 0);
        }
        List<WrapperPlayServerMultiBlockChange.EncodedBlock[]> batches = ProjectionClaimArbiter.splitBatches(blocks, 64);
        assertEquals(3, batches.size());
        assertEquals(64, batches.get(0).length);
        assertEquals(64, batches.get(1).length);
        assertEquals(22, batches.get(2).length);
        assertEquals(1, ProjectionClaimArbiter.splitBatches(blocks, Integer.MAX_VALUE).size(), "Java viewers keep one packet per section");
    }

    @Test
    void theBlackoutDisplayRendererWithholdsPanelsFromBedrockViewers() {
        ClientProfileService.install(new ClientProfileService(player -> true, player -> null));
        FidelitySettings.bedrockDisplayEntities = false;
        try (ProjectorBlackoutDisplayRendererBedrockProbe probe = new ProjectorBlackoutDisplayRendererBedrockProbe()) {
            assertFalse(probe.prepareForViewer(ClientProfileServiceTest.player(UUID.randomUUID())),
                "a Bedrock viewer with display entities withheld never receives blackout panels");
            assertEquals(0, probe.packetsSent());

            FidelitySettings.bedrockDisplayEntities = true;
            ClientProfileService.install(new ClientProfileService(player -> true, player -> null));
            assertTrue(probe.prepareForViewer(ClientProfileServiceTest.player(UUID.randomUUID())));
        }
    }
}
