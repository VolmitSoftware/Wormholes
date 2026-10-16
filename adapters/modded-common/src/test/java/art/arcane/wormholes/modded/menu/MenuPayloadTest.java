package art.arcane.wormholes.modded.menu;

import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.protocol.MenuProtocol;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.Test;

import java.util.SplittableRandom;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class MenuPayloadTest {
    @Test
    public void payloadIsTypedOnTheMenuChannel() {
        MenuPayload payload = new MenuPayload(new byte[] {32, 1});

        assertEquals(MenuProtocol.CHANNEL, MenuPayload.ID.toString());
        assertSame(MenuPayload.TYPE, payload.type());
        assertEquals(MenuPayload.ID, MenuPayload.TYPE.id());
    }

    @Test
    public void frameRoundTripsAsTheWholePayloadBody() {
        byte[] frame = {2, 0, 7, -1, 127, -128};

        MenuPayload decoded = roundTrip(new MenuPayload(frame));

        assertArrayEquals(frame, decoded.data());
    }

    @Test
    public void emptyFrameRoundTrips() {
        MenuPayload decoded = roundTrip(new MenuPayload(new byte[0]));

        assertEquals(0, decoded.data().length);
    }

    @Test
    public void largestServerFrameRoundTrips() {
        byte[] frame = new byte[MenuLimits.MAX_S2C_BYTES];
        new SplittableRandom(41L).nextBytes(frame);

        MenuPayload decoded = roundTrip(new MenuPayload(frame));

        assertArrayEquals(frame, decoded.data());
    }

    @Test
    public void serverboundCodecCarriesTheLargestClientFrame() {
        byte[] frame = new byte[MenuLimits.MAX_C2S_BYTES];
        new SplittableRandom(43L).nextBytes(frame);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MenuPayload.SERVERBOUND_CODEC.encode(buffer, new MenuPayload(frame));

            assertArrayEquals(frame, MenuPayload.SERVERBOUND_CODEC.decode(buffer).data());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void serverboundBodyAboveTheClientLimitIsRejectedBeforeCopying() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeZero(MenuLimits.MAX_C2S_BYTES + 1);

            assertThrows(IllegalArgumentException.class, () -> MenuPayload.SERVERBOUND_CODEC.decode(buffer));
            assertEquals(MenuLimits.MAX_C2S_BYTES + 1, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void bodyAboveTheLargestFrameIsRejectedBeforeCopying() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeZero(MenuLimits.MAX_S2C_BYTES + 1);

            assertThrows(IllegalArgumentException.class, () -> MenuPayload.CODEC.decode(buffer));
            assertEquals(MenuLimits.MAX_S2C_BYTES + 1, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test(expected = NullPointerException.class)
    public void payloadRequiresData() {
        new MenuPayload(null);
    }

    private static MenuPayload roundTrip(MenuPayload payload) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MenuPayload.CODEC.encode(buffer, payload);
            MenuPayload decoded = MenuPayload.CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }
}
