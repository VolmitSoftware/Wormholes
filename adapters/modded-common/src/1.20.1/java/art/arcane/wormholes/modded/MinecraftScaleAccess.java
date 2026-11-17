package art.arcane.wormholes.modded;

import art.arcane.optics.spi.ScaleAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Portal scaling through an attribute modifier. Minecraft 1.20.1 has no scale attribute, so
 * {@link #scaleAttribute()} resolves no instance and every entity stays at unit scale.
 */
public final class MinecraftScaleAccess implements ScaleAccess<Entity> {
    public static final ResourceLocation ID = new ResourceLocation("wormholes", "portal_scale");
    public static final UUID MODIFIER_ID = UUID.nameUUIDFromBytes(ID.toString().getBytes(StandardCharsets.UTF_8));
    private static final double UNIT_TOLERANCE = 1.0E-9D;

    private final Function<Entity, AttributeInstance> instances;

    public MinecraftScaleAccess(Function<Entity, AttributeInstance> instances) {
        this.instances = Objects.requireNonNull(instances, "instances");
    }

    public static MinecraftScaleAccess scaleAttribute() {
        return new MinecraftScaleAccess(entity -> null);
    }

    @Override
    public double scale(Entity entity) {
        AttributeInstance instance = instances.apply(entity);
        AttributeModifier modifier = instance == null ? null : instance.getModifier(MODIFIER_ID);
        return modifier == null ? 1.0D : 1.0D + modifier.getAmount();
    }

    @Override
    public double defaultScale(Entity entity) {
        AttributeInstance instance = instances.apply(entity);
        return instance == null ? 1.0D : instance.getValue() / scale(entity);
    }

    @Override
    public boolean scale(Entity entity, double factor) {
        AttributeInstance instance = instances.apply(entity);
        if (instance == null || !Double.isFinite(factor) || factor <= 0.0D) {
            return false;
        }
        AttributeModifier current = instance.getModifier(MODIFIER_ID);
        if (Math.abs(factor - 1.0D) <= UNIT_TOLERANCE) {
            if (current != null) {
                instance.removeModifier(MODIFIER_ID);
                entity.refreshDimensions();
            }
            return true;
        }
        if (current != null && current.getAmount() == factor - 1.0D && current.getOperation() == AttributeModifier.Operation.MULTIPLY_TOTAL) {
            return true;
        }
        if (current != null) {
            instance.removeModifier(MODIFIER_ID);
        }
        instance.addTransientModifier(new AttributeModifier(MODIFIER_ID, ID.toString(), factor - 1.0D, AttributeModifier.Operation.MULTIPLY_TOTAL));
        entity.refreshDimensions();
        return true;
    }

    @Override
    public boolean reset(Entity entity) {
        AttributeInstance instance = instances.apply(entity);
        if (instance == null || instance.getModifier(MODIFIER_ID) == null) {
            return false;
        }
        instance.removeModifier(MODIFIER_ID);
        entity.refreshDimensions();
        return true;
    }
}
