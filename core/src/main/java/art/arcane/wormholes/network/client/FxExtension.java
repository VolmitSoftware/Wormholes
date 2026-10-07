package art.arcane.wormholes.network.client;

import java.util.ArrayList;
import java.util.List;

import art.arcane.optics.stream.ViewStreamExtension;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamReader;
import art.arcane.optics.stream.ViewStreamWriter;

public final class FxExtension implements ViewStreamExtension<FxMessage> {
    public static final FxExtension INSTANCE = new FxExtension();

    private FxExtension() {
    }

    public static ViewStreamMessage.Extension burst(FxMessage.FxEmitter emitter) {
        return INSTANCE.wrap(new FxMessage.Fx(FxMessage.WORLD_FX_KEY, List.of(emitter)));
    }

    @Override
    public int firstId() {
        return FxMessage.FX;
    }

    @Override
    public int lastId() {
        return FxMessage.FX;
    }

    @Override
    public boolean serverbound(int id) {
        return false;
    }

    @Override
    public boolean clientbound(int id) {
        return id == FxMessage.FX;
    }

    @Override
    public Class<FxMessage> type() {
        return FxMessage.class;
    }

    @Override
    public String name(int id) {
        return "FX";
    }

    @Override
    public int id(FxMessage message) {
        return FxMessage.FX;
    }

    @Override
    public long capabilities() {
        return ClientViewExtensions.FX_EMITTERS;
    }

    @Override
    public void encode(FxMessage message, ViewStreamWriter out) throws ViewStreamProtocolException {
        FxMessage.Fx fx = switch (message) {
            case FxMessage.Fx value -> value;
        };
        out.varint(fx.portalKey());
        out.u8(fx.emitters().size());
        for (FxMessage.FxEmitter emitter : fx.emitters()) {
            out.u8(emitter.kind().ordinal());
            out.string(emitter.key());
            out.f64(emitter.x());
            out.f64(emitter.y());
            out.f64(emitter.z());
            out.f32(emitter.paramA());
            out.f32(emitter.paramB());
            out.u16(emitter.ticks());
            out.u8(emitter.flags());
        }
    }

    @Override
    public FxMessage decode(int id, ViewStreamReader in) throws ViewStreamProtocolException {
        if (id != FxMessage.FX) {
            throw new ViewStreamProtocolException("Unknown fx message " + id);
        }
        int portalKey = in.varint();
        int count = in.checkedCount(in.u8(), FxMessage.MAX_FX_EMITTERS, 37);
        List<FxMessage.FxEmitter> emitters = new ArrayList<FxMessage.FxEmitter>(count);
        for (int i = 0; i < count; i++) {
            FxMessage.FxKind kind = FxMessage.FxKind.byId(in.u8());
            if (kind == null) {
                throw new ViewStreamProtocolException("unknown fx kind");
            }
            String key = in.string();
            double x = in.f64();
            double y = in.f64();
            double z = in.f64();
            float a = in.f32();
            float b = in.f32();
            int ticks = in.u16();
            emitters.add(new FxMessage.FxEmitter(kind, key, x, y, z, a, b, ticks, in.u8()));
        }
        return new FxMessage.Fx(portalKey, emitters);
    }

    @Override
    public List<FxMessage> coalesce(List<FxMessage> messages) {
        List<FxMessage> out = new ArrayList<FxMessage>(messages.size());
        List<FxMessage.FxEmitter> pending = new ArrayList<FxMessage.FxEmitter>();
        int pendingKey = 0;
        for (FxMessage message : messages) {
            FxMessage.Fx fx = switch (message) {
                case FxMessage.Fx value -> value;
            };
            if (!pending.isEmpty() && fx.portalKey() != pendingKey) {
                flush(pendingKey, pending, out);
            }
            pendingKey = fx.portalKey();
            for (FxMessage.FxEmitter emitter : fx.emitters()) {
                pending.add(emitter);
                if (pending.size() == FxMessage.MAX_FX_EMITTERS) {
                    flush(pendingKey, pending, out);
                }
            }
        }
        if (!pending.isEmpty()) {
            flush(pendingKey, pending, out);
        }
        return out;
    }

    private static void flush(int portalKey, List<FxMessage.FxEmitter> pending, List<FxMessage> out) {
        out.add(new FxMessage.Fx(portalKey, pending));
        pending.clear();
    }
}
