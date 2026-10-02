package art.arcane.automator.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.util.TimeSource;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLHints;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    @Unique
    private static final boolean HIDDEN = Boolean.parseBoolean(System.getProperty("automator.hidden", "true"));

    @Inject(method = "initBackendSystem", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLInit;SDL_Init(I)Z"))
    private static void beforeVideoInit(CallbackInfoReturnable<TimeSource.NanoTimeSource> callback) {
        if (!HIDDEN) {
            return;
        }
        for (String hint : new String[]{SDLHints.SDL_HINT_MAC_BACKGROUND_APP, SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN,
                SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_RAISED}) {
            String value = hint.equals(SDLHints.SDL_HINT_MAC_BACKGROUND_APP) ? "1" : "0";
            if (!SDLHints.SDL_SetHintWithPriority(hint, value, SDLHints.SDL_HINT_OVERRIDE)) {
                throw new IllegalStateException("Cannot configure hidden SDL renderer: " + SDLError.SDL_GetError());
            }
        }
    }
}
