package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorPairIdentity;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.door.DoorStateService;
import art.arcane.wormholes.door.PairEndpoint;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftDoorCollisionTargetTest extends MinecraftTestBase {
    @Test
    public void physicallyClosedMateCarriesItsExactOpenArrivalStateWithoutOpeningBlocks() throws Exception {
        Fixture fixture = new Fixture(new Route(DoorForm.DOOR, DoorOpenState.OPEN, DoorOpenState.OPEN));

        assertTrue(fixture.service.canArm(fixture.player, fixture.source.identity().itemId(), fixture.destination.identity().itemId()));
        assertEquals(new TravelMessage.DoorCollisionTarget(-17, 80, 33, true),
            fixture.service.collisionTarget(fixture.destination.identity().itemId()));
        assertFalse(fixture.blocks.get(fixture.destinationBlock).getValue(BlockStateProperties.OPEN));
        assertFalse(fixture.blocks.get(fixture.destinationBlock.above()).getValue(BlockStateProperties.OPEN));
        verify(fixture.level, never()).setBlock(any(BlockPos.class), any(BlockState.class), anyInt());
    }

    @Test
    public void trapdoorArrivalUsesItsOwnCellAndConfiguredPhysicalState() throws Exception {
        Fixture fixture = new Fixture(new Route(DoorForm.TRAPDOOR, DoorOpenState.OPEN, DoorOpenState.OPEN));

        assertTrue(fixture.service.canArm(fixture.player, fixture.source.identity().itemId(), fixture.destination.identity().itemId()));
        assertEquals(new TravelMessage.DoorCollisionTarget(-17, 80, 33, true),
            fixture.service.collisionTarget(fixture.destination.identity().itemId()));
        assertFalse(fixture.blocks.get(fixture.destinationBlock).getValue(BlockStateProperties.OPEN));
    }

    @Test
    public void contactSourcesAndContactDestinationsRemainOnOrdinaryTransit() throws Exception {
        Fixture source = new Fixture(new Route(DoorForm.DOOR, DoorOpenState.CLOSED, DoorOpenState.OPEN));
        Fixture destination = new Fixture(new Route(DoorForm.DOOR, DoorOpenState.OPEN, DoorOpenState.CLOSED));

        assertFalse(source.service.canArm(source.player, source.source.identity().itemId(), source.destination.identity().itemId()));
        assertFalse(destination.service.canArm(destination.player, destination.source.identity().itemId(), destination.destination.identity().itemId()));
        assertEquals(new TravelMessage.DoorCollisionTarget(-17, 80, 33, false),
            destination.service.collisionTarget(destination.destination.identity().itemId()));
    }

    @Test
    public void missingCoordinateFallbackAndDestroyedEndpointHaveNoCollisionOverride() throws Exception {
        Fixture fixture = new Fixture(new Route(DoorForm.DOOR, DoorOpenState.OPEN, DoorOpenState.OPEN));
        UUID fallback = UUID.randomUUID();
        when(fixture.state.findEndpointByItem(fallback)).thenReturn(Optional.empty());

        assertNull(fixture.service.collisionTarget(fallback));
        fixture.blocks.put(fixture.destinationBlock, Blocks.STONE.defaultBlockState());
        assertNull(fixture.service.collisionTarget(fixture.destination.identity().itemId()));
        assertFalse(fixture.service.canArm(fixture.player, fixture.source.identity().itemId(), fixture.destination.identity().itemId()));
    }

    private record Route(DoorForm form, DoorOpenState sourceState, DoorOpenState destinationState) {
    }

    private static final class Fixture {
        private final WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        private final MinecraftDoorService service = new MinecraftDoorService(runtime);
        private final DoorStateService state = mock(DoorStateService.class);
        private final ServerLevel level = mock(ServerLevel.class);
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private final BlockPos destinationBlock = new BlockPos(-17, 80, 33);
        private final PlacedDoorEndpoint source;
        private final PlacedDoorEndpoint destination;

        private Fixture(Route route) throws Exception {
            MinecraftServer server = mock(MinecraftServer.class);
            WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
            when(configuration.settings()).thenReturn(MinecraftTestSettings.defaults());
            when(server.getLevel(any())).thenReturn(level);
            when(level.hasChunk(anyInt(), anyInt())).thenReturn(true);
            when(level.getBlockState(any(BlockPos.class))).thenAnswer(call -> blocks.getOrDefault(call.getArgument(0), Blocks.AIR.defaultBlockState()));
            when(player.level()).thenReturn(level);
            when(player.isAlive()).thenReturn(true);
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            setField(service, "server", server);
            setField(service, "configuration", configuration);
            setField(service, "state", state);
            setField(service, "options", new MinecraftDoorService.Options(Path.of("."), mock(MinecraftDoorService.Access.class)));
            DoorPairIdentity pair = DoorPairIdentity.create();
            UUID world = UUID.randomUUID();
            source = endpoint(pair, PairEndpoint.A, new DoorPosition(world, "minecraft:overworld", 4, 80, 7), route.sourceState(), route.form());
            destination = endpoint(pair, PairEndpoint.B, new DoorPosition(world, "minecraft:overworld", -17, 80, 33), route.destinationState(), route.form());
            when(state.findMate(source.identity())).thenReturn(Optional.of(destination));
            when(state.findEndpointByItem(destination.identity().itemId())).thenReturn(Optional.of(destination));
            place(new BlockPos(4, 80, 7), route.form(), route.sourceState() == DoorOpenState.OPEN);
            place(destinationBlock, route.form(), false);
            activate(source);
        }

        private void place(BlockPos block, DoorForm form, boolean open) {
            BlockState data = (form == DoorForm.DOOR ? Blocks.OAK_DOOR : Blocks.OAK_TRAPDOOR).defaultBlockState()
                .setValue(BlockStateProperties.OPEN, open);
            if (form == DoorForm.DOOR) {
                data = data.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
                blocks.put(block.above(), data.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            }
            blocks.put(block, data);
        }

        @SuppressWarnings("unchecked")
        private void activate(PlacedDoorEndpoint endpoint) throws Exception {
            Class<?> activeType = Class.forName(MinecraftDoorService.class.getName() + "$ActiveDoor");
            Constructor<?> constructor = activeType.getDeclaredConstructor(PlacedDoorEndpoint.class);
            constructor.setAccessible(true);
            Field field = MinecraftDoorService.class.getDeclaredField("doors");
            field.setAccessible(true);
            Map<UUID, Object> doors = (Map<UUID, Object>) field.get(service);
            doors.put(endpoint.identity().itemId(), constructor.newInstance(endpoint));
        }

        private static PlacedDoorEndpoint endpoint(DoorPairIdentity pair, PairEndpoint side, DoorPosition position,
                                                   DoorOpenState open, DoorForm form) {
            return new PlacedDoorEndpoint(position, DoorItemIdentity.paired(pair.itemId(side), pair.pairId(), side, form),
                open, DoorProjectionState.INHERIT);
        }

        private static void setField(MinecraftDoorService service, String name, Object value) throws Exception {
            Field field = MinecraftDoorService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        }
    }
}
