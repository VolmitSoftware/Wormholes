package art.arcane.automator;

import com.google.gson.JsonObject;

public final class IrisStatusTest {
    public static void main(String[] args) throws ReflectiveOperationException {
        JsonObject absent = new JsonObject();
        IrisStatus.append(absent);
        require(absent.isEmpty(), "Absent Iris must omit its state");
        JsonObject off = new JsonObject();
        IrisStatus.append(off, Api.class);
        require(!off.getAsJsonObject("iris").get("shaderPackInUse").getAsBoolean(), "Shader use must report the actual pipeline");
        IrisStatus.setShadersEnabled(Api.class, true);
        JsonObject on = new JsonObject();
        IrisStatus.append(on, Api.class);
        require(on.getAsJsonObject("iris").get("shadersEnabled").getAsBoolean(), "Shader enable must update API configuration");
        require(on.getAsJsonObject("iris").get("shaderPackInUse").getAsBoolean(), "Shader enable must invoke the apply API");
        IrisStatus.setShadersEnabled(Api.class, false);
        require(!Api.CONFIG.enabled && Api.CONFIG.applications == 2, "Both toggle directions must apply exactly once");
        JsonObject input = new JsonObject();
        input.addProperty("enabled", true);
        require(IrisStatus.requestedEnabled(input), "A boolean toggle must be accepted");
        input.addProperty("enabled", "true");
        try {
            IrisStatus.requestedEnabled(input);
            throw new AssertionError("String shader toggles must fail");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains("boolean"), "Invalid toggle diagnostic must identify its type");
        }
        System.out.println("Optional Iris shader state and applied boolean toggles passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static final class Api {
        private static final Config CONFIG = new Config();

        public static Api getInstance() { return new Api(); }
        public boolean isShaderPackInUse() { return CONFIG.enabled; }
        public Config getConfig() { return CONFIG; }
    }

    public static final class Config {
        private boolean enabled;
        private int applications;

        public boolean areShadersEnabled() { return enabled; }
        public void setShadersEnabledAndApply(boolean value) {
            enabled = value;
            applications++;
        }
    }
}
