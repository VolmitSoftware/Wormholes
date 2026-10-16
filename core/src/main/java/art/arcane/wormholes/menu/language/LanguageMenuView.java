package art.arcane.wormholes.menu.language;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.TextValue;

public record LanguageMenuView(String locale, List<Message> messages) {
    private static final List<String> PLURAL_FORMS = List.of("zero", "one", "two", "few", "many", "other");

    public LanguageMenuView {
        Objects.requireNonNull(locale, "locale");
        messages = List.copyOf(messages);
    }

    public static LanguageMenuView of(PluginLanguageEditor.Document document) {
        LocalizationSnapshot snapshot = document.snapshot();
        List<MessageKey> keys = snapshot.catalog().keys();
        List<Message> messages = new ArrayList<>(keys.size());
        for (MessageKey key : keys) {
            List<String> placeholders = new ArrayList<>(key.placeholders());
            placeholders.sort(Comparator.naturalOrder());
            messages.add(new Message(key.id(), snapshot.value(key), placeholders));
        }
        return new LanguageMenuView(document.locale(), messages);
    }

    public static String group(String key) {
        int separator = key.indexOf('.');
        return separator < 0 ? key : key.substring(0, separator);
    }

    public static String raw(MessageValue value, String form) {
        if (value instanceof TextValue text) {
            return text.template();
        }
        if (value instanceof LinesValue lines) {
            return form == null ? String.join("\n", lines.lines()) : lines.lines().get(Integer.parseInt(form));
        }
        PluralValue plural = (PluralValue) value;
        if (form != null) {
            return plural.forms().getOrDefault(form, plural.forms().get("other"));
        }
        List<String> forms = new ArrayList<>(plural.forms().size());
        for (Map.Entry<String, String> entry : plural.forms().entrySet()) {
            forms.add(entry.getKey() + ": " + entry.getValue());
        }
        return String.join("\n", forms);
    }

    public static MessageValue replaced(MessageValue current, String form, String value) {
        if (current instanceof TextValue) {
            return new TextValue(value);
        }
        if (current instanceof LinesValue lines) {
            List<String> updated = new ArrayList<>(lines.lines());
            updated.set(Integer.parseInt(Objects.requireNonNull(form, "line")), value);
            return new LinesValue(updated);
        }
        Map<String, String> forms = new LinkedHashMap<>(((PluralValue) current).forms());
        forms.put(Objects.requireNonNull(form, "plural form"), value);
        return new PluralValue(forms);
    }

    public static List<String> parts(MessageValue value) {
        if (value instanceof LinesValue lines) {
            List<String> indices = new ArrayList<>(lines.lines().size());
            for (int index = 0; index < lines.lines().size(); index++) {
                indices.add(Integer.toString(index));
            }
            return indices;
        }
        if (value instanceof PluralValue plural) {
            List<String> forms = new ArrayList<>(PLURAL_FORMS.size());
            for (String form : PLURAL_FORMS) {
                if (plural.forms().containsKey(form)) {
                    forms.add(form);
                }
            }
            return forms;
        }
        return List.of();
    }

    public List<String> groups() {
        Set<String> groups = new LinkedHashSet<>();
        for (Message message : messages) {
            groups.add(group(message.key()));
        }
        List<String> sorted = new ArrayList<>(groups);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    public int count(String group) {
        int count = 0;
        for (Message message : messages) {
            if (group(message.key()).equals(group)) {
                count++;
            }
        }
        return count;
    }

    public List<Message> matching(String group, String filter) {
        String query = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        List<Message> matches = new ArrayList<>();
        for (Message message : messages) {
            if (group != null && !group(message.key()).equals(group)) {
                continue;
            }
            if (query.isEmpty() || message.key().toLowerCase(Locale.ROOT).contains(query)
                || raw(message.value(), null).toLowerCase(Locale.ROOT).contains(query)) {
                matches.add(message);
            }
        }
        matches.sort(Comparator.comparing(Message::key));
        return matches;
    }

    public Optional<Message> message(String key) {
        for (Message message : messages) {
            if (message.key().equals(key)) {
                return Optional.of(message);
            }
        }
        return Optional.empty();
    }

    public record Message(String key, MessageValue value, List<String> placeholders) {
        public Message {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            placeholders = List.copyOf(placeholders);
        }
    }
}
