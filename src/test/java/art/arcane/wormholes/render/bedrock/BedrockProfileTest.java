package art.arcane.wormholes.render.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.optics.fidelity.FidelityOptions;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.ProjectionClaimArbiter;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import art.arcane.optics.fidelity.BedrockProfile;

final class BedrockProfileTest {
    @AfterEach
    void restore() {
        ClientProfileService.install(null);
    }

    @Test
    void theBedrockProfileMirrorsTheConfigAndCapsTheJavaLimits() {
        BedrockProfile profile = BedrockProfile.forBedrock(new FidelityOptions(0.005D, 0.6D, true, false, false, 3, 24.0D, 8));

        assertTrue(profile.bedrock());
        assertTrue(profile.withholdsDisplays());
        assertFalse(profile.lightingFidelity());
        assertEquals(3, profile.entityLimit(24));
        assertEquals(BedrockProfile.BLOCK_BATCH_LIMIT, profile.blockBatchLimit());

        assertFalse(BedrockProfile.JAVA.withholdsDisplays());
        assertTrue(BedrockProfile.JAVA.lightingFidelity());
        assertEquals(24, BedrockProfile.JAVA.entityLimit(24));
        assertEquals(Integer.MAX_VALUE, BedrockProfile.JAVA.blockBatchLimit());

        assertFalse(BedrockProfile.forBedrock(new FidelityOptions(0.005D, 0.6D, true, true, false, 3, 24.0D, 8)).withholdsDisplays(),
            "operators can opt Bedrock viewers into display entities");
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
}
