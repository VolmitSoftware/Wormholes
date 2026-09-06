package art.arcane.wormholes.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.config.toml.NetworkConfig;

public final class TransferGateTest {
    @Test
    public void nativeTransferAcceptancePreservesTheTransferIntention() {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.autoAcceptTransfers = true;

        assertFalse(TransferGate.shouldRewrite(config, true));
        assertTrue(TransferGate.shouldRewrite(config, false));
    }

    @Test
    public void disabledAutomaticAcceptanceNeverChangesTheHandshake() {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.autoAcceptTransfers = false;

        assertFalse(TransferGate.shouldRewrite(config, false));
        config.autoAcceptTransfers = true;
        config.enabled = false;
        assertFalse(TransferGate.shouldRewrite(config, false));
        assertFalse(TransferGate.shouldRewrite(null, false));
    }
}
