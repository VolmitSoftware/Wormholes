package art.arcane.wormholes.modded;

import art.arcane.optics.spi.ScaleAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Objects;
import java.util.function.Function;

public final class MinecraftScaleAccess implements ScaleAccess<Entity> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("wormholes", "portal_scale");
    private static final double UNIT_TOLERANCE = 1.0E-9D;

    private final Function<Entity, AttributeInstance> instances;

    public MinecraftScaleAccess(Function<Entity, AttributeInstance> instances) {
        this.instances = Objects.requireNonNull(instances, "instances");
    }

    public static MinecraftScaleAccess scaleAttribute() {
        return new MinecraftScaleAccess(entity -> entity instanceof LivingEntity living ? living.getAttribute(Attributes.SCALE) : null);
    }

    @Override
    public double scale(Entity entity) {
        AttributeInstance instance = instances.apply(entity);
        AttributeModifier modifier = instance == null ? null : instance.getModifier(ID);
        return modifier == null ? 1.0D : 1.0D + modifier.amount();
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
        if (Math.abs(factor - 1.0D) <= UNIT_TOLERANCE) {
            instance.removeModifier(ID);
            return true;
        }
        instance.addOrReplacePermanentModifier(new AttributeModifier(ID, factor - 1.0D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        return true;
    }

    @Override
    public boolean reset(Entity entity) {
        AttributeInstance instance = instances.apply(entity);
        return instance != null && instance.removeModifier(ID);
    }
}
