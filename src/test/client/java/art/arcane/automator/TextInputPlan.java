package art.arcane.automator;

import com.google.gson.JsonObject;

record TextInputPlan(String text, int ticksPerChar) {
    static TextInputPlan fromJson(JsonObject input) {
        String text = input.get("text").getAsString();
        int ticksPerChar = input.has("ticksPerChar") ? input.get("ticksPerChar").getAsInt() : 2;
        if (text.length() > 512 || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("GUI text must contain at most 512 printable characters");
        }
        if (ticksPerChar < 1 || ticksPerChar > 20) {
            throw new IllegalArgumentException("ticksPerChar must be between 1 and 20");
        }
        return new TextInputPlan(text, ticksPerChar);
    }
}
