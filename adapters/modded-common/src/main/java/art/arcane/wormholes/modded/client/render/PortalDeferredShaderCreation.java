package art.arcane.wormholes.modded.client.render;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class PortalDeferredShaderCreation<T> {
    private final T pendingValue;
    private Supplier<T> pending;

    public PortalDeferredShaderCreation(T pendingValue) {
        this.pendingValue = pendingValue;
    }

    public T create(Supplier<T> factory) {
        Objects.requireNonNull(factory);
        if (!PortalIrisShaderLoading.deferred()) {
            return factory.get();
        }
        if (pending != null) {
            throw new IllegalStateException("Destination shader factory has not been assigned");
        }
        pending = factory;
        return pendingValue;
    }

    public void assign(T value, Consumer<T> assignment) {
        Objects.requireNonNull(assignment);
        if (pending == null) {
            assignment.accept(value);
            return;
        }
        Supplier<T> factory = pending;
        pending = null;
        assignment.accept(pendingValue);
        PortalIrisShaderLoading.defer(() -> assignment.accept(factory.get()));
    }
}
