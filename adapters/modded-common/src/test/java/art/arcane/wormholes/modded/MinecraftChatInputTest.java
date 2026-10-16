package art.arcane.wormholes.modded;

import net.minecraft.server.level.ServerPlayer;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftChatInputTest {
    private final List<String> received = new ArrayList<>();
    private ServerPlayer player;
    private MinecraftChatInput chatInput;

    @Before
    public void setUp() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        when(runtime.schedule(any(), anyLong())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return true;
        });
        player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        chatInput = new MinecraftChatInput(runtime);
    }

    @Test
    public void cancelRemovesOnlyTheGivenCallback() {
        Consumer<String> current = received::add;
        Consumer<String> other = ignored -> {
        };
        chatInput.await(player, current);

        chatInput.cancel(player, other);
        assertTrue(chatInput.accept(player, "Gamma"));

        chatInput.await(player, current);
        chatInput.cancel(player, current);
        assertFalse(chatInput.accept(player, "Delta"));
        assertEquals(List.of("Gamma"), received);
    }
}
