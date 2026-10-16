package art.arcane.wormholes.modded.menu;

import art.arcane.wormholes.menu.CapturedItem;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.modded.MinecraftChatInput;
import art.arcane.wormholes.modded.MinecraftPortalText;
import art.arcane.wormholes.modded.MinecraftPortalToolsTest;
import art.arcane.wormholes.modded.WormholesModRuntime;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftMenuCapturesTest {
    private final List<String> submitted = new ArrayList<>();
    private final List<CapturedItem> captured = new ArrayList<>();
    private WormholesModRuntime runtime;
    private MinecraftChatInput chatInput;
    private ServerPlayer player;
    private MinecraftMenuViewer viewer;
    private MinecraftMenuCaptures captures;
    private int cancelled;

    @BeforeClass
    public static void bootstrap() {
        MinecraftPortalToolsTest.bootstrap();
    }

    @Before
    public void setUp() {
        runtime = mock(WormholesModRuntime.class);
        chatInput = mock(MinecraftChatInput.class);
        player = mock(ServerPlayer.class);
        when(runtime.chatInput()).thenReturn(chatInput);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        viewer = new MinecraftMenuViewer(runtime, player);
        captures = new MinecraftMenuCaptures(runtime);
    }

    @Test
    public void promptsSendTheirLinesAndResolveSubmitOrCancelFromChat() {
        captures.prompt(viewer, List.of("§eType a new name.", "Type cancel to stop."), submitted::add, () -> cancelled++);

        ArgumentCaptor<Component> lines = ArgumentCaptor.forClass(Component.class);
        verify(player, times(2)).sendSystemMessage(lines.capture());
        assertEquals(List.of("Type a new name.", "Type cancel to stop."), lines.getAllValues().stream().map(Component::getString).toList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<String>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(chatInput).await(eq(player), callback.capture());
        try (MockedStatic<MinecraftPortalText> text = mockStatic(MinecraftPortalText.class)) {
            text.when(() -> MinecraftPortalText.isCancelInput(player, "cancel")).thenReturn(true);
            callback.getValue().accept("Gamma");
            callback.getValue().accept("cancel");
            callback.getValue().accept(null);
        }

        assertEquals(List.of("Gamma"), submitted);
        assertEquals(2, cancelled);
    }

    @Test
    public void droppedItemsCompleteThePendingCaptureWithASingleHeldStack() {
        ItemStack held = new ItemStack(Items.DIAMOND, 20);
        held.set(DataComponents.CUSTOM_NAME, Component.literal("Exact toll"));
        when(player.getMainHandItem()).thenReturn(held);
        captures.dropItem(viewer, List.of("Drop the item."), captured::add, () -> cancelled++);

        assertTrue(captures.capture(player));

        assertEquals(1, captured.size());
        MinecraftCapturedItem item = (MinecraftCapturedItem) captured.getFirst();
        assertEquals("minecraft:diamond", item.itemId());
        assertEquals("Diamond", item.label());
        assertEquals(1, item.stack().getCount());
        assertTrue(ItemStack.isSameItemSameComponents(held, item.stack()));
        assertEquals(20, held.getCount());
        assertFalse(captures.capture(player));
        assertEquals(0, cancelled);
        verify(player).sendSystemMessage(any(Component.class));
    }

    @Test
    public void emptyHandsCancelThePendingCapture() {
        captures.dropItem(viewer, List.of(), captured::add, () -> cancelled++);

        assertTrue(captures.capture(player));

        assertTrue(captured.isEmpty());
        assertEquals(1, cancelled);
    }

    @Test
    public void dropsWithoutAPendingCaptureAreLeftToVanilla() {
        assertFalse(captures.capture(player));
    }

    @Test
    public void disconnectingOrClosingForgetsPendingCaptures() {
        captures.dropItem(viewer, List.of(), captured::add, () -> cancelled++);
        captures.disconnected(player);
        assertFalse(captures.capture(player));

        captures.dropItem(viewer, List.of(), captured::add, () -> cancelled++);
        captures.close();
        assertFalse(captures.capture(player));
        assertEquals(0, cancelled);
    }

    @Test
    public void cancelForgetsThePendingCapturesWithoutRunningTheirCallbacks() {
        captures.prompt(viewer, List.of(), submitted::add, () -> cancelled++);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<String>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(chatInput).await(eq(player), callback.capture());
        captures.dropItem(viewer, List.of(), captured::add, () -> cancelled++);

        captures.cancel(player);

        verify(chatInput).cancel(player, callback.getValue());
        assertFalse(captures.capture(player));
        assertEquals(0, cancelled);
        assertTrue(submitted.isEmpty());
    }

    @Test
    public void answeredPromptsAreNotCancelledLater() {
        captures.prompt(viewer, List.of(), submitted::add, () -> cancelled++);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<String>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(chatInput).await(eq(player), callback.capture());
        try (MockedStatic<MinecraftPortalText> text = mockStatic(MinecraftPortalText.class)) {
            callback.getValue().accept("Gamma");
        }

        captures.cancel(player);

        verify(chatInput, never()).cancel(any(), any());
        assertEquals(List.of("Gamma"), submitted);
    }

    @Test
    public void heldItemReadsTheMainHand() {
        assertTrue(captures.heldItem(viewer).isEmpty());

        when(player.getMainHandItem()).thenReturn(new ItemStack(Items.EMERALD, 7));

        CapturedItem held = captures.heldItem(viewer).orElseThrow();
        assertEquals("minecraft:emerald", held.itemId());
        assertEquals("Emerald", held.label());
    }

    @Test
    public void viewersOfOtherPlatformsAreRejected() {
        MenuViewer foreign = mock(MenuViewer.class);

        assertThrows(IllegalArgumentException.class, () -> captures.heldItem(foreign));
    }
}
