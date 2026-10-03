package art.arcane.automator;

import com.google.gson.JsonObject;

public final class TextInputPlanTest {
    public static void main(String[] args) {
        require(GuiMouseButton.fromProtocol(0) == 1);
        require(GuiMouseButton.fromProtocol(1) == 3);
        JsonObject input = input("Market trader", 2);
        TextInputPlan plan = TextInputPlan.fromJson(input);
        require(plan.text().equals("Market trader") && plan.ticksPerChar() == 2);
        require(TextInputPlan.fromJson(input("", 1)).text().isEmpty());
        require(TextInputPlan.fromJson(input("x".repeat(512), 20)).text().length() == 512);
        reject(input("x".repeat(513), 2));
        reject(input("line\nline", 2));
        reject(input("tab\t", 2));
        reject(input("Market", 0));
        reject(input("Market", 21));
        JsonObject defaults = new JsonObject();
        defaults.addProperty("text", "Trader");
        require(TextInputPlan.fromJson(defaults).ticksPerChar() == 2);
        System.out.println("TextInputPlanTest passed");
    }

    private static JsonObject input(String text, int ticksPerChar) {
        JsonObject input = new JsonObject();
        input.addProperty("text", text);
        input.addProperty("ticksPerChar", ticksPerChar);
        return input;
    }

    private static void reject(JsonObject input) {
        try {
            TextInputPlan.fromJson(input);
            throw new AssertionError("Invalid GUI text was accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void require(boolean accepted) {
        if (!accepted) {
            throw new AssertionError("GUI text plan differs from requested input");
        }
    }
}
