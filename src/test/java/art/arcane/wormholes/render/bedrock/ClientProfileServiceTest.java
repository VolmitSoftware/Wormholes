package art.arcane.wormholes.render.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.FidelitySettings;

final class ClientProfileServiceTest {
    private static final UUID JAVA_ID = UUID.fromString("8f3c1d2e-4b5a-4c6d-8e7f-90a1b2c3d4e5");
    private static final UUID FLOODGATE_SHAPED_ID = new UUID(0L, 0x1234567890L);

    @AfterEach
    void restore() {
        FidelitySettings.bedrockEnabled = true;
        ClientProfileService.install(null);
    }

    @Test
    void withoutFloodgateAJavaPlayerGetsTheJavaProfile() {
        ClientProfileService service = new ClientProfileService(player -> false, player -> null);
        BedrockProfile profile = service.profile(player(JAVA_ID));
        assertSame(BedrockProfile.JAVA, profile);
        assertFalse(profile.bedrock());
        assertEquals(0, service.bedrockViewers());
    }

    @Test
    void aFakeFloodgateSaysBedrockAndTheAnswerIsCachedUntilForgotten() {
        AtomicInteger probes = new AtomicInteger();
        ClientProfileService service = new ClientProfileService(player -> {
            probes.incrementAndGet();
            return true;
        }, player -> null);
        Player player = player(JAVA_ID);

        assertTrue(service.profile(player).bedrock());
        assertTrue(service.profile(player).bedrock());
        assertEquals(1, probes.get(), "detection runs once per player");
        assertEquals(1, service.bedrockViewers());

        service.forget(JAVA_ID);
        assertTrue(service.profile(player).bedrock());
        assertEquals(2, probes.get());
    }

    @Test
    void aReloadRedetectsEveryProfileSoNewCapsReachConnectedBedrockViewers() {
        AtomicInteger probes = new AtomicInteger();
        ClientProfileService service = new ClientProfileService(player -> {
            probes.incrementAndGet();
            return true;
        }, player -> null);
        ClientProfileService.install(service);
        Player player = player(JAVA_ID);

        assertTrue(service.profile(player).bedrock());
        assertEquals(1, probes.get());

        ClientProfileService.forgetAll();
        assertTrue(service.profile(player).bedrock());
        assertEquals(2, probes.get(), "a profile snapshots the fidelity caps, so a reload has to re-detect");
    }

    @Test
    void geyserBrandAndFloodgateShapedIdsCountAsBedrockAndTheGlobalSwitchDisablesDetection() {
        ClientProfileService brand = new ClientProfileService(player -> false, player -> "Geyser-Fabric");
        assertTrue(brand.profile(player(JAVA_ID)).bedrock());

        ClientProfileService shaped = new ClientProfileService(player -> false, player -> "vanilla");
        assertTrue(shaped.profile(player(FLOODGATE_SHAPED_ID)).bedrock(), "Floodgate players carry a zero-high-bits UUID");
        assertFalse(shaped.profile(player(JAVA_ID)).bedrock());

        FidelitySettings.bedrockEnabled = false;
        ClientProfileService disabled = new ClientProfileService(player -> true, player -> "Geyser");
        assertSame(BedrockProfile.JAVA, disabled.profile(player(FLOODGATE_SHAPED_ID)));
    }

    @Test
    void theStaticLookupFallsBackToJavaWhenNoServiceIsInstalled() {
        ClientProfileService.install(null);
        assertSame(BedrockProfile.JAVA, ClientProfileService.profileFor(player(JAVA_ID)));
        ClientProfileService.install(new ClientProfileService(player -> true, player -> null));
        assertTrue(ClientProfileService.profileFor(player(JAVA_ID)).bedrock());
        assertEquals(1, ClientProfileService.bedrockViewerCount());
    }

    static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "isOnline" -> Boolean.TRUE;
                case "getName", "toString" -> "player-" + id;
                case "hashCode" -> Integer.valueOf(id.hashCode());
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                default -> null;
            });
    }
}
