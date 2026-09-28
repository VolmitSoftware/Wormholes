package art.arcane.wormholes.render;

import art.arcane.wormholes.render.bedrock.BedrockProfile;
import art.arcane.wormholes.render.bedrock.ClientProfiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientProfilesTest {
    @AfterEach
    void restore() {
        FidelitySettings.bedrockEnabled = true;
        FidelitySettings.bedrockDisplayEntities = false;
        FidelitySettings.bedrockLightingFidelity = false;
        FidelitySettings.bedrockEntityCap = 8;
    }

    @Test
    void classificationUsesProviderThenBrandThenUuidAndCachesUntilForgotten() {
        AtomicBoolean provider = new AtomicBoolean();
        AtomicInteger probes = new AtomicInteger();
        ClientProfiles<Viewer> profiles = new ClientProfiles<>(new ClientProfiles.Options<>(viewer -> {
            probes.incrementAndGet();
            return provider.get();
        }, Viewer::brand, Viewer::id));
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
        ClientProfiles<Viewer> profiles = new ClientProfiles<>(new ClientProfiles.Options<>(viewer -> true, Viewer::brand, Viewer::id));
        Viewer viewer = new Viewer(UUID.randomUUID(), "vanilla");
        BedrockProfile first = profiles.profile(viewer);
        assertTrue(first.withholdsDisplays());
        assertFalse(first.lightingFidelity());
        assertEquals(8, first.entityLimit(48));
        assertEquals(64, first.blockBatchLimit());
        FidelitySettings.bedrockDisplayEntities = true;
        FidelitySettings.bedrockLightingFidelity = true;
        FidelitySettings.bedrockEntityCap = 3;
        profiles.clear();
        assertFalse(profiles.profile(viewer).withholdsDisplays());
        assertTrue(profiles.profile(viewer).lightingFidelity());
        assertEquals(3, profiles.profile(viewer).entityLimit(48));
        FidelitySettings.bedrockEnabled = false;
        assertEquals(BedrockProfile.JAVA, profiles.profile(viewer));
    }

    @Test
    void clientDistanceCapsPreserveServerFallbackAndMinimumTwoChunks() {
        assertEquals(32.0, ProjectorFrustumFit.capDistance(128, 8, 2));
        assertEquals(64.0, ProjectorFrustumFit.capDistance(128, 4, 12));
        assertEquals(96.0, ProjectorFrustumFit.capDistance(128, 6, 0));
        assertEquals(32.0, ProjectorFrustumFit.capDistance(128, 1, 1));
        assertEquals(8.0, ProjectorFrustumFit.capDistance(8, 8, 8));
    }

    private record Viewer(UUID id, String brand) { }
}
