package art.arcane.wormholes.menu;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class MenuBindings {
    private final Map<String, MenuBinding> bindings;

    private MenuBindings(Map<String, MenuBinding> bindings) {
        this.bindings = Map.copyOf(bindings);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<MenuBinding> find(String elementId) {
        return Optional.ofNullable(bindings.get(elementId));
    }

    public static final class Builder {
        private final Map<String, MenuGuard> guards = new HashMap<>();
        private final Map<String, Map<MenuInteraction, MenuHandler>> handlers = new HashMap<>();
        private final Map<String, MenuConfirmFactory> confirmations = new HashMap<>();

        private Builder() {
        }

        public Builder guard(String elementId, MenuGuard guard) {
            guards.put(elementId, Objects.requireNonNull(guard, "guard"));
            return this;
        }

        public Builder on(String elementId, MenuInteraction interaction, MenuHandler handler) {
            handlers.computeIfAbsent(elementId, ignored -> new EnumMap<>(MenuInteraction.class))
                .put(interaction, Objects.requireNonNull(handler, "handler"));
            return this;
        }

        public Builder confirm(String elementId, MenuConfirmFactory confirmation) {
            confirmations.put(elementId, Objects.requireNonNull(confirmation, "confirmation"));
            return this;
        }

        public MenuBindings build() {
            Map<String, MenuBinding> result = new HashMap<>(handlers.size() * 2);
            for (Map.Entry<String, Map<MenuInteraction, MenuHandler>> entry : handlers.entrySet()) {
                MenuGuard guard = Objects.requireNonNull(guards.get(entry.getKey()), "guard for " + entry.getKey());
                result.put(entry.getKey(), new MenuBinding(guard, entry.getValue(), confirmations.get(entry.getKey())));
            }
            for (String elementId : confirmations.keySet()) {
                if (!handlers.containsKey(elementId)) {
                    throw new IllegalStateException("confirmation for " + elementId + " has no handler");
                }
            }
            return new MenuBindings(result);
        }
    }
}
