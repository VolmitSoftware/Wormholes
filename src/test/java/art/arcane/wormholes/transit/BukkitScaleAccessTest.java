package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.junit.jupiter.api.Test;

final class BukkitScaleAccessTest {
    @Test
    void scalingAddsOneMultiplyModifierUnderThePortalKey() {
        Scaled scaled = scaled(1.0D);
        BukkitScaleAccess access = scaled.access();

        assertEquals(1.0D, access.scale(scaled.entity()), 0.0D);
        assertTrue(access.scale(scaled.entity(), 3.0D));

        assertEquals(1, scaled.modifiers().size());
        AttributeModifier modifier = scaled.modifiers().getFirst();
        assertEquals(BukkitScaleAccess.KEY, modifier.getKey());
        assertEquals(AttributeModifier.Operation.MULTIPLY_SCALAR_1, modifier.getOperation());
        assertEquals(2.0D, modifier.getAmount(), 1.0E-12D);
        assertEquals(3.0D, access.scale(scaled.entity()), 1.0E-12D);
    }

    @Test
    void scalingAgainReplacesTheModifierAndUnitRemovesIt() {
        Scaled scaled = scaled(1.0D);
        BukkitScaleAccess access = scaled.access();
        access.scale(scaled.entity(), 3.0D);

        assertTrue(access.scale(scaled.entity(), 0.5D));
        assertEquals(1, scaled.modifiers().size());
        assertEquals(-0.5D, scaled.modifiers().getFirst().getAmount(), 1.0E-12D);

        assertTrue(access.scale(scaled.entity(), 1.0D));
        assertTrue(scaled.modifiers().isEmpty());
    }

    @Test
    void defaultScaleIgnoresThePortalModifier() {
        Scaled scaled = scaled(2.0D);
        BukkitScaleAccess access = scaled.access();
        access.scale(scaled.entity(), 3.0D);

        assertEquals(2.0D, access.defaultScale(scaled.entity()), 1.0E-12D);
    }

    @Test
    void resetRemovesOnlyThePortalModifier() {
        Scaled scaled = scaled(1.0D);
        AttributeModifier other = new AttributeModifier(NamespacedKey.fromString("other:grow"), 1.0D,
            AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY);
        scaled.modifiers().add(other);
        BukkitScaleAccess access = scaled.access();

        assertFalse(access.reset(scaled.entity()));
        access.scale(scaled.entity(), 2.0D);
        assertTrue(access.reset(scaled.entity()));
        assertEquals(List.of(other), scaled.modifiers());
    }

    @Test
    void entitiesWithoutTheAttributeAreUnsupported() {
        Entity item = mock(Entity.class);
        BukkitScaleAccess access = new BukkitScaleAccess(entity -> null);

        assertEquals(1.0D, access.scale(item), 0.0D);
        assertFalse(access.scale(item, 3.0D));
        assertFalse(access.reset(item));
    }

    private static Scaled scaled(double base) {
        List<AttributeModifier> modifiers = new ArrayList<>();
        AttributeInstance instance = mock(AttributeInstance.class);
        when(instance.getBaseValue()).thenReturn(base);
        when(instance.getModifiers()).thenAnswer(invocation -> List.copyOf(modifiers));
        when(instance.getValue()).thenAnswer(invocation -> {
            double value = base;
            for (AttributeModifier modifier : modifiers) {
                if (modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                    value += modifier.getAmount();
                }
            }
            for (AttributeModifier modifier : modifiers) {
                if (modifier.getOperation() == AttributeModifier.Operation.MULTIPLY_SCALAR_1) {
                    value *= 1.0D + modifier.getAmount();
                }
            }
            return value;
        });
        doAnswer(invocation -> modifiers.add(invocation.getArgument(0))).when(instance).addModifier(any(AttributeModifier.class));
        doAnswer(invocation -> modifiers.remove(invocation.<AttributeModifier>getArgument(0))).when(instance).removeModifier(any(AttributeModifier.class));
        LivingEntity entity = mock(LivingEntity.class);
        return new Scaled(entity, modifiers, new BukkitScaleAccess(candidate -> candidate == entity ? instance : null));
    }

    private record Scaled(LivingEntity entity, List<AttributeModifier> modifiers, BukkitScaleAccess access) {
    }
}
