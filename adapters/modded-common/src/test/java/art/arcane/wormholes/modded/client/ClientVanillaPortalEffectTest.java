package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.mixin.client.VanillaPortalEffectMixin;
import art.arcane.wormholes.portal.ApertureKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

public class ClientVanillaPortalEffectTest extends MinecraftTestBase {
    @Test
    public void onlyActiveManagedRootCellsSuppressVanillaEffects() {
        ClientViewSession session = new ClientViewSession(new WormholesClientConfig(),
            new ClientPalette(BuiltInRegistries.BLOCK), 1, "test");
        ApertureDescriptor geometry = geometry(ApertureKind.VANILLA_REPLACEMENT);
        session.portals().put(1, new ClientPortal(1, geometry, 1, 0.0D));
        assertFalse(session.managesVanillaPortal(10, 64, -8));
        session.accept(new ViewStreamMessage.Accept(1, ViewStreamCapability.ALL, ViewStreamLimits.DEFAULT_TICK_RATE,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
        assertTrue(session.managesVanillaPortal(10, 64, -8));
        assertFalse(session.managesVanillaPortal(11, 65, -8));
        assertFalse(session.managesVanillaPortal(10, 64, -7));
        assertFalse(session.managesVanillaPortal(9, 64, -8));
        session.portals().put(1, new ClientPortal(1, geometry.withParent(2), 1, 0.0D));
        assertFalse(session.managesVanillaPortal(10, 64, -8));
        session.portals().put(1, new ClientPortal(1, geometry(ApertureKind.FRAME), 1, 0.0D));
        assertFalse(session.managesVanillaPortal(10, 64, -8));
    }

    @Test
    public void sourceRootCoordinatesDoNotMatchAnUnattachedDestinationWorld() throws ReflectiveOperationException {
        WormholesClient client = mock(WormholesClient.class, CALLS_REAL_METHODS);
        ClientLevel source = mock(ClientLevel.class);
        ClientLevel destination = mock(ClientLevel.class);
        ClientViewSession session = mock(ClientViewSession.class);
        ClientPreparedTravel travel = mock(ClientPreparedTravel.class);
        Field attached = WormholesClient.class.getDeclaredField("attachedLevel");
        attached.setAccessible(true);
        attached.set(client, source);
        Field currentSession = WormholesClient.class.getDeclaredField("session");
        currentSession.setAccessible(true);
        currentSession.set(client, session);
        Field prepared = WormholesClient.class.getDeclaredField("preparedTravel");
        prepared.setAccessible(true);
        prepared.set(client, travel);
        BlockPos position = new BlockPos(10, 64, -8);
        when(session.managesVanillaPortal(10, 64, -8)).thenReturn(true);
        assertTrue(client.managesVanillaPortal(source, position));
        assertFalse(client.managesVanillaPortal(destination, position));
        when(travel.managesVanillaPortal(destination, position)).thenReturn(true);
        assertTrue(client.managesVanillaPortal(destination, position));
    }

    @Test
    public void managedEffectsCancelOnlyForTheLocalPlayerInTheActualClientWorld() throws ReflectiveOperationException {
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.level = mock(ClientLevel.class);
        minecraft.player = mock(LocalPlayer.class);
        WormholesClient client = mock(WormholesClient.class);
        BlockPos position = new BlockPos(10, 64, -8);
        Method hook = VanillaPortalEffectMixin.class.getDeclaredMethod("wormholes$managedEffect", BlockState.class,
            Level.class, BlockPos.class, Entity.class, InsideBlockEffectApplier.class, boolean.class, CallbackInfo.class);
        hook.setAccessible(true);
        VanillaPortalEffectMixin mixin = new VanillaPortalEffectMixin() { };
        try (MockedStatic<Minecraft> minecraftAccess = mockStatic(Minecraft.class);
             MockedStatic<WormholesClient> clientAccess = mockStatic(WormholesClient.class)) {
            minecraftAccess.when(Minecraft::getInstance).thenReturn(minecraft);
            clientAccess.when(WormholesClient::instance).thenReturn(client);
            CallbackInfo ordinary = new CallbackInfo("entityInside", true);
            hook.invoke(mixin, Blocks.NETHER_PORTAL.defaultBlockState(), minecraft.level, position, minecraft.player, null, true, ordinary);
            assertFalse(ordinary.isCancelled());
            when(client.managesVanillaPortal(minecraft.level, position)).thenReturn(true);
            CallbackInfo otherWorld = new CallbackInfo("entityInside", true);
            hook.invoke(mixin, Blocks.NETHER_PORTAL.defaultBlockState(), mock(Level.class), position, minecraft.player, null, true, otherWorld);
            assertFalse(otherWorld.isCancelled());
            CallbackInfo otherEntity = new CallbackInfo("entityInside", true);
            hook.invoke(mixin, Blocks.NETHER_PORTAL.defaultBlockState(), minecraft.level, position, mock(Entity.class), null, true, otherEntity);
            assertFalse(otherEntity.isCancelled());
            minecraft.player.portalProcess = new PortalProcessor((Portal) Blocks.NETHER_PORTAL, position);
            minecraft.player.portalEffectIntensity = 0.5F;
            minecraft.player.oPortalEffectIntensity = 0.4F;
            CallbackInfo managed = new CallbackInfo("entityInside", true);
            hook.invoke(mixin, Blocks.NETHER_PORTAL.defaultBlockState(), minecraft.level, position, minecraft.player, null, true, managed);
            assertTrue(managed.isCancelled());
            assertNull(minecraft.player.portalProcess);
            assertEquals(0.0F, minecraft.player.portalEffectIntensity, 0.0F);
            assertEquals(0.0F, minecraft.player.oPortalEffectIntensity, 0.0F);
        }
    }

    private static ApertureDescriptor geometry(int kind) {
        return new ApertureDescriptor(10, 64, -8, Face.N.ordinal(), true, 0, false, 3, 3,
            new long[]{0x1efL}, 0.0F, 0.0F, 0.0F, 8, 0, ApertureDescriptor.BLACKOUT_OFF, 0,
            ApertureDescriptor.MASK_AIR_PROJECT, 0, 0, kind, 0.0D, 0, 0L, List.of());
    }
}
