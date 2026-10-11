package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.mixin.client.StraddleOverlayMixin;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;

import static org.mockito.Mockito.mock;

final class NativeStraddleOverlay {
    private NativeStraddleOverlay() {
    }

    static boolean blocked(LocalPlayer player) throws ReflectiveOperationException {
        BlockState blocked = mock(BlockState.class);
        Method method = StraddleOverlayMixin.class.getDeclaredMethod("wormholes$straddleOverlay", BlockState.class, Player.class);
        method.setAccessible(true);
        return method.invoke(null, blocked, player) != null;
    }
}
