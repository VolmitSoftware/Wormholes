package art.arcane.wormholes.chunk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

public final class PaperChunkSendRateAccessor implements ChunkSendRateAccessor {
    public static final String GLOBAL_CONFIGURATION_CLASS = "io.papermc.paper.configuration.GlobalConfiguration";

    private final Map<ChunkSendRateLimit, Binding> bindings;
    private final String description;
    private final boolean available;

    private PaperChunkSendRateAccessor(Map<ChunkSendRateLimit, Binding> bindings, String description, boolean available) {
        this.bindings = bindings;
        this.description = description;
        this.available = available;
    }

    public static ChunkSendRateAccessor resolve() {
        return resolve(PaperChunkSendRateAccessor.class.getClassLoader());
    }

    static ChunkSendRateAccessor resolve(ClassLoader loader) {
        Object configuration;
        try {
            Class<?> type = Class.forName(GLOBAL_CONFIGURATION_CLASS, true, Objects.requireNonNull(loader));
            Method get = type.getMethod("get");
            configuration = get.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return unsupported(GLOBAL_CONFIGURATION_CLASS + " absent");
        }

        if (configuration == null) {
            return unsupported(GLOBAL_CONFIGURATION_CLASS + " uninitialised");
        }

        return bind(configuration, "Paper GlobalConfiguration");
    }

    static ChunkSendRateAccessor bind(Object configuration, String description) {
        Objects.requireNonNull(configuration);
        Map<ChunkSendRateLimit, Binding> bindings = new EnumMap<>(ChunkSendRateLimit.class);
        for (ChunkSendRateLimit limit : ChunkSendRateLimit.values()) {
            Binding binding = bindLimit(configuration, limit);
            if (binding != null) {
                bindings.put(limit, binding);
            }
        }
        return new PaperChunkSendRateAccessor(bindings, description, true);
    }

    static ChunkSendRateAccessor unsupported(String description) {
        return new PaperChunkSendRateAccessor(Map.of(), description, false);
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public OptionalDouble read(ChunkSendRateLimit limit) {
        Binding binding = bindings.get(Objects.requireNonNull(limit));
        if (binding == null) {
            return OptionalDouble.empty();
        }

        try {
            return OptionalDouble.of(binding.field().getDouble(binding.owner()));
        } catch (IllegalAccessException | RuntimeException error) {
            return OptionalDouble.empty();
        }
    }

    @Override
    public boolean write(ChunkSendRateLimit limit, double value) {
        Binding binding = bindings.get(Objects.requireNonNull(limit));
        if (binding == null) {
            return false;
        }

        try {
            binding.field().setDouble(binding.owner(), value);
            return true;
        } catch (IllegalAccessException | RuntimeException error) {
            return false;
        }
    }

    private static Binding bindLimit(Object configuration, ChunkSendRateLimit limit) {
        try {
            Object owner = configuration.getClass().getField(limit.section()).get(configuration);
            Field field = owner.getClass().getField(limit.field());
            if (field.getType() != double.class || Modifier.isFinal(field.getModifiers()) || Modifier.isStatic(field.getModifiers())) {
                return null;
            }

            return new Binding(owner, field);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private record Binding(Object owner, Field field) {
    }
}
