package art.arcane.wormholes.modded;

import art.arcane.wormholes.render.ProjectedEntityMetadata;
import art.arcane.wormholes.render.ProjectedItemFrameMetadata;
import art.arcane.wormholes.util.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;

import java.util.Optional;

public final class MinecraftEntityMetadata implements ProjectedItemFrameMetadata.Access<SynchedEntityData.DataValue<?>>,
    ProjectedEntityMetadata.Access<SynchedEntityData.DataValue<?>> {
    public static final MinecraftEntityMetadata ACCESS = new MinecraftEntityMetadata();
    public static final ProjectedItemFrameMetadata<SynchedEntityData.DataValue<?>> FRAMES = new ProjectedItemFrameMetadata<>(ACCESS);
    public static final ProjectedEntityMetadata<SynchedEntityData.DataValue<?>> ENTITIES = new ProjectedEntityMetadata<>(ACCESS);

    private MinecraftEntityMetadata() {
    }

    @Override
    public int index(SynchedEntityData.DataValue<?> value) { return value.id(); }
    @Override
    public Object value(SynchedEntityData.DataValue<?> value) { return value.value(); }
    @Override
    public boolean isDirection(Object value) { return value instanceof net.minecraft.core.Direction; }
    @Override
    public boolean isItem(Object value) { return value instanceof ItemStack; }
    @Override
    public Object direction(Direction direction) {
        return switch (direction) {
            case D -> net.minecraft.core.Direction.DOWN;
            case U -> net.minecraft.core.Direction.UP;
            case N -> net.minecraft.core.Direction.NORTH;
            case S -> net.minecraft.core.Direction.SOUTH;
            case W -> net.minecraft.core.Direction.WEST;
            case E -> net.minecraft.core.Direction.EAST;
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
