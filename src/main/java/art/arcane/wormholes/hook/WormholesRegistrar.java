package art.arcane.wormholes.hook;

import art.arcane.wormholes.network.WireMessageHandler;
import art.arcane.wormholes.network.WireMessageType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collects hook registrations from every subsystem during the register phase. {@link WormholesHooks#install}
 * publishes the result as immutable arrays that the hot paths read without locking.
 */
public final class WormholesRegistrar {
    private final List<TraversalGate> traversalGates = new ArrayList<>();
    private final List<DestinationResolver> destinationResolvers = new ArrayList<>();
    private final List<TraversalObserver> traversalObservers = new ArrayList<>();
    private final List<PortalExtensionFactory> portalExtensionFactories = new ArrayList<>();
    private final List<ProjectionSource> projectionSources = new ArrayList<>();
    private final List<PortalMenuEntry> portalMenuEntries = new ArrayList<>();
    private final Map<WireMessageType, List<WireMessageHandler>> wireHandlers = new EnumMap<>(WireMessageType.class);

    public WormholesRegistrar traversalGate(TraversalGate gate) {
        traversalGates.add(Objects.requireNonNull(gate, "gate"));
        return this;
    }

    public WormholesRegistrar destinationResolver(DestinationResolver resolver) {
        destinationResolvers.add(Objects.requireNonNull(resolver, "resolver"));
        return this;
    }

    public WormholesRegistrar traversalObserver(TraversalObserver observer) {
        traversalObservers.add(Objects.requireNonNull(observer, "observer"));
        return this;
    }

    public WormholesRegistrar portalExtension(PortalExtensionFactory factory) {
        portalExtensionFactories.add(Objects.requireNonNull(factory, "factory"));
        return this;
    }

    public WormholesRegistrar projectionSource(ProjectionSource source) {
        projectionSources.add(Objects.requireNonNull(source, "source"));
        return this;
    }

    public WormholesRegistrar portalMenuEntry(PortalMenuEntry entry) {
        portalMenuEntries.add(Objects.requireNonNull(entry, "entry"));
        return this;
    }

    public WormholesRegistrar wireHandler(WireMessageType type, WireMessageHandler handler) {
        wireHandlers.computeIfAbsent(Objects.requireNonNull(type, "type"), ignored -> new ArrayList<>())
            .add(Objects.requireNonNull(handler, "handler"));
        return this;
    }

    List<TraversalGate> traversalGates() {
        List<TraversalGate> sorted = new ArrayList<>(traversalGates);
        sorted.sort((a, b) -> Integer.compare(a.order(), b.order()));
        return Collections.unmodifiableList(sorted);
    }

    List<DestinationResolver> destinationResolvers() {
        List<DestinationResolver> sorted = new ArrayList<>(destinationResolvers);
        sorted.sort((a, b) -> Integer.compare(a.order(), b.order()));
        return Collections.unmodifiableList(sorted);
    }

    List<TraversalObserver> traversalObservers() {
        return Collections.unmodifiableList(new ArrayList<>(traversalObservers));
    }

    List<PortalExtensionFactory> portalExtensionFactories() {
        return Collections.unmodifiableList(new ArrayList<>(portalExtensionFactories));
    }

    List<ProjectionSource> projectionSources() {
        return Collections.unmodifiableList(new ArrayList<>(projectionSources));
    }

    List<PortalMenuEntry> portalMenuEntries() {
        return Collections.unmodifiableList(new ArrayList<>(portalMenuEntries));
    }

    Map<WireMessageType, List<WireMessageHandler>> wireHandlers() {
        Map<WireMessageType, List<WireMessageHandler>> copy = new EnumMap<>(WireMessageType.class);
        for (Map.Entry<WireMessageType, List<WireMessageHandler>> entry : wireHandlers.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }
}
