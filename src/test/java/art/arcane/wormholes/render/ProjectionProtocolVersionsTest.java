package art.arcane.wormholes.render;

import art.arcane.wormholes.network.view.PacketBlobs;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProjectionProtocolVersionsTest {
    @ParameterizedTest
    @EnumSource(value = ServerVersion.class, names = {"V_26_1_2", "V_26_2", "V_26_3"})
    void projectedBlockMetadataAndEquipmentSurviveTheWireFormat(ServerVersion version) {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        recorder.serverVersion(version);
        try {
            StateType type = version == ServerVersion.V_26_3 ? StateTypes.POPLAR_PLANKS : StateTypes.OAK_PLANKS;
            WrappedBlockState state = WrappedBlockState.getDefaultState(version.toClientVersion(), type);
            assertTrue(state.getGlobalId() > 0);
            List<EntityData<?>> metadata = List.of(new EntityData<>(23, EntityDataTypes.BLOCK_STATE, state.getGlobalId()));
            List<EntityData<?>> decodedMetadata = PacketBlobs.readMetadata(PacketBlobs.writeMetadata(metadata));
            assertEquals(23, decodedMetadata.getFirst().getIndex());
            assertEquals(state.getGlobalId(), decodedMetadata.getFirst().getValue());

            ItemType itemType = version == ServerVersion.V_26_3 ? ItemTypes.POPLAR_PLANKS : ItemTypes.OAK_PLANKS;
            ItemStack item = ItemStack.builder().type(itemType).amount(3).build();
            List<Equipment> equipment = List.of(new Equipment(EquipmentSlot.MAIN_HAND, item));
            List<Equipment> decodedEquipment = PacketBlobs.readEquipment(PacketBlobs.writeEquipment(equipment));
            assertEquals(EquipmentSlot.MAIN_HAND, decodedEquipment.getFirst().getSlot());
            assertEquals(itemType, decodedEquipment.getFirst().getItem().getType());
            assertEquals(3, decodedEquipment.getFirst().getItem().getAmount());
        } finally {
            recorder.uninstall();
        }
    }
}
