package art.arcane.wormholes.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import art.arcane.volmlib.util.localization.LinesKey;

public record MenuLines(String label, List<String> description, List<String> chestHints) {
    public MenuLines {
        label = MenuText.text(label, "label");
        description = List.copyOf(Objects.requireNonNull(description, "description"));
        chestHints = List.copyOf(Objects.requireNonNull(chestHints, "chestHints"));
    }

    public static MenuLines split(List<String> resolved, LinesKey key, int chestHintLines) {
        List<String> lines = Objects.requireNonNull(resolved, "resolved");
        String label = lines.isEmpty() ? "" : lines.get(0);
        List<String> lore = lines.isEmpty() ? List.of() : lines.subList(1, lines.size());
        if (chestHintLines <= 0 || lines.size() != key.english().size() || chestHintLines > lore.size()) {
            return new MenuLines(label, lore, List.of());
        }
        int hintStart = lore.size() - chestHintLines;
        int descriptionEnd = hintStart;
        while (descriptionEnd > 0 && lore.get(descriptionEnd - 1).isEmpty()) {
            descriptionEnd--;
        }
        return new MenuLines(label, new ArrayList<>(lore.subList(0, descriptionEnd)), new ArrayList<>(lore.subList(descriptionEnd, lore.size())));
    }

    public List<String> chestLore() {
        List<String> lore = new ArrayList<>(description.size() + chestHints.size());
        lore.addAll(description);
        lore.addAll(chestHints);
        return lore;
    }

    public MenuLines withDescription(List<String> extra) {
        List<String> next = new ArrayList<>(description.size() + extra.size());
        next.addAll(description);
        next.addAll(extra);
        return new MenuLines(label, next, chestHints);
    }

    public MenuLines withChestHints(List<String> extra) {
        List<String> next = new ArrayList<>(chestHints.size() + extra.size());
        next.addAll(chestHints);
        next.addAll(extra);
        return new MenuLines(label, description, next);
    }
}
