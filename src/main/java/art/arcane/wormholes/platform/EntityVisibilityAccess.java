package art.arcane.wormholes.platform;

import art.arcane.wormholes.Wormholes;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EntityVisibilityAccess {
    private static final ClassValue<VisibilityAccessor> ACCESSORS = new ClassValue<VisibilityAccessor>() {
        @Override
        protected VisibilityAccessor computeValue(Class<?> type) {
            try {
                Field field = visibilityField(type);
                if (!Map.class.isAssignableFrom(field.getType()) || Modifier.isStatic(field.getModifiers())) {
                    throw new IllegalStateException("Entity visibility field is not an instance map on " + type.getName());
                }
                field.setAccessible(true);
                return new VisibilityAccessor(field, null);
            } catch (ReflectiveOperationException | RuntimeException error) {
                return new VisibilityAccessor(null, error);
            }
        }
    };

    private EntityVisibilityAccess() {
    }

    public static boolean isVisible(Player observer, UUID entityId, boolean visibleByDefault, Plugin excludedPlugin) {
        if (observer == null || entityId == null) {
            return false;
        }
        if (entityId.equals(observer.getUniqueId())) {
            return true;
        }
        return ACCESSORS.get(observer.getClass()).isVisible(observer, entityId, visibleByDefault, excludedPlugin);
    }

    private static Field visibilityField(Class<?> type) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField("invertedVisibilityEntities");
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException("Entity visibility map is unavailable on " + type.getName());
    }

    private static boolean isInverted(Map<?, ?> inversions, UUID entityId, Plugin excludedPlugin) {
        Object value = inversions.get(entityId);
        if (value == null) {
            return inversions.containsKey(entityId);
        }
        if (!(value instanceof Set<?> plugins)) {
            throw new IllegalStateException("Entity visibility map contains an unsupported plugin collection");
        }
        if (plugins.isEmpty()) {
            return true;
        }
        for (Object pluginReference : plugins) {
            if (pluginReference == null) {
                return true;
            }
            if (!(pluginReference instanceof WeakReference<?> reference)) {
                throw new IllegalStateException("Entity visibility map contains an unsupported plugin reference");
            }
            if (excludedPlugin == null || reference.get() != excludedPlugin) {
                return true;
            }
        }
        return false;
    }

    private static final class VisibilityAccessor {
        private final Field field;
        private final Exception failure;
        private final AtomicBoolean failureReported = new AtomicBoolean();

        private VisibilityAccessor(Field field, Exception failure) {
            this.field = field;
            this.failure = failure;
        }

        private boolean isVisible(Player observer, UUID entityId, boolean visibleByDefault, Plugin excludedPlugin) {
            if (failure != null) {
                reportFailure(observer, failure);
                return false;
            }
            try {
                Object value = field.get(observer);
                if (!(value instanceof Map<?, ?> inversions)) {
                    throw new IllegalStateException("Entity visibility map is unavailable");
                }
                return visibleByDefault ^ isInverted(inversions, entityId,
                    visibleByDefault ? excludedPlugin : null);
            } catch (IllegalAccessException | RuntimeException error) {
                reportFailure(observer, error);
                return false;
            }
        }

        private void reportFailure(Player observer, Exception error) {
            if (!failureReported.compareAndSet(false, true)) {
                return;
            }
            Wormholes plugin = Wormholes.instance;
            Logger logger = plugin == null ? Logger.getLogger("Wormholes") : plugin.getLogger();
            logger.log(Level.WARNING,
                "Entity projection cannot read player visibility on " + observer.getClass().getName()
                    + "; projected entities are withheld for this player implementation.",
                error);
        }
    }
}
