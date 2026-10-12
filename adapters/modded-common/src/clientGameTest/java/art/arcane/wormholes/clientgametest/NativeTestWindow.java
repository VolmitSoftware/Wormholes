package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLVideo;

final class NativeTestWindow {
    private NativeTestWindow() {
    }

    static boolean hiddenAndUnfocused(Minecraft client) {
        long flags = SDLVideo.SDL_GetWindowFlags(client.getWindow().handle());
        return (flags & SDLVideo.SDL_WINDOW_HIDDEN) != 0 && (flags & SDLVideo.SDL_WINDOW_INPUT_FOCUS) == 0;
    }

    static boolean mouseReleased(Minecraft client) {
        long window = client.getWindow().handle();
        return !client.mouseHandler.isMouseGrabbed() && !SDLVideo.SDL_GetWindowMouseGrab(window)
            && !SDLMouse.SDL_GetWindowRelativeMouseMode(window);
    }

    static boolean hasBlockOverlay(Minecraft client) {
        return client.gameRenderer.gameRenderState().levelRenderState.playerRenderState.blockOverlay != null;
    }
}
