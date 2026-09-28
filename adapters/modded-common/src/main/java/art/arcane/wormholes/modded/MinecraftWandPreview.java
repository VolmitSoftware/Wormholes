package art.arcane.wormholes.modded;

import art.arcane.wormholes.WandSelectionGeometry;
import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

final class MinecraftWandPreview {
    private final int entityId;

    MinecraftWandPreview(ServerPlayer player, int[] cornerA, int[] cornerB) {
        boolean complete = cornerA != null && cornerB != null;
        int[] a = cornerA == null ? cornerB : cornerA;
        int[] b = cornerB == null ? cornerA : cornerB;
        int[] min = WandSelectionGeometry.selectionMin(a, b);
        int[] max = WandSelectionGeometry.selectionMax(a, b);
        int flat = WandSelectionGeometry.flatAxis(min, max);
        boolean valid = complete && flat >= 0
            && WandSelectionGeometry.cellCount(min, max) <= WandSelectionGeometry.MAX_DRAWN_CELLS;
        float[] box = WandSelectionGeometry.paneBox(min, max, complete ? flat : -1, 0.12f, 0.02f);
        if (complete && flat >= 0 && player.level().getBlockState(new BlockPos(min[0], min[1], min[2])).isSolid()) {
            double normal = switch (flat) {
                case 0 -> player.getX();
                case 1 -> player.getY();
                default -> player.getZ();
            };
            box[flat + 3] = normal > min[flat] + 0.5 ? 1.005f : -0.125f;
        }
        CompoundTag data = new CompoundTag();
        data.store("transformation", Transformation.EXTENDED_CODEC, new Transformation(
            new Vector3f(box[3], box[4], box[5]), new Quaternionf(),
            new Vector3f(box[0], box[1], box[2]), new Quaternionf()));
        data.store("block_state", BlockState.CODEC,
            Blocks.STAINED_GLASS.pick(!complete || valid ? DyeColor.LIGHT_BLUE : DyeColor.RED).defaultBlockState());
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        data.put("brightness", brightness);
        data.putFloat("view_range", 4.0f);
        Display.BlockDisplay display = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, player.level());
        display.load(TagValueInput.create(ProblemReporter.DISCARDING, player.level().registryAccess(), data));
        display.setPos(min[0], min[1], min[2]);
        entityId = display.getId();
        player.connection.send(new ClientboundAddEntityPacket(entityId, display.getUUID(),
            min[0], min[1], min[2], 0, 0, EntityTypes.BLOCK_DISPLAY, 0, Vec3.ZERO, 0));
        List<SynchedEntityData.DataValue<?>> values = display.getEntityData().getNonDefaultValues();
        if (values != null && !values.isEmpty()) {
            player.connection.send(new ClientboundSetEntityDataPacket(entityId, values));
        }
    }

    void remove(ServerPlayer player) {
        if (!player.hasDisconnected()) {
            player.connection.send(new ClientboundRemoveEntitiesPacket(entityId));
        }
    }
}
