package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.TraversalProfile;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftTraversalCuesTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void thresholdScalesSoundByPortalProfileAndGlobalVolume() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        WormholesSettings settings = mock(WormholesSettings.class);
        MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
        MinecraftRules rules = mock(MinecraftRules.class);
        MinecraftPortal portal = mock(MinecraftPortal.class);
        ServerLevel level = mock(ServerLevel.class);
        MainConfig main = new MainConfig();
        main.enableParticles = false;
        main.portalSoundVolumeMultiplier = 2.0D;
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(settings);
        when(settings.getMain()).thenReturn(main);
        when(settings.getTransit()).thenReturn(new TransitConfig());
        when(runtime.portals()).thenReturn(portals);
        when(portals.resolveLevel(portal)).thenReturn(level);
        when(runtime.rules()).thenReturn(rules);
        when(rules.document(portal)).thenReturn(RuleDocument.EMPTY.withProfile(new TraversalProfile(0, "", 0, 1, 0.25D, 0, 0)));
        when(portal.getType()).thenReturn(PortalType.PORTAL);
        MinecraftTraversalCues.threshold(runtime, portal, new GeometryVector(1, 64, 2));
        verify(level).playSound(isNull(), eq(1.0D), eq(64.0D), eq(2.0D), any(SoundEvent.class),
            eq(SoundSource.BLOCKS), eq(0.3F), eq(1.3F));
    }
}
