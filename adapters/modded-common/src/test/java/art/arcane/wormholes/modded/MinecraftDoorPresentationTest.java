package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewService;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftDoorPresentationTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void nativeOwnershipHidesBackingRegardlessOfPacketProjectionPreference() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        WormholesSettings settings = new WormholesSettings(new MainConfig(), new ProjectionConfig(), new RenderConfig(), new NetworkConfig());
        MinecraftClientViewService clientViews = mock(MinecraftClientViewService.class);
        MinecraftProjectionService projections = mock(MinecraftProjectionService.class);
        ServerPlayer player = mock(ServerPlayer.class);
        UUID observer = UUID.randomUUID();
        UUID door = UUID.randomUUID();
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(settings);
        when(runtime.clientViews()).thenReturn(clientViews);
        when(runtime.projections()).thenReturn(projections);
        when(player.getUUID()).thenReturn(observer);
        MinecraftDoorPresentation presentation = new MinecraftDoorPresentation(runtime, mock(MinecraftDoorService.class));
        settings.getDoors().projectionHideBacking = false;
        when(projections.isDoorProjected(observer, door)).thenReturn(true);
        assertFalse(presentation.hideBacking(player, door));
        when(clientViews.nativeMesh(player)).thenReturn(true);
        assertFalse(presentation.hideBacking(player, door));
        when(clientViews.owns(observer, door)).thenReturn(true);
        assertTrue(presentation.hideBacking(player, door));
        assertFalse(presentation.hideBacking(player, UUID.randomUUID()));
        when(clientViews.nativeMesh(player)).thenReturn(false);
        assertFalse(presentation.hideBacking(player, door));
        settings.getDoors().projectionHideBacking = true;
        assertTrue(presentation.hideBacking(player, door));
    }
}
