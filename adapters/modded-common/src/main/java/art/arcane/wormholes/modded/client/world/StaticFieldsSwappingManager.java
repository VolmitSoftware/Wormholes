/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: contexts are keyed by client level instead of dimension.
 */
package art.arcane.wormholes.modded.client.world;

import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class StaticFieldsSwappingManager<C> {
    private final Consumer<C> copyFromObject;
    private final Consumer<C> copyToObject;
    private final Supplier<C> contextConstructor;
    private final Map<ClientLevel, ContextRecord<C>> contextMap = new IdentityHashMap<>();
    private final ArrayDeque<ContextRecord<C>> swappedContext = new ArrayDeque<>();
    private ClientLevel outerLevel;

    StaticFieldsSwappingManager(Consumer<C> copyFromObject, Consumer<C> copyToObject, Supplier<C> contextConstructor) {
        this.copyFromObject = Objects.requireNonNull(copyFromObject);
        this.copyToObject = Objects.requireNonNull(copyToObject);
        this.contextConstructor = Objects.requireNonNull(contextConstructor);
    }

    boolean isSwapped() {
        return !swappedContext.isEmpty();
    }

    void setOuterLevel(ClientLevel level) {
        if (isSwapped()) {
            throw new IllegalStateException("Static render context swapped while changing the outer level");
        }
        outerLevel = level;
        record(level);
    }

    void pushSwapping(ClientLevel level) {
        ContextRecord<C> outer = record(currentLevel());
        ContextRecord<C> inner = record(level);
        swappedContext.push(inner);
        copyToObject.accept(outer.context());
        copyFromObject.accept(inner.context());
    }

    void popSwapping() {
        ContextRecord<C> inner = swappedContext.pop();
        ContextRecord<C> outer = record(currentLevel());
        copyToObject.accept(inner.context());
        copyFromObject.accept(outer.context());
    }

    void updateOuterLevelAndChangeContext(ClientLevel level) {
        if (isSwapped()) {
            throw new IllegalStateException("Static render context swapped while the player changes level");
        }
        if (outerLevel != null) {
            copyToObject.accept(record(outerLevel).context());
        }
        copyFromObject.accept(record(level).context());
        outerLevel = level;
    }

    void forget(ClientLevel level) {
        if (level != outerLevel) {
            contextMap.remove(level);
        }
    }

    void clear() {
        swappedContext.clear();
        contextMap.clear();
        outerLevel = null;
    }

    private ClientLevel currentLevel() {
        return swappedContext.isEmpty() ? outerLevel : swappedContext.peek().level();
    }

    private ContextRecord<C> record(ClientLevel level) {
        return contextMap.computeIfAbsent(level, key -> new ContextRecord<>(key, contextConstructor.get()));
    }

    private record ContextRecord<C>(ClientLevel level, C context) {
    }
}
