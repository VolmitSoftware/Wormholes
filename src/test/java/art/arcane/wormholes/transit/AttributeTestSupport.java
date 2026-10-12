package art.arcane.wormholes.transit;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.inventory.MenuType;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

public final class AttributeTestSupport {
    private AttributeTestSupport() {
    }

    public static void initialize() {
        try (MockedStatic<RegistryAccess> registries = mockStatic(RegistryAccess.class)) {
            Map<Object, Attribute> attributes = new HashMap<>();
            RegistryAccess access = mock(RegistryAccess.class, invocation -> registry(invocation.getArgument(0), attributes));
            registries.when(RegistryAccess::registryAccess).thenReturn(access);
            Objects.requireNonNull(Attribute.SCALE);
        }
    }

    private static Registry<?> registry(Object key, Map<Object, Attribute> attributes) {
        boolean attribute = key == Attribute.class || key == RegistryKey.ATTRIBUTE;
        boolean menu = key == MenuType.class || key == RegistryKey.MENU;
        return mock(Registry.class, lookup -> switch (lookup.getMethod().getName()) {
            case "get", "getOrThrow" -> attribute ? attributes.computeIfAbsent(lookup.getArgument(0), ignored -> mock(Attribute.class))
                : menu ? mock(MenuType.Typed.class, RETURNS_SELF) : null;
            default -> RETURNS_DEFAULTS.answer(lookup);
        });
    }
}
