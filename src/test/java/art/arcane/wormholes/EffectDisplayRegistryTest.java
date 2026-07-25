package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;

import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

final class EffectDisplayRegistryTest {
    private static final String EFFECT_ENTITY_TAG = "wormholes_fx";

    @Test
    void taggedDisplayIsRecognisedAsEffectEntity() {
        assertTrue(EffectDisplayRegistry.isEffectEntity(entity(Display.class, Set.of(EFFECT_ENTITY_TAG))));
        assertTrue(EffectDisplayRegistry.isEffectEntity(entity(Display.class, Set.of("other", EFFECT_ENTITY_TAG))));
    }

    @Test
    void untaggedDisplayIsNotAnEffectEntity() {
        assertFalse(EffectDisplayRegistry.isEffectEntity(entity(Display.class, Set.of())));
        assertFalse(EffectDisplayRegistry.isEffectEntity(entity(Display.class, Set.of("wormholes_fx_other"))));
    }

    @Test
    void taggedNonDisplayEntityIsNotAnEffectEntity() {
        assertFalse(EffectDisplayRegistry.isEffectEntity(entity(Entity.class, Set.of(EFFECT_ENTITY_TAG))));
    }

    private static Entity entity(Class<? extends Entity> type, Set<String> scoreboardTags) {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> switch (method.getName()) {
            case "getScoreboardTags" -> scoreboardTags;
            case "equals" -> Boolean.valueOf(proxy == args[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> type.getSimpleName() + scoreboardTags;
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return type.cast(Proxy.newProxyInstance(EffectDisplayRegistryTest.class.getClassLoader(),
            new Class<?>[] { type }, handler));
    }
}
