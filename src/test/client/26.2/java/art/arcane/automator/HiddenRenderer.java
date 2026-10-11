package art.arcane.automator;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.lwjgl.glfw.GLFW;

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

    @Override
    public void onPreLaunch() {
        if (enabled()) {
            GLFW.glfwInitHint(GLFW.GLFW_COCOA_MENUBAR, GLFW.GLFW_FALSE);
        }
    }
}
