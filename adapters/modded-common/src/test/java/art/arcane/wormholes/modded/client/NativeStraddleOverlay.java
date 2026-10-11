package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.mixin.client.StraddleOverlayMixin;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

final class NativeStraddleOverlay {
    private NativeStraddleOverlay() {
    }

    static boolean blocked(LocalPlayer player) throws ReflectiveOperationException {
        PlayerRenderState rendered = new PlayerRenderState();
        rendered.blockOverlay = new PlayerRenderState.BlockOverlay(null, 0, 0, 1, 1);
        StraddleOverlayMixin mixin = mock(StraddleOverlayMixin.class, CALLS_REAL_METHODS);
        Method method = StraddleOverlayMixin.class.getDeclaredMethod("wormholes$straddleOverlay", Camera.class, DeltaTracker.class, float.class,
            PlayerRenderState.class, CallbackInfo.class);
        method.setAccessible(true);
        method.invoke(mixin, null, null, 0.0F, rendered, mock(CallbackInfo.class));
        return rendered.blockOverlay != null;
    }
}
