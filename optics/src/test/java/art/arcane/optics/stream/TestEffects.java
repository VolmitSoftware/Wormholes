package art.arcane.optics.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class TestEffects implements ViewStreamExtension<TestEffects.Effect> {
    static final int ID = 13;
    static final int MAX_NAMES = 255;
    static final int WORLD_KEY = 0;
    static final long CAPABILITY = ViewStreamCapability.extension(0);
    static final TestEffects INSTANCE = new TestEffects();

    private TestEffects() {
    }

    static ViewStreamMessage.Extension burst(String name) {
        return INSTANCE.wrap(new Effect(WORLD_KEY, List.of(name)));
    }

    static ViewStreamMessage.Extension scene(int key, List<String> names) {
        return INSTANCE.wrap(new Effect(key, names));
    }

    static Effect payload(ViewStreamMessage message) {
        return (Effect) ((ViewStreamMessage.Extension) message).payload();
    }

    @Override
    public int firstId() {
        return ID;
    }

    @Override
    public int lastId() {
        return ID;
    }

    @Override
    public boolean serverbound(int id) {
        return false;
    }

    @Override
    public boolean clientbound(int id) {
        return id == ID;
    }

    @Override
    public Class<Effect> type() {
        return Effect.class;
    }

    @Override
    public String name(int id) {
        return "EFFECTS";
    }

    @Override
    public int id(Effect message) {
        return ID;
    }

    @Override
    public void encode(Effect message, ViewStreamWriter out) throws ViewStreamProtocolException {
        out.varint(message.key());
        out.u8(message.names().size());
        for (String name : message.names()) {
            out.string(name);
        }
    }

    @Override
    public Effect decode(int id, ViewStreamReader in) throws ViewStreamProtocolException {
        int key = in.varint();
        int count = in.checkedCount(in.u8(), MAX_NAMES, 2);
        List<String> names = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            names.add(in.string());
        }
        return new Effect(key, names);
    }

    @Override
    public long capabilities() {
        return CAPABILITY;
    }

    @Override
    public List<Effect> coalesce(List<Effect> messages) {
        List<Effect> out = new ArrayList<Effect>();
        List<String> pending = new ArrayList<String>();
        int pendingKey = 0;
        for (Effect message : messages) {
            if (!pending.isEmpty() && message.key() != pendingKey) {
                out.add(new Effect(pendingKey, pending));
                pending.clear();
            }
            pendingKey = message.key();
            for (String name : message.names()) {
                pending.add(name);
                if (pending.size() == MAX_NAMES) {
                    out.add(new Effect(pendingKey, pending));
                    pending.clear();
                }
            }
        }
        if (!pending.isEmpty()) {
            out.add(new Effect(pendingKey, pending));
        }
        return out;
    }

    record Effect(int key, List<String> names) {
        Effect {
            Objects.requireNonNull(names, "names");
            names = List.copyOf(names);
            if (names.size() > MAX_NAMES) {
                throw new IllegalArgumentException("effect with " + names.size() + " names");
            }
        }
    }
}
