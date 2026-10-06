package art.arcane.wormholes.render.client.session;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ClientViewTransport;
import art.arcane.optics.stream.ClientViewPlateHandoff;

public record ClientViewPlatform<P, B>(ClientViewTransport<P> transport,
                                       ClientViewPortalAccess<P, B> portals,
                                       ClientViewEntitySource<P> entities,
                                       ClientViewFxSource<P> fx,
                                       ClientViewPlateHandoff<B> handoffs,
                                       Executor lanes,
                                       Function<B, String> stateStrings,
                                       int mcDataVersion,
                                       long platformCaps,
                                       LongSupplier nanoClock,
                                       BiConsumer<String, Throwable> warnings) {
    public ClientViewPlatform {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(portals, "portals");
        Objects.requireNonNull(lanes, "lanes");
        Objects.requireNonNull(stateStrings, "stateStrings");
        entities = entities == null ? ClientViewEntitySource.none() : entities;
        fx = fx == null ? ClientViewFxSource.none() : fx;
        handoffs = handoffs == null ? ClientViewPlateHandoff.none() : handoffs;
        platformCaps &= ViewStreamCapability.ALL;
        nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
        warnings = warnings == null ? (message, error) -> { } : warnings;
    }
}
