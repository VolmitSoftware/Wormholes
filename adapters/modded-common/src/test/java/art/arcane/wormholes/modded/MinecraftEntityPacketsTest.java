package art.arcane.wormholes.modded;

import art.arcane.optics.entity.EntityProfile;
import art.arcane.optics.entity.MapSnapshot;
import art.arcane.optics.entity.SpoofedEntity;
import art.arcane.wormholes.render.ProjectedEntityIdentity;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDelta;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MinecraftEntityPacketsTest extends MinecraftTestBase {
    private static final int VEHICLE_ID = ProjectedEntityIdentity.MAX_ENTITY_ID;
    private static final int RIDER_ID = ProjectedEntityIdentity.MIN_ENTITY_ID;
    private static final AtomicInteger ENTITY_IDS = new AtomicInteger(1);

    @Test
    public void projectedSwingUsesTheNativeAnimationActionAndFakeId() {
        ClientboundSwingAnimationPacket packet = (ClientboundSwingAnimationPacket) MinecraftEntityPackets.animation(VEHICLE_ID,
            MinecraftEntityPackets.ANIMATION_SWING_OFF_HAND);
        assertEquals(VEHICLE_ID, packet.entityId());
        assertEquals(InteractionHand.OFF_HAND, packet.hand());
        assertEquals(MinecraftEntityPackets.ANIMATION_SWING_OFF_HAND, MinecraftEntityPackets.animationId(packet));
        ClientboundAnimatePacket critical = (ClientboundAnimatePacket) MinecraftEntityPackets.animation(VEHICLE_ID,
            MinecraftEntityPackets.ANIMATION_CRITICAL_HIT);
        assertEquals(ClientboundAnimatePacket.CRITICAL_HIT, critical.getAction());
        assertEquals(MinecraftEntityPackets.ANIMATION_CRITICAL_HIT, MinecraftEntityPackets.animationId(critical));
    }

    @Test
    public void playerInfoPreservesSignedSkinAndHidesTabEntry() {
        SpoofedEntity state = SpoofedEntity.create(ENTITY_IDS::getAndIncrement, true, false, true);
        state.setPlayerIdentity(ProjectedEntityIdentity.NAMING.syntheticProfileName(state.fakeUuid()), "PortalTester");
        ClientboundPlayerInfoUpdatePacket packet = MinecraftEntityPackets.playerInfo(RegistryAccess.EMPTY, state,
            new EntityProfile("PortalTester", "texture-value", "texture-signature"));
        ClientboundPlayerInfoUpdatePacket.Entry entry = packet.entries().getFirst();
        assertEquals(state.fakeUuid(), entry.profileId());
        assertEquals(state.playerProfileName(), entry.profile().name());
        assertEquals("texture-signature", entry.profile().properties().get("textures").iterator().next().signature());
        assertFalse(entry.listed());
        assertTrue(entry.showHat());
    }

    @Test
    public void relationshipsAndAbsoluteMotionUseFakeEntityIds() {
        ClientboundSetPassengersPacket riders = MinecraftEntityPackets.passengersPacket(VEHICLE_ID, new int[] {RIDER_ID});
        assertEquals(VEHICLE_ID, riders.getVehicle());
        assertArrayEquals(new int[] {RIDER_ID}, riders.getPassengers());
        ClientboundSetEntityLinkPacket leash = MinecraftEntityPackets.leashPacket(RIDER_ID, -1);
        assertEquals(RIDER_ID, leash.getSourceId());
        assertEquals(-1, leash.getDestId());
        Vec3 position = new Vec3(-120.25D, 65.75D, 340.5D);
        ClientboundTeleportEntityPacket teleport = MinecraftEntityPackets.teleport(VEHICLE_ID, position, 37.0F, -22.0F, true);
        assertEquals(position, teleport.change().position());
        assertTrue(teleport.relatives().isEmpty());
        assertTrue(teleport.onGround());
        assertEquals(new VecDelta.Linear((short) -6144, (short) 0, (short) 4096), MinecraftEntityPackets.delta(-1.5D, 0.0D, 1.0D));
    }

    @Test
    public void mapAndPlayerMetadataPreserveSharedProjectionSemantics() {
        byte[] pixels = new byte[MapSnapshot.PIXEL_COUNT];
        pixels[0] = 24;
        ClientboundMapItemDataPacket packet = MinecraftEntityPackets.mapPacket(new MapSnapshot(17, (byte) 2, true, true, pixels), -1_900_000_001);
        assertEquals(-1_900_000_001, packet.mapId().id());
        assertEquals((byte) 2, packet.scale());
        assertTrue(packet.locked());
        List<SynchedEntityData.DataValue<?>> metadata = MinecraftEntityMetadata.ENTITIES.upsideDownPlayer(
            List.of(new SynchedEntityData.DataValue<>(16, EntityDataSerializers.BYTE, (byte) 0x20)));
        assertEquals((byte) 0x21, metadata.getFirst().value());
        List<SynchedEntityData.DataValue<?>> label = MinecraftEntityPackets.labelMetadata("PortalTester");
        assertEquals(3, label.getFirst().value());
        assertEquals(10, label.getFirst().id());
    }
}
