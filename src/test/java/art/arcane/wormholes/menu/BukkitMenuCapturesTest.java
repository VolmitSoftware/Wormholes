package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.service.WormholesAudience;

class BukkitMenuCapturesTest {
    private final UUID viewerId = UUID.randomUUID();
    private final AtomicReference<Consumer<String>> chat = new AtomicReference<>();
    private final List<String> submitted = new ArrayList<>();
    private final AtomicInteger cancelled = new AtomicInteger();
    private final List<CapturedItem> captured = new ArrayList<>();
    private Player player;
    private BukkitMenuViewer viewer;
    private BukkitMenuCaptures captures;
    private MockedStatic<Wormholes> wormholes;
    private MockedStatic<WormholesAudience> audience;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(viewerId);
        viewer = new BukkitMenuViewer(player);
        captures = new BukkitMenuCaptures();
        audience = mockStatic(WormholesAudience.class);
        wormholes = mockStatic(Wormholes.class, CALLS_REAL_METHODS);
        wormholes.when(() -> Wormholes.awaitChatInput(eq(player), any())).thenAnswer(invocation -> {
            chat.set(invocation.getArgument(1));
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        wormholes.close();
        audience.close();
    }

    @Test
    void chatAnswersSubmitUnlessTheyAreTheCancelWord() {
        String cancelWord = Wormholes.text().plain(WormholesMessages.PORTAL_INPUT_CANCEL);

        captures.prompt(viewer, List.of("Type a name."), submitted::add, cancelled::incrementAndGet);
        chat.get().accept("Gamma");
        captures.prompt(viewer, List.of("Type a name."), submitted::add, cancelled::incrementAndGet);
        chat.get().accept(cancelWord.toUpperCase());
        captures.prompt(viewer, List.of("Type a name."), submitted::add, cancelled::incrementAndGet);
        chat.get().accept(null);

        assertEquals(List.of("Gamma"), submitted);
        assertEquals(2, cancelled.get());
    }

    @Test
    void dropsAreCancelledAndCapturedAsASingleItemCopy() {
        ItemStack dropped = mock(ItemStack.class);
        ItemStack single = mock(ItemStack.class);
        when(dropped.clone()).thenReturn(single);
        when(single.getType()).thenReturn(Material.DIAMOND);
        captures.dropItem(viewer, List.of("Drop the item."), captured::add, cancelled::incrementAndGet);

        PlayerDropItemEvent event = drop(dropped);
        captures.on(event);

        assertTrue(event.isCancelled());
        assertEquals(1, captured.size());
        assertEquals("minecraft:diamond", captured.getFirst().itemId());
        verify(single).setAmount(1);
        verify(dropped, never()).setAmount(anyInt());
    }

    @Test
    void aNewCaptureCancelsThePreviousOne() {
        List<CapturedItem> first = new ArrayList<>();
        ItemStack dropped = mock(ItemStack.class);
        ItemStack single = mock(ItemStack.class);
        when(dropped.clone()).thenReturn(single);
        when(single.getType()).thenReturn(Material.EMERALD);
        captures.dropItem(viewer, List.of("Drop the item."), first::add, cancelled::incrementAndGet);

        captures.dropItem(viewer, List.of("Drop the item."), captured::add, () -> {
        });
        captures.on(drop(dropped));

        assertEquals(1, cancelled.get());
        assertTrue(first.isEmpty());
        assertEquals(1, captured.size());
    }

    @Test
    void quitDropsAPendingCapture() {
        captures.dropItem(viewer, List.of("Drop the item."), captured::add, cancelled::incrementAndGet);
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);

        captures.on(quit);
        PlayerDropItemEvent event = drop(mock(ItemStack.class));
        captures.on(event);

        assertFalse(event.isCancelled());
        assertTrue(captured.isEmpty());
    }

    private PlayerDropItemEvent drop(ItemStack stack) {
        Item item = mock(Item.class);
        when(item.getItemStack()).thenReturn(stack);
        return new PlayerDropItemEvent(player, item);
    }
}
