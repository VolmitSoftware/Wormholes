package art.arcane.automator;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

final class NativeClientPlatform {
    private NativeClientPlatform() {
    }

    static KeyEvent keyEvent(String key) {
        int code = switch (key) {
            case "enter" -> GLFW.GLFW_KEY_ENTER;
            case "escape" -> GLFW.GLFW_KEY_ESCAPE;
            case "tab" -> GLFW.GLFW_KEY_TAB;
            case "up" -> GLFW.GLFW_KEY_UP;
            case "down" -> GLFW.GLFW_KEY_DOWN;
            case "home" -> GLFW.GLFW_KEY_HOME;
            case "end" -> GLFW.GLFW_KEY_END;
            case "backspace" -> GLFW.GLFW_KEY_BACKSPACE;
            case "select-all" -> GLFW.GLFW_KEY_A;
            default -> throw new IllegalArgumentException("Unsupported GUI key " + key);
        };
        int modifiers = key.equals("select-all") ? GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER : 0;
        return new KeyEvent(code, GLFW.glfwGetKeyScancode(code), modifiers);
    }

    static void appendWindowState(JsonObject result, Minecraft client) {
        long window = client.getWindow().handle();
        boolean captured = GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_DISABLED;
        result.addProperty("windowVisible", GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_VISIBLE) != 0);
        result.addProperty("windowFocused", GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) != 0);
        result.addProperty("windowMouseGrabbed", captured);
        result.addProperty("relativeMouseMode", captured);
        result.addProperty("mouseGrabbed", client.mouseHandler.isMouseGrabbed() || captured);
    }
}
