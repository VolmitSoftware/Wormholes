package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.junit.Test;
import org.junit.BeforeClass;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftDiagnosticsTest {
    @BeforeClass
    public static void bootstrap() {
        MinecraftPortalToolsTest.bootstrap();
    }

    @Test
    public void dumpIncludesNativeStateAndSystemMeasurements() {
        String report = MinecraftDiagnostics.report("Portals: 12\n");
        assertTrue(report.contains("Portals: 12"));
        assertTrue(report.contains("Heap maximum bytes:"));
        assertTrue(report.contains("Live threads:"));
        assertTrue(report.contains("Java:"));
    }

    @Test
    public void sessionToggleResetsWhenConfigurationReloads() throws Exception {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        WormholesSettings settings = mock(WormholesSettings.class);
        MinecraftAccessService access = mock(MinecraftAccessService.class);
        MinecraftLocalization localization = mock(MinecraftLocalization.class);
        MinecraftStatsSnapshots stats = mock(MinecraftStatsSnapshots.class);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(settings);
        when(settings.getMain()).thenReturn(new MainConfig());
        when(runtime.access()).thenReturn(access);
        when(runtime.localization()).thenReturn(localization);
        when(localization.text(any(), any(), any())).thenReturn(Component.empty());
        when(access.permission(any(CommandSourceStack.class), anyString())).thenReturn(true);
        when(stats.capture()).thenReturn("Portals: 1\n");
        MinecraftDiagnostics diagnostics = new MinecraftDiagnostics(runtime, stats);
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(Commands.literal("wormholes").then(diagnostics.commands()));
        diagnostics.tick();
        verify(stats, never()).capture();
        dispatcher.execute("wormholes debug toggle", source);
        diagnostics.tick();
        verify(stats).capture();
        when(settings.getMain()).thenReturn(new MainConfig());
        diagnostics.tick();
        verify(stats).capture();
        diagnostics.close();
    }
}
