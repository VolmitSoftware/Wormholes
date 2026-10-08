package art.arcane.wormholes.transit;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attributable;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EquipmentSlotGroup;

import java.util.Objects;
import java.util.function.Function;

import art.arcane.optics.spi.ScaleAccess;

public final class BukkitScaleAccess implements ScaleAccess<Entity> {
    public static final NamespacedKey KEY = NamespacedKey.fromString("wormholes:portal_scale");
    private static final double UNIT_TOLERANCE = 1.0E-9D;

    private final Function<Entity, AttributeInstance> instances;

    public BukkitScaleAccess(Function<Entity, AttributeInstance> instances) {
        this.instances = Objects.requireNonNull(instances, "instances");
    }

    public static BukkitScaleAccess scaleAttribute() {
        return new BukkitScaleAccess(entity -> entity instanceof Attributable attributable ? attributable.getAttribute(Attribute.SCALE) : null);
    }

    @Override
    public double scale(Entity entity) {
        AttributeInstance instance = instance(entity);
        AttributeModifier modifier = instance == null ? null : modifier(instance);
        return modifier == null ? 1.0D : 1.0D + modifier.getAmount();
    }

    @Override
    public double defaultScale(Entity entity) {
        AttributeInstance instance = instance(entity);
        if (instance == null) {
            return 1.0D;
        }
        return instance.getValue() / scale(entity);
    }

    @Override
    public boolean scale(Entity entity, double factor) {
        AttributeInstance instance = instance(entity);
        if (instance == null || !Double.isFinite(factor) || factor <= 0.0D) {
            return false;
        }
        AttributeModifier current = modifier(instance);
        if (current != null) {
            instance.removeModifier(current);
        }
        if (Math.abs(factor - 1.0D) > UNIT_TOLERANCE) {
            instance.addModifier(new AttributeModifier(KEY, factor - 1.0D, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
        }
        return true;
    }

    @Override
    public boolean reset(Entity entity) {
        AttributeInstance instance = instance(entity);
        AttributeModifier current = instance == null ? null : modifier(instance);
        if (current == null) {
            return false;
        }
        instance.removeModifier(current);
        return true;
    }

    private AttributeInstance instance(Entity entity) {
        return instances.apply(entity);
    }

    private static AttributeModifier modifier(AttributeInstance instance) {
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (KEY.equals(modifier.getKey())) {
                return modifier;
            }
        }
        return null;
    }
}
