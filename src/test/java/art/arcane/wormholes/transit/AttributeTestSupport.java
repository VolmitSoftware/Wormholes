package art.arcane.wormholes.transit;

import art.arcane.wormholes.TestRegistryAccess;
import io.papermc.paper.registry.RegistryAccess;
import org.bukkit.attribute.Attribute;
import org.mockito.MockedStatic;

import java.util.Objects;

import static org.mockito.Mockito.mockStatic;

public final class AttributeTestSupport {
    private AttributeTestSupport() {
    }

    public static void initialize() {
        try (MockedStatic<RegistryAccess> registries = mockStatic(RegistryAccess.class)) {
            registries.when(RegistryAccess::registryAccess).thenReturn(new TestRegistryAccess());
            Objects.requireNonNull(Attribute.SCALE);
        }
    }
}
