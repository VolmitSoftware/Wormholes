package art.arcane.wormholes.portal;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.util.M;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

class LocalPortalEffectsSoundTest {
    @Test
    void ambientTicksDoNotAccessSoundOrWorldStateWithParticlesDisabled() {
        boolean particlesEnabled = Settings.ENABLE_PARTICLES;
        Settings.ENABLE_PARTICLES = false;
        LocalPortal portal = mock(LocalPortal.class, invocation -> {
            throw new AssertionError("Ambient sound accessed " + invocation.getMethod().getName());
        });
        try (MockedStatic<M> random = mockStatic(M.class)) {
            random.when(() -> M.r(0.01D)).thenReturn(true);
            LocalPortalEffects effects = new LocalPortalEffects(portal);
            effects.playEffect(PortalEffect.AMBIENT_OPEN, null);
            effects.playEffect(PortalEffect.AMBIENT_CLOSED, null);
            verifyNoInteractions(portal);
        } finally {
            Settings.ENABLE_PARTICLES = particlesEnabled;
        }
    }
}
