package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

final class NativeTestWindow {
    private NativeTestWindow() {
    }

    static boolean hiddenAndUnfocused(Minecraft client) {
        long window = client.getWindow().handle();
        return GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_VISIBLE) == GLFW.GLFW_FALSE
            && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_FALSE;
    }

    static boolean mouseReleased(Minecraft client) {
        long window = client.getWindow().handle();
        return !client.mouseHandler.isMouseGrabbed()
            && GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_NORMAL
            && GLFW.glfwGetInputMode(window, GLFW.GLFW_RAW_MOUSE_MOTION) == GLFW.GLFW_FALSE;
    }

    static boolean hasBlockOverlay(Minecraft client) {
        return GravityOverlayTap.active();
    }
}
