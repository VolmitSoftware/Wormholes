package art.arcane.automator;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.sdl.SDLKeycode;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLScancode;
import org.lwjgl.sdl.SDLVideo;

final class NativeClientPlatform {
    private NativeClientPlatform() {
    }

    static KeyEvent keyEvent(String key) {
        return switch (key) {
            case "enter" -> new KeyEvent(SDLScancode.SDL_SCANCODE_RETURN, SDLKeycode.SDLK_RETURN, 0);
            case "escape" -> new KeyEvent(SDLScancode.SDL_SCANCODE_ESCAPE, SDLKeycode.SDLK_ESCAPE, 0);
            case "tab" -> new KeyEvent(SDLScancode.SDL_SCANCODE_TAB, SDLKeycode.SDLK_TAB, 0);
            case "up" -> new KeyEvent(SDLScancode.SDL_SCANCODE_UP, SDLKeycode.SDLK_UP, 0);
            case "down" -> new KeyEvent(SDLScancode.SDL_SCANCODE_DOWN, SDLKeycode.SDLK_DOWN, 0);
            case "home" -> new KeyEvent(SDLScancode.SDL_SCANCODE_HOME, SDLKeycode.SDLK_HOME, 0);
            case "end" -> new KeyEvent(SDLScancode.SDL_SCANCODE_END, SDLKeycode.SDLK_END, 0);
            case "backspace" -> new KeyEvent(SDLScancode.SDL_SCANCODE_BACKSPACE, SDLKeycode.SDLK_BACKSPACE, 0);
            case "select-all" -> new KeyEvent(SDLScancode.SDL_SCANCODE_A, SDLKeycode.SDLK_A,
                    SDLKeycode.SDL_KMOD_CTRL | SDLKeycode.SDL_KMOD_GUI);
            default -> throw new IllegalArgumentException("Unsupported GUI key " + key);
        };
    }

    static void appendWindowState(JsonObject result, Minecraft client) {
        long window = client.getWindow().handle();
        long flags = SDLVideo.SDL_GetWindowFlags(window);
        result.addProperty("windowVisible", (flags & SDLVideo.SDL_WINDOW_HIDDEN) == 0);
        result.addProperty("windowFocused", (flags & SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0);
        result.addProperty("windowMouseGrabbed", SDLVideo.SDL_GetWindowMouseGrab(window));
        result.addProperty("relativeMouseMode", SDLMouse.SDL_GetWindowRelativeMouseMode(window));
        result.addProperty("mouseGrabbed", client.mouseHandler.isMouseGrabbed() || SDLMouse.SDL_GetWindowRelativeMouseMode(window)
            || SDLVideo.SDL_GetWindowMouseGrab(window));
    }
}
