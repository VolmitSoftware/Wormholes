package art.arcane.wormholes.portal.vanilla;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VanillaPortalEndExitTest {
    @Test
    void discoversAnActiveOpeningRetainsItsMaskAndRemovesItWhenTheDragonRespawns() throws Exception {
        PortalManager previousManager = Wormholes.portalManager;
        boolean previousReplacement = Settings.REPLACE_NETHER_AND_END_PORTALS;
        PortalManager manager = mock(PortalManager.class);
        World world = mock(World.class);
        Set<Block> cells = new HashSet<>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if ((Math.abs(x) == 2 && Math.abs(z) == 2) || (x == 0 && z == 0)) {
                    continue;
                }
                Block block = mock(Block.class);
                when(block.getWorld()).thenReturn(world);
                when(block.getX()).thenReturn(x);
                when(block.getY()).thenReturn(64);
                when(block.getZ()).thenReturn(z);
                cells.add(block);
            }
        }
        PortalStructure structure = new PortalStructure();
        structure.setBlocks(cells);
        ILocalPortal exit = mock(ILocalPortal.class);
        when(exit.getDimensionalPortalKind()).thenReturn(DimensionalPortalKind.END_EXIT);
        when(exit.getWorld()).thenReturn(world);
        when(exit.getStructure()).thenReturn(structure);
        Method reconcile = VanillaPortalEndExit.class.getDeclaredMethod("reconcile", World.class, Set.class);
        reconcile.setAccessible(true);
        try (MockedStatic<PortalFactory> factory = mockStatic(PortalFactory.class)) {
            Wormholes.portalManager = manager;
            Settings.REPLACE_NETHER_AND_END_PORTALS = true;
            when(manager.getLocalPortals()).thenReturn(List.of());
            reconcile.invoke(null, world, cells);
            factory.verify(() -> PortalFactory.createFromCells(eq(cells), any(PortalFrame.class), eq(PortalType.PORTAL),
                eq("End return"), eq(DimensionalPortalKind.END_EXIT)));
            factory.clearInvocations();
            when(manager.getLocalPortals()).thenReturn(List.of(exit));
            assertTrue(VanillaPortalEndExit.sameCells(exit, cells));
            assertFalse(structure.containsBlock(0, 64, 0));
            reconcile.invoke(null, world, cells);
            factory.verifyNoInteractions();
            verify(exit, never()).destroy();
            reconcile.invoke(null, world, Set.of());
            verify(exit).destroy();
            factory.verifyNoInteractions();
        } finally {
            Wormholes.portalManager = previousManager;
            Settings.REPLACE_NETHER_AND_END_PORTALS = previousReplacement;
        }
    }
}
