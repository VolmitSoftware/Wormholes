package art.arcane.wormholes.modded;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.resources.Identifier;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class MinecraftScaleAccessTest extends MinecraftTestBase {
    @Test
    public void scalingWritesOnePermanentTotalMultiplierUnderThePortalId() {
        AttributeInstance instance = new AttributeInstance(Attributes.SCALE, ignored -> { });
        Entity entity = mock(Entity.class);
        MinecraftScaleAccess access = new MinecraftScaleAccess(candidate -> candidate == entity ? instance : null);

        assertEquals(1.0D, access.scale(entity), 0.0D);
        assertTrue(access.scale(entity, 3.0D));

        AttributeModifier modifier = instance.getModifier(MinecraftScaleAccess.ID);
        assertEquals(2.0D, modifier.amount(), 1.0E-12D);
        assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, modifier.operation());
        assertTrue(instance.getPermanentModifiers().contains(modifier));
        assertEquals(3.0D, instance.getValue(), 1.0E-9D);
        assertEquals(3.0D, access.scale(entity), 1.0E-12D);
    }

    @Test
    public void scalingReplacesAndUnitRemovesTheModifier() {
        AttributeInstance instance = new AttributeInstance(Attributes.SCALE, ignored -> { });
        Entity entity = mock(Entity.class);
        MinecraftScaleAccess access = new MinecraftScaleAccess(candidate -> instance);
        access.scale(entity, 3.0D);

        assertTrue(access.scale(entity, 0.5D));
        assertEquals(-0.5D, instance.getModifier(MinecraftScaleAccess.ID).amount(), 1.0E-12D);
        assertEquals(1, instance.getModifiers().size());
        assertTrue(access.scale(entity, 1.0D));
        assertNull(instance.getModifier(MinecraftScaleAccess.ID));
    }

    @Test
    public void defaultScaleAndResetIgnoreOtherModifiers() {
        AttributeInstance instance = new AttributeInstance(Attributes.SCALE, ignored -> { });
        instance.addPermanentModifier(new AttributeModifier(Identifier.fromNamespaceAndPath("other", "grow"), 1.0D,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        Entity entity = mock(Entity.class);
        MinecraftScaleAccess access = new MinecraftScaleAccess(candidate -> instance);
        access.scale(entity, 3.0D);

        assertEquals(2.0D, access.defaultScale(entity), 1.0E-9D);
        assertTrue(access.reset(entity));
        assertFalse(access.reset(entity));
        assertEquals(2.0D, instance.getValue(), 1.0E-9D);
    }

    @Test
    public void entitiesWithoutTheAttributeAreUnsupported() {
        Entity item = mock(Entity.class);
        MinecraftScaleAccess access = MinecraftScaleAccess.scaleAttribute();

        assertEquals(1.0D, access.scale(item), 0.0D);
        assertFalse(access.scale(item, 3.0D));
        assertFalse(access.reset(item));
    }
}
