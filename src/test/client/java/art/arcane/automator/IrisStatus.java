package art.arcane.automator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

final class IrisStatus {
    private static final Logger LOGGER = LoggerFactory.getLogger("InstanceAutomator");
    private static final String API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
    private static String previousError;

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
}
