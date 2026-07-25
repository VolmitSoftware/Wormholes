package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public final class ProjectedEntityMetadataBridgeRetryTest {
    @Test
    public void metadataBridgeRecoversOnceTheRetryWindowElapses() {
        long failedAt = 1_000_000L;
        long retryAt = failedAt + EntityRenderMetadataBridge.METADATA_BRIDGE_RETRY_MILLIS;

        assertTrue(EntityRenderMetadataBridge.metadataBridgeAvailable(0L, failedAt));
        assertFalse(EntityRenderMetadataBridge.metadataBridgeAvailable(retryAt, failedAt + 1L));
        assertFalse(EntityRenderMetadataBridge.metadataBridgeAvailable(retryAt, retryAt - 1L));
        assertTrue(EntityRenderMetadataBridge.metadataBridgeAvailable(retryAt, retryAt));
        assertTrue(EntityRenderMetadataBridge.metadataBridgeAvailable(retryAt, retryAt + 3_600_000L));
    }
}
