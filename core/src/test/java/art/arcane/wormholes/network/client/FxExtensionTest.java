package art.arcane.wormholes.network.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamProtocolException;

final class FxExtensionTest {
    @Test
    void burstsTravelOnTheWorldKey() {
        FxMessage.FxEmitter emitter = emitter(1);
        ViewStreamMessage.Extension burst = FxExtension.burst(emitter);
        assertEquals(FxMessage.FX, burst.id());
        assertEquals(new FxMessage.Fx(FxMessage.WORLD_FX_KEY, List.of(emitter)), burst.payload());
    }

    @Test
    void coalesceBatchesEmittersInOrderUpToTheEmitterCap() throws ViewStreamProtocolException {
        List<ViewStreamMessage.Extension> bursts = new ArrayList<ViewStreamMessage.Extension>();
        List<FxMessage.FxEmitter> emitters = new ArrayList<FxMessage.FxEmitter>();
        for (int i = 0; i < FxMessage.MAX_FX_EMITTERS + 45; i++) {
            emitters.add(emitter(i));
            bursts.add(FxExtension.burst(emitters.getLast()));
        }
        List<ViewStreamMessage.Extension> coalesced = ClientViewExtensions.CODEC.coalesce(bursts);
        assertEquals(List.of(FxExtension.INSTANCE.wrap(new FxMessage.Fx(FxMessage.WORLD_FX_KEY, emitters.subList(0, FxMessage.MAX_FX_EMITTERS))),
            FxExtension.INSTANCE.wrap(new FxMessage.Fx(FxMessage.WORLD_FX_KEY, emitters.subList(FxMessage.MAX_FX_EMITTERS, emitters.size())))),
            coalesced);
    }

    @Test
    void coalesceKeepsDifferentKeysApart() throws ViewStreamProtocolException {
        FxMessage.Fx world = new FxMessage.Fx(FxMessage.WORLD_FX_KEY, List.of(emitter(1)));
        FxMessage.Fx portal = new FxMessage.Fx(7, List.of(emitter(2)));
        List<ViewStreamMessage.Extension> coalesced = ClientViewExtensions.CODEC.coalesce(List.of(FxExtension.INSTANCE.wrap(world),
            FxExtension.INSTANCE.wrap(portal), FxExtension.INSTANCE.wrap(world)));
        assertEquals(List.of(FxExtension.INSTANCE.wrap(world), FxExtension.INSTANCE.wrap(portal), FxExtension.INSTANCE.wrap(world)), coalesced);
    }

    @Test
    void fxIsClientboundOnlyAndOffersEmitters() throws ViewStreamProtocolException {
        ViewStreamMessage.Extension burst = FxExtension.burst(emitter(3));
        assertEquals(burst, ClientViewExtensions.CODEC.decodeS2C(ClientViewExtensions.CODEC.encodeS2C(burst, 4, ViewStreamLimits.FLAG_LAST),
            ViewStreamCapability.ALL).message());
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewExtensions.CODEC.encodeC2S(burst));
        assertEquals(ClientViewExtensions.FX_EMITTERS, FxExtension.INSTANCE.capabilities());
    }

    @Test
    void unknownEmitterKindsAreProtocolExceptions() throws ViewStreamProtocolException {
        byte[] frame = ClientViewExtensions.CODEC.encodeS2C(FxExtension.burst(emitter(5)), 4, ViewStreamLimits.FLAG_LAST);
        frame[ViewStreamLimits.S2C_HEADER_BYTES + 2] = (byte) FxMessage.FxKind.values().length;
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewExtensions.CODEC.decodeS2C(frame, ViewStreamCapability.ALL));
    }

    private static FxMessage.FxEmitter emitter(int index) {
        return new FxMessage.FxEmitter(FxMessage.FxKind.BURST, "minecraft:portal", index, 64.0D, -index, 0.5F, 0.25F, 4, 0);
    }
}
