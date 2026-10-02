package art.arcane.automator;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;

public final class HiddenRenderer implements PreLaunchEntrypoint {
    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("automator.hidden", "true"));
    }

    public static int width() {
        return Math.clamp(Integer.getInteger("automator.frameWidth", 1920), 320, 7680);
    }

    public static int height() {
        return Math.clamp(Integer.getInteger("automator.frameHeight", 1080), 240, 4320);
    }

    public static long windowFlags(long flags) {
        if (!enabled()) {
            return flags;
        }
        return (flags & ~(SDLVideo.SDL_WINDOW_FULLSCREEN | SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY
            | SDLVideo.SDL_WINDOW_MINIMIZED | SDLVideo.SDL_WINDOW_MAXIMIZED))
            | SDLVideo.SDL_WINDOW_HIDDEN | SDLVideo.SDL_WINDOW_NOT_FOCUSABLE;
    }

    @Override
    public void onPreLaunch() {
        if (enabled()) {
            SDLHints.SDL_SetHint(SDLHints.SDL_HINT_MAC_BACKGROUND_APP, "1");
            SDLHints.SDL_SetHint(SDLHints.SDL_HINT_MOUSE_AUTO_CAPTURE, "0");
            SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_RAISED, "0");
            SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "0");
        }
    }
}
