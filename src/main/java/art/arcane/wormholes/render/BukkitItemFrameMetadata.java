package art.arcane.wormholes.render;

import java.util.ArrayList;
import java.util.List;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import art.arcane.wormholes.util.Direction;

final class BukkitItemFrameMetadata {
    static final ProjectedItemFrameMetadata<EntityData<?>> TRANSFORM = new ProjectedItemFrameMetadata<>(new Access());

    private BukkitItemFrameMetadata() {
    }

    static boolean isItemFrame(EntityType entityType) {
        return entityType == EntityTypes.ITEM_FRAME || entityType == EntityTypes.GLOW_ITEM_FRAME;
    }

    static boolean isHanging(EntityType entityType) {
        return isItemFrame(entityType) || entityType == EntityTypes.PAINTING;
    }

    private static BlockFace blockFace(Direction direction) {
        return switch (direction) {
            case D -> BlockFace.DOWN;
            case U -> BlockFace.UP;
            case N -> BlockFace.NORTH;
            case S -> BlockFace.SOUTH;
            case W -> BlockFace.WEST;
            case E -> BlockFace.EAST;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> EntityData<T> replaceValue(EntityData<?> source, T value) {
        EntityDataType<T> type = (EntityDataType<T>) source.getType();
        return new EntityData<T>(source.getIndex(), type, value);
    }
    private static final class Access implements ProjectedItemFrameMetadata.Access<EntityData<?>> {
        public int index(EntityData<?> value) { return value.getIndex(); }
        public Object value(EntityData<?> value) { return value.getValue(); }
        public boolean isDirection(Object value) { return value instanceof BlockFace; }
        public boolean isItem(Object value) { return value instanceof ItemStack; }
        public Object direction(Direction direction) { return blockFace(direction); }
        public Integer mapId(Object item) { return ((ItemStack) item).getComponent(ComponentTypes.MAP_ID).orElse(null); }
        public Object withMapId(Object item, Integer id) {
            ItemStack copy = ((ItemStack) item).copy();
            if (id == null) {
                copy.unsetComponent(ComponentTypes.MAP_ID);
            } else {
                copy.setComponent(ComponentTypes.MAP_ID, id);
            }
            return copy;
        }
        public EntityData<?> replace(EntityData<?> value, Object replacement) { return replaceValue(value, replacement); }
    }
}
