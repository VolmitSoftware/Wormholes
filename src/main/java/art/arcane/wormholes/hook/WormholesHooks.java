package art.arcane.wormholes.hook;

import art.arcane.wormholes.network.WireMessageHandlers;

import java.util.List;

/**
 * Published hook tables. Hot paths read the volatile lists; {@link #install(WormholesRegistrar)} swaps
 * them once per enable and {@link #clear()} empties them on teardown. Every list is empty until a
 * subsystem registers something, so an unregistered hook costs one volatile read.
 */
public final class WormholesHooks {
    private static volatile List<TraversalGate> traversalGates = List.of();
    private static volatile List<DestinationResolver> destinationResolvers = List.of();
    private static volatile List<TraversalObserver> traversalObservers = List.of();
    private static volatile List<PortalExtensionFactory> portalExtensionFactories = List.of();
    private static volatile List<ProjectionSource> projectionSources = List.of();
    private static volatile List<PortalMenuEntry> portalMenuEntries = List.of();

    private WormholesHooks() {
    }

    public static void install(WormholesRegistrar registrar) {
        traversalGates = registrar.traversalGates();
        destinationResolvers = registrar.destinationResolvers();
        traversalObservers = registrar.traversalObservers();
        portalExtensionFactories = registrar.portalExtensionFactories();
        projectionSources = registrar.projectionSources();
        portalMenuEntries = registrar.portalMenuEntries();
        WireMessageHandlers.install(registrar.wireHandlers());
    }

    public static void clear() {
        traversalGates = List.of();
        destinationResolvers = List.of();
        traversalObservers = List.of();
        portalExtensionFactories = List.of();
        projectionSources = List.of();
        portalMenuEntries = List.of();
        WireMessageHandlers.clear();
    }

    public static List<TraversalGate> traversalGates() {
        return traversalGates;
    }

    public static List<DestinationResolver> destinationResolvers() {
        return destinationResolvers;
    }

    public static List<TraversalObserver> traversalObservers() {
        return traversalObservers;
    }

    public static List<PortalExtensionFactory> portalExtensionFactories() {
        return portalExtensionFactories;
    }

    public static List<ProjectionSource> projectionSources() {
        return projectionSources;
    }

    public static List<PortalMenuEntry> portalMenuEntries() {
        return portalMenuEntries;
    }
}
