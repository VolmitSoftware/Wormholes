package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Registration helper for a lane-owned message group. Each group class keeps its own key list and a
 * unique id prefix; the catalog merges every group through {@link WormholesMessageGroups}.
 */
public final class MessageGroup {
    private final String prefix;
    private final List<MessageKey> keys = new ArrayList<>();

    public MessageGroup(String prefix) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        if (!prefix.endsWith(".")) {
            throw new IllegalArgumentException("Message group prefix must end with '.': " + prefix);
        }
    }

    public String prefix() {
        return prefix;
    }

    public List<MessageKey> keys() {
        return Collections.unmodifiableList(keys);
    }

    public TextKey text(String id, String english) {
        TextKey key = TextKey.of(requirePrefixed(id), english);
        keys.add(key);
        return key;
    }

    public LinesKey lines(String id, String... english) {
        LinesKey key = LinesKey.of(requirePrefixed(id), english);
        keys.add(key);
        return key;
    }

    public PluralKey plural(String id, String selectorArgument, Map<String, String> english) {
        PluralKey key = PluralKey.of(requirePrefixed(id), selectorArgument, english);
        keys.add(key);
        return key;
    }

    private String requirePrefixed(String id) {
        if (id == null || !id.startsWith(prefix)) {
            throw new IllegalArgumentException("Message id '" + id + "' must start with group prefix '" + prefix + "'");
        }
        return id;
    }
}
