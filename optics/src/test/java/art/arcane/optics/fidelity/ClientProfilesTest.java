package art.arcane.optics.fidelity;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import art.arcane.optics.volume.FrustumFit;

class ClientProfilesTest {
    private static final FidelityOptions DEFAULTS = new FidelityOptions(0.005D, 0.6D, true, false, false, 8, 24.0D, 8);

    @Test
    void classificationUsesProviderThenBrandThenUuidAndCachesUntilForgotten() {
        AtomicBoolean provider = new AtomicBoolean();
        AtomicInteger probes = new AtomicInteger();
        ClientProfiles<Viewer> profiles = new ClientProfiles<>(new ClientProfiles.Options<>(viewer -> {
            probes.incrementAndGet();
            return provider.get();
        }, Viewer::brand, Viewer::id, () -> DEFAULTS));
        Viewer java = new Viewer(UUID.randomUUID(), "vanilla");
        assertFalse(profiles.profile(java).bedrock());
        provider.set(true);
        assertFalse(profiles.profile(java).bedrock());
        assertEquals(1, probes.get());
        profiles.forget(java.id());
        assertTrue(profiles.profile(java).bedrock());
        provider.set(false);
        assertTrue(profiles.profile(new Viewer(UUID.randomUUID(), "Geyser-fabric")).bedrock());
        assertTrue(profiles.profile(new Viewer(new UUID(0L, 42L), "vanilla")).bedrock());
        assertEquals(3, profiles.bedrockViewers());
        profiles.clear();
        assertEquals(0, profiles.bedrockViewers());
    }

    @Test
    void reloadRefreshesViewerCapsAndDisablingDetectionRestoresJavaChannels() {
        AtomicReference<FidelityOptions> fidelity = new AtomicReference<FidelityOptions>(DEFAULTS);
        ClientProfiles<Viewer> profiles = new ClientProfiles<>(new ClientProfiles.Options<>(viewer -> true, Viewer::brand, Viewer::id,
            fidelity::get));
        Viewer viewer = new Viewer(UUID.randomUUID(), "vanilla");
        BedrockProfile first = profiles.profile(viewer);
        assertTrue(first.withholdsDisplays());
        assertFalse(first.lightingFidelity());
        assertEquals(8, first.entityLimit(48));
        assertEquals(64, first.blockBatchLimit());
        fidelity.set(new FidelityOptions(0.005D, 0.6D, true, true, true, 3, 24.0D, 8));
        profiles.clear();
        assertFalse(profiles.profile(viewer).withholdsDisplays());
        assertTrue(profiles.profile(viewer).lightingFidelity());
        assertEquals(3, profiles.profile(viewer).entityLimit(48));
        fidelity.set(new FidelityOptions(0.005D, 0.6D, false, true, true, 3, 24.0D, 8));
        assertEquals(BedrockProfile.JAVA, profiles.profile(viewer));
    }

    @Test
    void clientDistanceCapsPreserveServerFallbackAndMinimumTwoChunks() {
        assertEquals(32.0, FrustumFit.capDistance(128, 8, 2));
        assertEquals(64.0, FrustumFit.capDistance(128, 4, 12));
        assertEquals(96.0, FrustumFit.capDistance(128, 6, 0));
        assertEquals(32.0, FrustumFit.capDistance(128, 1, 1));
        assertEquals(8.0, FrustumFit.capDistance(8, 8, 8));
    }

    private record Viewer(UUID id, String brand) { }
}
