package art.arcane.wormholes;

import art.arcane.volmlib.util.event.ProtectionProbe;
import art.arcane.wormholes.door.DimensionalDoorManager;
import art.arcane.wormholes.nexus.DialGestures;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class ProtectionProbeInteractionTest {
    @Test
    void permissionChecksDoNotSelectCornersOpenMenusApplySkinsOrUnpackKits() throws ReflectiveOperationException {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        Block block = mock(Block.class);
        when(player.getInventory()).thenReturn(inventory);
        PlayerInteractEvent event = ProtectionProbe.blockInteract(player, block, EquipmentSlot.HAND);
        Event.Result blockUse = event.useInteractedBlock();
        clearInvocations(player, inventory, block);
        List<Method> handlers = List.of(
            WandSelectionManager.class.getMethod("on", PlayerInteractEvent.class),
            EffectManager.class.getMethod("on", PlayerInteractEvent.class),
            EffectManager.class.getMethod("onPortalMenuGesture", PlayerInteractEvent.class),
            PortalSkinListener.class.getMethod("on", PlayerInteractEvent.class),
            DimensionalDoorManager.class.getMethod("onPairKitUse", PlayerInteractEvent.class),
            DialGestures.class.getMethod("on", PlayerInteractEvent.class));

        for (Method handler : handlers) {
            handler.invoke(mock(handler.getDeclaringClass(), CALLS_REAL_METHODS), event);
        }

        verifyNoInteractions(player, inventory, block);
        assertEquals(blockUse, event.useInteractedBlock());
    }
}
