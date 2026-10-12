package art.arcane.automator;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.Set;

final class IrisStatus {
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger("InstanceAutomator");
    private static final String API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
    private static final Set<String> DIAGNOSTIC_FAILURES = new HashSet<>();
    private static String previousError;
    private static String previousGlError;

    private IrisStatus() {
    }

    static void append(JsonObject state) {
        Class<?> apiClass;
        try {
            apiClass = Class.forName(API_CLASS, false, IrisStatus.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            return;
        }
        append(state, apiClass);
        appendCapabilities(state);
    }

    static void append(JsonObject state, Class<?> apiClass) {
        try {
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Method configuration = apiClass.getMethod("getConfig");
            Object config = configuration.invoke(api);
            JsonObject iris = new JsonObject();
            iris.addProperty("shaderPackInUse", (Boolean) apiClass.getMethod("isShaderPackInUse").invoke(api));
            iris.addProperty("shadersEnabled", (Boolean) configuration.getReturnType().getMethod("areShadersEnabled").invoke(config));
            state.add("iris", iris);
            previousError = null;
        } catch (ReflectiveOperationException failure) {
            String diagnostic = failure.toString();
            state.addProperty("irisError", diagnostic);
            if (!diagnostic.equals(previousError)) {
                LOGGER.error("Cannot read Iris shader state", failure);
                previousError = diagnostic;
            }
        }
    }

    static void configure(JsonObject input) {
        boolean enabled = requestedEnabled(input);
        try {
            setShadersEnabled(Class.forName(API_CLASS), enabled);
        } catch (ClassNotFoundException absent) {
            throw new IllegalStateException("Iris is not installed", absent);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot apply Iris shader setting", failure);
        }
    }

    static boolean requestedEnabled(JsonObject input) {
        JsonElement enabled = input.get("enabled");
        if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Shader enabled must be a boolean");
        }
        return enabled.getAsBoolean();
    }

    static void setShadersEnabled(Class<?> apiClass, boolean enabled) throws ReflectiveOperationException {
        Object api = apiClass.getMethod("getInstance").invoke(null);
        Method configuration = apiClass.getMethod("getConfig");
        Object config = configuration.invoke(api);
        configuration.getReturnType().getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, enabled);
    }

    private static void appendCapabilities(JsonObject state) {
        JsonObject iris = state.getAsJsonObject("iris");
        if (iris == null || !RenderSystem.isOnRenderThread()) {
            return;
        }
        try {
            GLCapabilities capabilities = GL.getCapabilities();
            iris.addProperty("renderer", GL11.glGetString(GL11.GL_RENDERER));
            iris.addProperty("openGL41", capabilities.OpenGL41);
            iris.addProperty("arbGetProgramBinary", capabilities.GL_ARB_get_program_binary);
            boolean binaryApi = capabilities.OpenGL41 || capabilities.GL_ARB_get_program_binary;
            int formats = binaryApi ? GL11.glGetInteger(GL41.GL_NUM_PROGRAM_BINARY_FORMATS) : 0;
            if (binaryApi) {
                iris.addProperty("programBinaryFormats", formats);
            }
            iris.addProperty("programBinarySupported", binaryApi && formats > 0);
            appendDiagnostics(iris, "viewPipelines", "art.arcane.wormholes.modded.client.render.iris.IrisViewPipelines", "diagnostics");
            appendDiagnostics(iris, "shaderStages", "art.arcane.wormholes.modded.client.render.iris.IrisShaderStages", "diagnostics");
            appendDiagnostics(iris, "cameraPaths", "art.arcane.wormholes.modded.client.render.iris.IrisViewPipelines", "cameraSnapshots");
            previousGlError = null;
        } catch (IllegalStateException failure) {
            String diagnostic = failure.toString();
            iris.addProperty("glError", diagnostic);
            if (!diagnostic.equals(previousGlError)) {
                LOGGER.error("Cannot read live Iris OpenGL capabilities", failure);
                previousGlError = diagnostic;
            }
        }
    }

    private static void appendDiagnostics(JsonObject iris, String key, String className, String method) {
        try {
            Class<?> source = Class.forName(className, false, IrisStatus.class.getClassLoader());
            Object counters = source.getMethod(method).invoke(null);
            JsonObject values = new JsonObject();
            for (RecordComponent component : counters.getClass().getRecordComponents()) {
                Object value = component.getAccessor().invoke(counters);
                if (value instanceof Number number) {
                    values.addProperty(component.getName(), number);
                } else {
                    values.add(component.getName(), GSON.toJsonTree(value));
                }
            }
            iris.add(key, values);
        } catch (ClassNotFoundException absent) {
            return;
        } catch (ReflectiveOperationException failure) {
            iris.addProperty(key + "Error", failure.toString());
            if (DIAGNOSTIC_FAILURES.add(className + ":" + failure)) {
                LOGGER.error("Cannot read Wormholes shader diagnostics for {}", className, failure);
            }
        }
    }
}
