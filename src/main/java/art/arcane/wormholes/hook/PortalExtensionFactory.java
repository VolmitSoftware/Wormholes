package art.arcane.wormholes.hook;

import art.arcane.wormholes.portal.LocalPortal;

import java.util.Objects;
import java.util.function.Function;

/** Creates a {@link PortalExtension} for every constructed or loaded {@code LocalPortal}. */
public record PortalExtensionFactory<T extends PortalExtension>(Class<T> type, Function<LocalPortal, T> constructor) {
    public PortalExtensionFactory {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(constructor, "constructor");
    }

    public T create(LocalPortal portal) {
        return constructor.apply(portal);
    }
}
