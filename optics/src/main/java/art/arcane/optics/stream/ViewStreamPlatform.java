package art.arcane.optics.stream;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

public record ViewStreamPlatform<O, B>(ViewStreamTransport<O> transport,
                                       ViewStreamEndpoints<O, B> endpoints,
                                       EntityFrameSource<O> entities,
                                       ViewStreamScene<O> scene,
                                       PlateHandoffs<B> handoffs,
                                       Executor lanes,
                                       Function<B, String> stateStrings,
                                       int mcDataVersion,
                                       long platformCaps,
                                       LongSupplier nanoClock,
                                       BiConsumer<String, Throwable> warnings,
                                       List<ViewStreamExtension<?>> extensions,
                                       ViewStreamHooksFactory<O, B> hooks) {
    public ViewStreamPlatform {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(endpoints, "endpoints");
        Objects.requireNonNull(lanes, "lanes");
        Objects.requireNonNull(stateStrings, "stateStrings");
        entities = entities == null ? EntityFrameSource.none() : entities;
        scene = scene == null ? ViewStreamScene.none() : scene;
        handoffs = handoffs == null ? PlateHandoffs.none() : handoffs;
        platformCaps &= ViewStreamCapability.ALL;
        nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
        warnings = warnings == null ? (message, error) -> { } : warnings;
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        hooks = hooks == null ? session -> ViewStreamHooks.none() : hooks;
    }
}
