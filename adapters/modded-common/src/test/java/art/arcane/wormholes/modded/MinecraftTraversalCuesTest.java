package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewService;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.TraversalProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftTraversalCuesTest extends MinecraftTestBase {
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
        MinecraftTraversalCues.threshold(runtime, portal, new GeometryVector(1, 64, 2), null);
        verify(level).playSound(isNull(), eq(1.0D), eq(64.0D), eq(2.0D), any(SoundEvent.class),
            eq(SoundSource.BLOCKS), eq(0.3F), eq(1.3F));
    }
    @Test
    public void thresholdExcludesOnlyTheReadyRouteParticipantAndPreservesBroadcastForOthers() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        WormholesSettings settings = mock(WormholesSettings.class);
        MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
        MinecraftRules rules = mock(MinecraftRules.class);
        MinecraftClientViewService views = mock(MinecraftClientViewService.class);
        MinecraftPortal portal = mock(MinecraftPortal.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        UUID observer = UUID.randomUUID();
        UUID route = UUID.randomUUID();
        MainConfig main = new MainConfig();
        main.enableParticles = false;
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(settings);
        when(settings.getMain()).thenReturn(main);
        when(settings.getTransit()).thenReturn(new TransitConfig());
        when(runtime.portals()).thenReturn(portals);
        when(runtime.clientViews()).thenReturn(views);
        when(portals.resolveLevel(portal)).thenReturn(level);
        when(runtime.rules()).thenReturn(rules);
        when(rules.document(portal)).thenReturn(RuleDocument.EMPTY);
        when(portal.getType()).thenReturn(PortalType.PORTAL);
        when(portal.getId()).thenReturn(route);
        when(player.getUUID()).thenReturn(observer);
        when(views.seamlessTravel(observer, route)).thenReturn(true);
        MinecraftTraversalCues.threshold(runtime, portal, new GeometryVector(1, 64, 2), player);
        verify(level).playSound(eq(player), eq(1.0D), eq(64.0D), eq(2.0D), any(SoundEvent.class),
            eq(SoundSource.BLOCKS), eq(0.6F), eq(1.3F));
        when(views.seamlessTravel(observer, route)).thenReturn(false);
        MinecraftTraversalCues.threshold(runtime, portal, new GeometryVector(1, 64, 2), player);
        verify(level).playSound(isNull(), eq(1.0D), eq(64.0D), eq(2.0D), any(SoundEvent.class),
            eq(SoundSource.BLOCKS), eq(0.6F), eq(1.3F));
    }
}
