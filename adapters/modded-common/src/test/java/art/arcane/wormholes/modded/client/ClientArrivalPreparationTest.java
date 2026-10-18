package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.network.client.ArrivalMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import art.arcane.wormholes.modded.client.world.PreparedTerrainRenderer;
import org.mockito.MockedConstruction;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Set;
import org.mockito.MockedStatic;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;

import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientArrivalPreparationTest extends MinecraftTestBase {
    @Test
    public void onlyConsumedCommittedPositionCompletesArrivalLoading() throws Exception {
        for (boolean matching : new boolean[]{false, true}) {
            Minecraft minecraft = mock(Minecraft.class);
            LocalPlayer player = mock(LocalPlayer.class);
            minecraft.player = player;
            ClientArrivalPreparation arrivals = new ClientArrivalPreparation(ignored -> {}, 512L << 20);
            TravelMessage.TravelWorld world = new TravelMessage.TravelWorld("minecraft:the_end", "minecraft:the_end",
                1L, false, false, 0, 0, 256);
            TravelMessage.TravelPose target = new TravelMessage.TravelPose(520.5D, 200D, 8.5D, 0F, 90F);
            ArrivalMessage.Prepare prepare = new ArrivalMessage.Prepare(UUID.randomUUID(), 1L, UUID.randomUUID(), world,
                target, 2, "0".repeat(64), 0L, 0L, 0F, 0F, 100);
            ClientboundPlayerPositionPacket packet = mock(ClientboundPlayerPositionPacket.class);
            when(packet.change()).thenReturn(new PositionMoveRotation(
                new Vec3(matching ? 520.5D : 8.5D, 200D, 8.5D), Vec3.ZERO, 0F, 90F));
            when(packet.relatives()).thenReturn(Set.of());
            try (MockedStatic<Minecraft> singleton = mockStatic(Minecraft.class);
                 MockedStatic<PositionMoveRotation> positions = mockStatic(PositionMoveRotation.class, invocation ->
                     invocation.getMethod().getName().equals("of") && invocation.getArgument(0) == player
                         ? new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0F, 0F) : invocation.callRealMethod());
                 MockedConstruction<PreparedTerrainRenderer> renderers = mockConstruction(PreparedTerrainRenderer.class)) {
                singleton.when(Minecraft::getInstance).thenReturn(minecraft);
                assertFalse(arrivals.position(packet));
                Class<?> stageType = Class.forName(ClientArrivalPreparation.class.getName() + "$Stage");
                Constructor<?> constructor = stageType.getDeclaredConstructor(ClientArrivalPreparation.class,
                    ArrivalMessage.Prepare.class, ClientLevel.class);
                constructor.setAccessible(true);
                Object stage = constructor.newInstance(arrivals, prepare, mock(ClientLevel.class));
                stageField(stage, "commit", new ArrivalMessage.Commit(prepare.token(), prepare.revision(), target));
                stageField(stage, "adopted", true);
                stageField(stage, "respawning", true);
                Field active = ClientArrivalPreparation.class.getDeclaredField("stage");
                active.setAccessible(true);
                active.set(arrivals, stage);
                if (matching) {
                    assertTrue(arrivals.position(packet));
                } else {
                    assertFalse(arrivals.position(packet));
                }
                assertFalse(arrivals.position(packet));
            }
        }
    }


    @Test
    public void transportedHaloCannotBeUsedAsTheLandingCenter() {
        TravelMessage.TravelWorld world = new TravelMessage.TravelWorld("minecraft:overworld", "minecraft:overworld",
            1L, false, false, 63, -64, 384);
        ArrivalMessage.Prepare prepare = new ArrivalMessage.Prepare(UUID.randomUUID(), 1L, UUID.randomUUID(), world,
            new TravelMessage.TravelPose(520.5D, 200D, 8.5D, 0F, 0F), 2, "0".repeat(64), 0L, 0L, 0F, 0F, 100);
        assertTrue(ClientArrivalPreparation.matchesLanding(prepare, prepare.arrival()));
        assertTrue(ClientArrivalPreparation.matchesLanding(prepare, new TravelMessage.TravelPose(527.9D, 208D, 15.9D, 90F, 0F)));
        assertFalse(ClientArrivalPreparation.matchesLanding(prepare, new TravelMessage.TravelPose(528D, 200D, 8.5D, 0F, 0F)));
        assertFalse(ClientArrivalPreparation.matchesLanding(prepare, new TravelMessage.TravelPose(520.5D, 200D, 16D, 0F, 0F)));
        assertFalse(ClientArrivalPreparation.matchesLanding(prepare, new TravelMessage.TravelPose(520.5D, 208.1D, 8.5D, 0F, 0F)));
    }

    @Test
    public void clearFencesQueuedPreparationEvenWithoutAnActiveStage() {
        Minecraft minecraft = mock(Minecraft.class);
        ClientPacketListener connection = mock(ClientPacketListener.class);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        List<ArrivalMessage> sent = new ArrayList<>();
        when(minecraft.getConnection()).thenReturn(connection);
        doAnswer(invocation -> {
            queued.set(invocation.getArgument(0));
            return null;
        }).when(minecraft).execute(any(Runnable.class));
        try (MockedStatic<Minecraft> singleton = mockStatic(Minecraft.class)) {
            singleton.when(Minecraft::getInstance).thenReturn(minecraft);
            ClientArrivalPreparation arrivals = new ClientArrivalPreparation(sent::add, 512L << 20);
            TravelMessage.TravelWorld world = new TravelMessage.TravelWorld("minecraft:overworld", "minecraft:overworld",
                1L, false, false, 63, -64, 384);
            ArrivalMessage.Prepare prepare = new ArrivalMessage.Prepare(UUID.randomUUID(), 1L, UUID.randomUUID(), world,
                new TravelMessage.TravelPose(8.5D, 80D, 8.5D, 0F, 0F), 1, "0".repeat(64), 0L, 0L, 0F, 0F, 100);
            arrivals.deliver(prepare);
            arrivals.clear();
            when(minecraft.isSameThread()).thenReturn(true);
            queued.get().run();
            assertTrue(sent.isEmpty());
        }
    }

    @Test
    public void onlyTheCommittedServerPositionCanConsumePreparedTerrain() {
        TravelMessage.TravelPose target = new TravelMessage.TravelPose(4104.5D, 80.0D, -2055.5D, 180.0F, 10.0F);
        assertTrue(ClientArrivalPreparation.matchesPosition(target,
            new PositionMoveRotation(new Vec3(4104.5D, 80.0D, -2055.5D), Vec3.ZERO, -180.0F, 10.0F)));
        assertFalse(ClientArrivalPreparation.matchesPosition(target,
            new PositionMoveRotation(new Vec3(4104.5D, 81.0D, -2055.5D), Vec3.ZERO, -180.0F, 10.0F)));
        assertFalse(ClientArrivalPreparation.matchesPosition(target,
            new PositionMoveRotation(new Vec3(8.5D, 80.0D, 8.5D), Vec3.ZERO, -180.0F, 10.0F)));
        assertFalse(ClientArrivalPreparation.matchesPosition(target,
            new PositionMoveRotation(new Vec3(4104.5D, 80.0D, -2055.5D), Vec3.ZERO, 0.0F, 10.0F)));
    }

    @Test
    public void destinationTypeAndSeedMustMatchBeforeTerrainOwnershipMoves() {
        TravelMessage.TravelWorld target = new TravelMessage.TravelWorld("minecraft:custom", "minecraft:overworld", 42L,
            false, false, 63, -64, 384);
        assertTrue(ClientArrivalPreparation.matchesWorld(target, target));
        assertFalse(ClientArrivalPreparation.matchesWorld(target, new TravelMessage.TravelWorld("minecraft:other",
            "minecraft:overworld", 42L, false, false, 63, -64, 384)));
        assertFalse(ClientArrivalPreparation.matchesWorld(target, new TravelMessage.TravelWorld("minecraft:custom",
            "minecraft:the_nether", 42L, false, false, 63, 0, 256)));
        assertFalse(ClientArrivalPreparation.matchesWorld(target, new TravelMessage.TravelWorld("minecraft:custom",
            "minecraft:overworld", 43L, false, false, 63, -64, 384)));
    }

    private static void stageField(Object stage, String name, Object value) throws Exception {
        Field field = stage.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(stage, value);
    }

}
