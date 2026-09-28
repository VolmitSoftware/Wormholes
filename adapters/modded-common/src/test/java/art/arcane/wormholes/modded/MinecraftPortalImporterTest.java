package art.arcane.wormholes.modded;

import art.arcane.wormholes.ops.importers.ImportedPortal;
import art.arcane.wormholes.ops.importers.PortalFactoryBridge;
import art.arcane.wormholes.util.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPortalImporterTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void importedPortalUsesNativeRegistryAndOnlyLinksCreatedNames() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        MinecraftPortal portal = mock(MinecraftPortal.class);
        UUID id = UUID.randomUUID();
        when(runtime.server()).thenReturn(server);
        when(runtime.portals()).thenReturn(registry);
        when(server.isSameThread()).thenReturn(true);
        when(server.getAllLevels()).thenReturn(List.of(level));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(320);
        when(level.getWorldBorder()).thenReturn(new WorldBorder());
        when(registry.create(any(), eq(level), any(), any(), any())).thenReturn(portal);
        when(portal.getId()).thenReturn(id);
        when(registry.get(id)).thenReturn(portal);
        MinecraftPortalImporter importer = new MinecraftPortalImporter(new MinecraftPortalImporter.Options(runtime, UUID.randomUUID(), () -> true));
        PortalFactoryBridge.CreateResult result = importer.create(new ImportedPortal("Gate", "minecraft:overworld", 1, 70, 2, Direction.N, 2, 3, ""));
        assertTrue(result.ok());
        assertEquals(id, result.portalId());
        verify(portal).setName("Gate");
        assertFalse(importer.link(id, "Missing"));
        assertTrue(importer.link(id, "Gate"));
        verify(portal).link(portal);
    }

    @Test
    public void staleImportCannotMutateRestartedRuntime() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(runtime.server()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);
        MinecraftPortalImporter importer = new MinecraftPortalImporter(new MinecraftPortalImporter.Options(runtime, null, () -> false));
        assertFalse(importer.create(new ImportedPortal("Gate", "minecraft:overworld", 1, 70, 2, Direction.N, 2, 3, "")).ok());
        verify(runtime, never()).portals();
    }
}
