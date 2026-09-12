package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds exactly the arguments a message declares. Rule denials are authored per portal, so the key a refusal
 * names is not known at the call site; filling only its declared placeholders keeps any denial renderable.
 */
final class RuleMessageArgs {
    private final Map<String, Object> available = new LinkedHashMap<>();

    private RuleMessageArgs() {
    }

    static RuleMessageArgs of() {
        return new RuleMessageArgs();
    }

    RuleMessageArgs with(String name, Object value) {
        available.put(name, value);
        return this;
    }

    MessageArgs forKey(TextKey key) {
        MessageArgs.Builder builder = MessageArgs.builder();
        for (String placeholder : key.placeholders()) {
            builder.untrusted(placeholder, available.getOrDefault(placeholder, ""));
        }
        return builder.build();
    }
}
