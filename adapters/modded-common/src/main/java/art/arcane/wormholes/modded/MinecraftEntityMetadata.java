package art.arcane.wormholes.modded;

import art.arcane.optics.entity.MetadataPatcher;
import art.arcane.optics.entity.ItemFrameMetadata;
import art.arcane.optics.entity.MetadataAccess;
import art.arcane.optics.math.Face;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;

import java.util.Optional;

public final class MinecraftEntityMetadata implements MetadataAccess<SynchedEntityData.DataValue<?>> {
    public static final MinecraftEntityMetadata ACCESS = new MinecraftEntityMetadata();
    public static final ItemFrameMetadata<SynchedEntityData.DataValue<?>> FRAMES = new ItemFrameMetadata<>(ACCESS);
    public static final MetadataPatcher<SynchedEntityData.DataValue<?>> ENTITIES = new MetadataPatcher<>(ACCESS);

    private MinecraftEntityMetadata() {
    }

    @Override
    public int index(SynchedEntityData.DataValue<?> value) { return value.id(); }
    @Override
    public Object value(SynchedEntityData.DataValue<?> value) { return value.value(); }
    @Override
    public boolean isDirection(Object value) { return value instanceof Direction; }
    @Override
    public boolean isItem(Object value) { return value instanceof ItemStack; }
    @Override
    public Object direction(Face direction) {
        return switch (direction) {
            case D -> Direction.DOWN;
            case U -> Direction.UP;
            case N -> Direction.NORTH;
            case S -> Direction.SOUTH;
            case W -> Direction.WEST;
            case E -> Direction.EAST;
        };
    }
    @Override
    public Integer mapId(Object item) {
        MapId id = ((ItemStack) item).get(DataComponents.MAP_ID);
        return id == null ? null : id.id();
    }
    @Override
    public Object withMapId(Object item, Integer id) {
        ItemStack copy = ((ItemStack) item).copy();
        if (id == null) {
            copy.remove(DataComponents.MAP_ID);
        } else {
            copy.set(DataComponents.MAP_ID, new MapId(id));
        }
        return copy;
    }
    @Override
    @SuppressWarnings("unchecked")
    public SynchedEntityData.DataValue<?> replace(SynchedEntityData.DataValue<?> value, Object replacement) {
        return new SynchedEntityData.DataValue<>(value.id(), (EntityDataSerializer<Object>) value.serializer(), replacement);
    }
    @Override
    public SynchedEntityData.DataValue<?> skinParts(int index, byte parts) {
        return new SynchedEntityData.DataValue<>(index, EntityDataSerializers.BYTE, parts);
    }
    @Override
    public SynchedEntityData.DataValue<?> customName(int index, String name) {
        return new SynchedEntityData.DataValue<>(index, EntityDataSerializers.OPTIONAL_COMPONENT, Optional.of(Component.literal(name)));
    }
    @Override
    public SynchedEntityData.DataValue<?> nameVisible(int index, boolean visible) {
        return new SynchedEntityData.DataValue<>(index, EntityDataSerializers.BOOLEAN, visible);
    }
}
