package art.arcane.wormholes.modded;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.Assert.assertEquals;
import java.util.UUID;
import net.minecraft.network.chat.Component;

public class MinecraftOperationsTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void registersPublicAndAdministrativeCommands() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        new MinecraftOperations(mock(WormholesModRuntime.class)).registerCommands(dispatcher);
        for (String command : List.of("help", "info", "version", "stats", "debug")) {
            assertNotNull(dispatcher.findNode(List.of("wormholes", command)));
        }
        for (String command : List.of("freeze", "flush", "deleteallportals", "deleteeverything", "backup", "portals")) {
            assertNotNull(dispatcher.findNode(List.of("wormholes", "admin", command)));
        }
        for (String command : List.of("now", "list", "export", "restore", "import", "import-from")) {
            assertNotNull(dispatcher.findNode(List.of("wormholes", "admin", "backup", command)));
        }
        for (String command : List.of("list", "find", "info", "tp", "retarget", "prune", "rename-server")) {
            assertNotNull(dispatcher.findNode(List.of("wormholes", "admin", "portals", command)));
        }
    }
    @Test
    public void pruneRequiresExplicitMutationAndConfirmation() throws Exception {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        MinecraftAccessService access = mock(MinecraftAccessService.class);
        MinecraftLocalization localization = mock(MinecraftLocalization.class);
        MinecraftPortal portal = mock(MinecraftPortal.class);
        CommandSourceStack source = mock(CommandSourceStack.class);
        UUID id = UUID.randomUUID();
        when(runtime.portals()).thenReturn(registry);
        when(runtime.access()).thenReturn(access);
        when(runtime.localization()).thenReturn(localization);
        when(access.permission(any(CommandSourceStack.class), anyString())).thenReturn(true);
        when(localization.text(any(), any(), any())).thenReturn(Component.empty());
        when(portal.getId()).thenReturn(id);
        when(portal.getName()).thenReturn("Orphan");
        when(portal.getDestinationId()).thenReturn(UUID.randomUUID());
        when(registry.snapshot()).thenReturn(List.of(portal));
        when(registry.get(id)).thenReturn(portal);
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        new MinecraftOperations(runtime).registerCommands(dispatcher);
        assertEquals(1, dispatcher.execute("wormholes admin portals prune", source));
        assertEquals(0, dispatcher.execute("wormholes admin portals prune false", source));
        verify(portal, never()).unlink();
        verify(registry, never()).save(any());
        assertEquals(1, dispatcher.execute("wormholes admin portals prune false true", source));
        verify(portal).unlink();
        verify(registry).save(portal);
    }

    @Test
    public void projectionMaintenanceUsesConfiguredPermission() throws Exception {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftAccessService access = mock(MinecraftAccessService.class);
        MinecraftLocalization localization = mock(MinecraftLocalization.class);
        MinecraftProjectionService projections = mock(MinecraftProjectionService.class);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(runtime.access()).thenReturn(access);
        when(runtime.localization()).thenReturn(localization);
        when(runtime.projections()).thenReturn(projections);
        when(access.permission(source, "wormholes.admin.projection")).thenReturn(true);
        when(localization.text(any(), any(), any())).thenReturn(Component.empty());
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        new MinecraftOperations(runtime).registerCommands(dispatcher);
        dispatcher.execute("wormholes admin freeze 1", source);
        verify(projections).freeze(5);
        dispatcher.execute("wormholes admin freeze 0", source);
        verify(projections).freeze(0);
        dispatcher.execute("wormholes admin flush", source);
        verify(projections).flush();
    }
}
