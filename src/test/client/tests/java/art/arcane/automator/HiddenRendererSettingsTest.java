package art.arcane.automator;

import org.lwjgl.sdl.SDLVideo;

public final class HiddenRendererSettingsTest {
    public static void main(String[] args) {
        System.clearProperty("automator.hidden");
        System.clearProperty("automator.frameWidth");
        System.clearProperty("automator.frameHeight");
        require(HiddenRenderer.enabled(), "Automation must start hidden by default");
        require(HiddenRenderer.width() == 1920 && HiddenRenderer.height() == 1080, "Default render resolution must be 1920x1080");
        long desktopFlags = SDLVideo.SDL_WINDOW_RESIZABLE | SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY | SDLVideo.SDL_WINDOW_FULLSCREEN
            | SDLVideo.SDL_WINDOW_MAXIMIZED;
        long hiddenFlags = HiddenRenderer.windowFlags(desktopFlags);
        require((hiddenFlags & SDLVideo.SDL_WINDOW_HIDDEN) != 0, "Window must be hidden at creation");
        require((hiddenFlags & SDLVideo.SDL_WINDOW_NOT_FOCUSABLE) != 0, "Window must reject desktop focus");
        require((hiddenFlags & SDLVideo.SDL_WINDOW_FULLSCREEN) == 0, "Hidden renderer must not enter fullscreen");
        require((hiddenFlags & SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY) == 0, "Hidden resolution must not double on Retina displays");
        require((hiddenFlags & SDLVideo.SDL_WINDOW_MAXIMIZED) == 0, "Hidden renderer must not maximize");
        require((hiddenFlags & SDLVideo.SDL_WINDOW_RESIZABLE) != 0, "Unrelated backend flags must survive");
        System.setProperty("automator.hidden", "false");
        require(!HiddenRenderer.enabled(), "Explicit visible mode must remain available");
        require(HiddenRenderer.windowFlags(desktopFlags) == desktopFlags, "Visible mode must preserve native creation flags");
        System.clearProperty("automator.hidden");
        System.setProperty("automator.frameWidth", "99999");
        System.setProperty("automator.frameHeight", "0");
        require(HiddenRenderer.width() == 7680 && HiddenRenderer.height() == 240, "Invalid dimensions must stay within allocation bounds");
        System.clearProperty("automator.frameWidth");
        System.clearProperty("automator.frameHeight");
        System.out.println("Hidden renderer settings passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
