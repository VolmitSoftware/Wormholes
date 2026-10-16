package art.arcane.wormholes.portal;

import art.arcane.wormholes.TestRegistryAccess;
import art.arcane.wormholes.Settings;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;
import io.papermc.paper.registry.RegistryAccess;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ArrivalTransitionTravelTest {
    @Test
    void onlyAWorldReloadingArrivalIsMasked() {
        Player sameWorld = mock(Player.class);
        Player reloading = mock(Player.class);
        boolean enabled = Settings.ARRIVAL_TRANSITION_MASK;
        Settings.ARRIVAL_TRANSITION_MASK = true;
        try (MockedStatic<RegistryAccess> registries = mockStatic(RegistryAccess.class)) {
            registries.when(RegistryAccess::registryAccess).thenReturn(new TestRegistryAccess());
            ArrivalTransition.apply(sameWorld, false, 5);
            ArrivalTransition.apply(reloading, true, 5);
            verifyNoInteractions(sameWorld);
            verify(reloading).addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 5, 0, false, false, false));
        } finally {
            Settings.ARRIVAL_TRANSITION_MASK = enabled;
        }
    }
}
