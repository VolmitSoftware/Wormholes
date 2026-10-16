package art.arcane.wormholes;

import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Keyed;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.inventory.MenuType;
import org.bukkit.potion.PotionEffectType;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;

public final class TestRegistryAccess implements RegistryAccess {
    private final Map<Object, Registry<?>> registries = new ConcurrentHashMap<>();
    private final Map<Object, Attribute> attributes = new ConcurrentHashMap<>();

    @Override
    public <T extends Keyed> Registry<T> getRegistry(Class<T> type) {
        return typed(registries.computeIfAbsent(type, this::registry));
    }

    @Override
    public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) {
        return typed(registries.computeIfAbsent(key, this::registry));
    }

    private Registry<?> registry(Object key) {
        boolean attribute = key == Attribute.class || key == RegistryKey.ATTRIBUTE;
        boolean menu = key == MenuType.class || key == RegistryKey.MENU;
        boolean effect = key == PotionEffectType.class || key == RegistryKey.MOB_EFFECT;
        return mock(Registry.class, lookup -> switch (lookup.getMethod().getName()) {
            case "get", "getOrThrow" -> {
                if (attribute) {
                    yield attributes.computeIfAbsent(lookup.getArgument(0), ignored -> mock(Attribute.class));
                }
                if (menu) {
                    yield mock(MenuType.Typed.class, RETURNS_SELF);
                }
                yield effect ? mock(PotionEffectType.class) : null;
            }
            default -> RETURNS_DEFAULTS.answer(lookup);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Keyed> Registry<T> typed(Registry<?> registry) {
        return (Registry<T>) registry;
    }
}
